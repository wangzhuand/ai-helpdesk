package com.example.helpdesk.component;
import com.example.helpdesk.dto.KbDocProcessMessage;
import com.example.helpdesk.dto.KbDocTxArg;
import com.example.helpdesk.entity.KbDocument;
import com.example.helpdesk.mapper.KbDocumentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.stereotype.Component;
import org.springframework.messaging.Message;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * ClassName:KbDocTransactionListener
 * Package:com.example.helpdesk.component
 * Description:
 * 文档上传的事务消息监听器，
 * 本地事务和回查都在这里面执行
 *
 * @Author 妄汐霜
 * @Create 2026/10/8 16:32
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQTransactionListener
public class KbDocTransactionListener implements RocketMQLocalTransactionListener {
    private final KbDocumentMapper kbDocumentMapper;
    private final ObjectMapper objectMapper;

    //本地事务方法：把文档插入库
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg,Object arg){
        KbDocTxArg txArg = (KbDocTxArg) arg;
        try{
            KbDocument doc = new KbDocument();
            doc.setId(txArg.getDocId());
            doc.setTitle(txArg.getTitle());
            doc.setContent(txArg.getContent());
            doc.setSourceType("TEXT");
            doc.setStatus("PENDING");
            doc.setChunkNum(0);
            doc.setCreatedBy(txArg.getCreateBy());
            doc.setCreatedAt(LocalDateTime.now());
            kbDocumentMapper.insert(doc);

            log.info("[事务消息]本地事务成功入库，docId={}",doc.getId());
            return RocketMQLocalTransactionState.COMMIT;
        }catch (Exception e){
            txArg.setError(e);
            log.error("[事务消息]本地入库失败，返回ROLLBACK",e);
            return RocketMQLocalTransactionState.ROLLBACK;

        }

    }


    //回查：Broker问这条消息的本地事务到底成没成功
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg){
        Long docId;
        try {
            String body = new String((byte[]) msg.getPayload(), StandardCharsets.UTF_8);
            docId = objectMapper.readValue(body, KbDocProcessMessage.class).getDocId();
        }catch (Exception e){
            log.error("[事务消息]回查时解析失败，返回UNKNOW等下次回查",e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }

        try{
            KbDocument doc = kbDocumentMapper.selectById(docId);
            if (doc != null){
                log.info("[事务消息回查]：docId={} 已入库-》commit",docId);
                return RocketMQLocalTransactionState.COMMIT;
            }
            log.warn("[事务消息]回查：docId={} 不存在-》ROLLBACK",docId);
            return RocketMQLocalTransactionState.ROLLBACK;
        }catch (Exception e){
            log.error("[事务消息]回查时查库失败，返回UNKNOW等下次回查，docId={}",docId,e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }






    }







}