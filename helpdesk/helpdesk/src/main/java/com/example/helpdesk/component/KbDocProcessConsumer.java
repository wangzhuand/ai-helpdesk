package com.example.helpdesk.component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.helpdesk.client.EmbeddingClient;
import com.example.helpdesk.dto.KbDocProcessMessage;
import com.example.helpdesk.entity.KbChunk;
import com.example.helpdesk.entity.KbDocument;
import com.example.helpdesk.mapper.KbChunkMapper;
import com.example.helpdesk.mapper.KbDocumentMapper;
import com.example.helpdesk.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ClassName:KbDocProcessConsumer
 * Package:com.example.helpdesk.component
 * Description:
 *文档处理消费者：原来写在 KnowledgeBaseService.upload() 里的
 *   "分块 → 逐块向量化 → 双写 MySQL/ES → 改状态"整段逻辑，搬到了这里。
 *
 *    为什么搬：那段逻辑要打几十次 HTTP（百炼 + ES），同步跑会把接口卡几十秒；
 *    搬到消费者后，接口只需"发条消息"，剩下的慢慢在后台做。
 * @Author 妄汐霜
 * @Create 2026/10/4 22:28
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = KnowledgeBaseService.KB_DOC_TOPIC,consumerGroup = "kb-doc-group")
public class KbDocProcessConsumer implements RocketMQListener<String> {
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final String ES_INDEX = "kb_chunk";
    //最大重试次数
    private static final int MAX_ATTEMPT = 3;
    /**
     * 每次失败后安排重投的延迟等级（下标 = 当前 attempt）：
     *   attempt=0 失败 → 等 10 秒 → 重投 attempt=1   （等级 3）
     *   attempt=1 失败 → 等 30 秒 → 重投 attempt=2   （等级 4）
     *   attempt=2 失败 → 等 60 秒 → 重投 attempt=3   （等级 5）
     * 越等越久：临时故障（网络抖动/限流）通常前几次就好了；一直失败说明问题不小，放慢节奏别猛打百炼
     */
    private static final int[] RETRY_DELAY_LEVELS = {3,4,5};




    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;
    private final EmbeddingClient embeddingClient;
    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper;
    //失败重投时，用它发消息
    private final KnowledgeBaseService knowledgeBaseService;

    //每来一条消息，MQ就调这个方法
    @Override
    public void onMessage(String body) {
        KbDocProcessMessage msg;
        try {
            msg = objectMapper.readValue(body, KbDocProcessMessage.class);
        } catch (Exception e) {
            log.error("文档处理消息格式不对，丢弃：{}", body, e);
            return;
        }
        Long docId = msg.getDocId();
        int attempt = msg.getAttempt() == null ? 0 : msg.getAttempt();
        log.info("文档处理消息：docId={},attempt={}", docId, attempt);

        KbDocument doc = kbDocumentMapper.selectById(docId);
        if (doc == null) {
            log.error("文档不存在，丢弃：{}", docId);
            return;
        }

        //第一道幂等
       if (doc.getContent() == null || doc.getContent().isBlank()){
           log.error("[文档处理]文档没有正文，无法处理,docId={}",docId);
           markFailed(doc);
           return;
       }

       //抢处理权：一条带条件的UPDATE同时干两件事：
        //1幂等，状态已经不是PENDING（已READY/正被别人处理）-》 影响0行，跳过
        //2，防并发。就算同一条消息被投了两次，也只有第一个线程能把PENDING改成PROCESSING
        int locked = kbDocumentMapper.update(null,new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId,docId)
                .eq(KbDocument::getStatus,"PENDING")
                .set(KbDocument::getStatus,"PROCESSING")
        );
       if (locked == 0){
           log.info("文档不在待处理状态，跳过：docId={},status = {}",docId,doc.getStatus());
           return;
       }
        doc.setStatus("PROCESSING");



        try {

            //幂等第二道
            kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>()
                    .eq(KbChunk::getDocumentId, docId));


            List<String> chunks = split(doc.getContent());
            for (int i = 0; i < chunks.size(); i++) {
                String text = chunks.get(i);

                float[] vector = embeddingClient.embed(text);

                KbChunk chunk = new KbChunk();
                chunk.setDocumentId(doc.getId());
                chunk.setChunkIndex(i);
                chunk.setContent(text);
                kbChunkMapper.insert(chunk);

                String esId = "doc-" + doc.getId() + "-" + i;
                writeToEs(esId, doc.getId(), doc.getTitle(), i, text, vector);

                chunk.setEsId(esId);
                kbChunkMapper.updateById(chunk);
            }


            doc.setStatus("READY");
            doc.setChunkNum(chunks.size());
            kbDocumentMapper.updateById(doc);
            log.info("【文档处理】完成, docId={}, 块数={}", docId, chunks.size());


        } catch (Exception e) {
            handleFailure(doc,attempt,e);
        }

    }
    /**
     * 处理失败的统一出口。
     *
     * 策略：【延迟重投 + 次数上限】，不用 MQ 自带的重试。
     *   attempt <  MAX_ATTEMPT → 状态退回 PENDING（表示"排队等重投"）+ 发一条延迟消息
     *   attempt >= MAX_ATTEMPT → 认输：置 FAILED，人工介入
     *
     * 为什么状态要退回 PENDING（而不是停在 PROCESSING）：
     *   ① 语义准确：它现在确实在"排队等重投"，不在"处理中"
     *   ② 抢处理权的条件是 status = PENDING，退回后重投的消息才抢得到锁
     *   ③ 万一重投消息丢了，前端看到"排队中"还能察觉；停在"处理中"会一直骗人
     *
     * 依然【不抛异常】：一抛就交给 MQ 自带的重试（16 次、每次重跑全量、烧钱），
     * 重试节奏我们自己控制。
     */
    private void handleFailure(KbDocument doc,int attempt,Exception e){
        Long docId = doc.getId();
        log.error("[文档处理]失败，docId={},attempt={}",docId,attempt,e);
        if (attempt >= MAX_ATTEMPT){
            log.error("[文档处理]重试{}次仍失败，表示FAILED，docId={}",MAX_ATTEMPT,docId);
            markFailed(doc);
            return;
        }


        try {
            //先改状态，再发消息
            //重投的消息10秒后才到，届时状态就要是PENDING，它才抢得到锁
            doc.setStatus("PENDING");
            kbDocumentMapper.updateById(doc);
            knowledgeBaseService.sendProcessMessage(docId,attempt+1,RETRY_DELAY_LEVELS[attempt]);
        }catch (Exception sendEx){
            log.error("[文档处理]重投消息发送失败，标记FAILED，docId={}",docId,sendEx);
            markFailed(doc);
        }

    }









    private void markFailed(KbDocument doc) {
        doc.setStatus("FAILED");
        kbDocumentMapper.updateById(doc);
    }

    private void writeToEs(String esId, Long documentId, String title,
                           int chunkIndex, String text, float[] vector) throws IOException {
        Map<String, Object> document = new HashMap<>();
        document.put("content", text);
        document.put("document_id", documentId);
        document.put("chunk_index", chunkIndex);
        document.put("doc_title", title);
        document.put("embedding", vector);

        esClient.index(i -> i.index(ES_INDEX).id(esId).document(document));
    }

    private List<String> split(String content) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + CHUNK_SIZE, content.length());
            chunks.add(content.substring(start, end));
            if (end == content.length()) {
                break;
            }
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }


}
