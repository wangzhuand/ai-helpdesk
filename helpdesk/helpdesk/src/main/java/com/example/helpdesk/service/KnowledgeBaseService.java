package com.example.helpdesk.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.helpdesk.client.EmbeddingClient;
import com.example.helpdesk.common.BusinessException;
import com.example.helpdesk.entity.KbChunk;
import com.example.helpdesk.entity.KbDocument;
import com.example.helpdesk.mapper.KbChunkMapper;
import com.example.helpdesk.mapper.KbDocumentMapper;
import com.example.helpdesk.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * ClassName:KnowledgeBaseService
 * Package:com.example.helpdesk.service
 * Description:
 *  知识库：文档-》分块-》向量化-》双写-》状态机
 * @Author 妄汐霜
 * @Create 2026/9/13 15:53
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {
//每块最多多少字
    private static final int CHUNK_SIZE = 500;
//相邻块重叠多少字
    private static final int CHUNK_OVERLAP = 50;

    private static final String ES_INDEX = "kb_chunk";

    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;
    private final EmbeddingClient embeddingClient;
    private final ElasticsearchClient esClient;

    public Long upload(String title,String content,Long createBy){
        //先把文档存库
        KbDocument doc = new KbDocument();
        doc.setTitle(title);
        doc.setSourceType("TEXT");
        doc.setStatus("PROCESSING");
        doc.setChunkNum(0);
        doc.setCreatedBy(createBy);
        doc.setCreatedAt(LocalDateTime.now());
        kbDocumentMapper.insert(doc);

        try {
            //分块
            List<String> chunks = split(content);

            //逐块处理，每块都先向量化，存mysql，写es，回填esId
            for(int i = 0;i < chunks.size();i++){
                String text = chunks.get(i);

                float[] vector = embeddingClient.embed(text);

                KbChunk chunk = new KbChunk();
                chunk.setDocumentId(doc.getId());
                chunk.setChunkIndex(i);
                chunk.setContent(text);
                chunk.setContent(text);
                kbChunkMapper.insert(chunk);

                String esId = "doc-" + doc.getId() + "-" + i;
                writeToEs(esId,doc.getId(),title,i,text,vector);

                chunk.setEsId(esId);
                kbChunkMapper.updateById(chunk);
            }
                //全部成功，设置状态为就绪
                doc.setStatus("READY");
                doc.setChunkNum(chunks.size());
                kbDocumentMapper.updateById(doc);

                log.info("Document ID: {}, Chunk Num: {}", doc.getId(), chunks.size());
                return doc.getId();


        }catch (Exception e){
            log.error("文档知识库处理失败，id=", doc.getId(),e);
            doc.setStatus("FAILED");
            kbDocumentMapper.updateById(doc);
            throw  new BusinessException("文档处理失败" + e.getMessage());
        }

    }


    public List<KbDocument> list() {
        return kbDocumentMapper.selectList(
                new LambdaQueryWrapper<KbDocument>().orderByDesc(KbDocument::getId));
    }

    private void writeToEs(String esId, Long documentId, String title,
                           int chunkIndex, String text, float[] vector) throws IOException {
        Map<String, Object> doc = new HashMap<>();
        doc.put("content", text);
        doc.put("document_id", documentId);
        doc.put("chunk_index", chunkIndex);
        doc.put("doc_title", title);
        doc.put("embedding", vector);

        esClient.index(i -> i.index(ES_INDEX).id(esId).document(doc));
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
