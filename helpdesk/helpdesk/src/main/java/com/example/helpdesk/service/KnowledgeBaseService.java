package com.example.helpdesk.service;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.helpdesk.dto.KbDocProcessMessage;

import com.example.helpdesk.dto.KbDocTxArg;
import com.example.helpdesk.entity.KbDocument;
import com.example.helpdesk.mapper.KbDocumentMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import java.util.List;



/**
 * ClassName:KnowledgeBaseService
 * Package:com.example.helpdesk.service
 * Description:
 *知识库服务：
 * 上传不再同步，只“落库+发消息”，真正的处理逻辑搬到了KbDocProcessConsumer
 *
 * @Author 妄汐霜
 * @Create 2026/9/13 15:53
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {
    //文档处理消息队列的topic，生产者在这里定义，消费者引用他--抽成常量防止写错
   public static final String KB_DOC_TOPIC="Kb-doc-process";

   private final KbDocumentMapper kbDocumentMapper;
   private final RocketMQTemplate rocketMQTemplate;
   private final ObjectMapper objectMapper;

   /*
   上传文档：只做“落库+发消息+返回"

   改之前：逐块调百联向量化，逐块写es，一份20块的文档要几十秒（前端三十秒直接超时断开）
   改之后：1次insert+1次发消息，几十毫秒返回

   W7把这个方法改成事务方法
    */
   public Long upload(String title,String content,Long createBy){
       //提前生成ID
       Long docId = IdWorker.getId();

       KbDocTxArg arg = new KbDocTxArg();
       arg.setDocId(docId);
       arg.setTitle(title);
       arg.setContent(content);
       arg.setCreateBy(createBy);

        //发事务消息：Broker收半消息，【同步】回调executeLocalTransaction
        sendProcessMessageInTransaction(docId,arg);

       // 本地事务失败了？抛出去让接口正常报错，而不是返回一个数据库里不存在的 docId
       if (arg.getError() != null) {
           throw new IllegalStateException("文档入库失败", arg.getError());
       }

       log.info("文档已入库并投递处理消息，docId={}, title={}", docId, title);
       return docId;
   }


        //发消息的方法
    public void sendProcessMessage(Long docId,int attempt,int delayLevel){
       String json;
       try {
           json = objectMapper.writeValueAsString(new KbDocProcessMessage(docId,attempt));
       }catch (JsonProcessingException e){
           throw new IllegalStateException("文档处理消息序列化失败",e);
       }

       if(delayLevel > 0){
           Message<String> message = MessageBuilder.withPayload(json).build();
           rocketMQTemplate.syncSend(KB_DOC_TOPIC,message,3000,delayLevel);
           log.error("已投递【延迟重投】消息：docId={},attempt={},delayLevel={}",docId,attempt,delayLevel);
       }else {
           rocketMQTemplate.convertAndSend(KB_DOC_TOPIC,json);
       }



    }

    public List<KbDocument> list(){
       return kbDocumentMapper.selectList(
               new LambdaQueryWrapper<KbDocument>().orderByDesc(KbDocument::getId));

    }




    /*
    发事务方法的消息：
    Broker先发半消息，消费者不可见，等监听器的结果再决定投不投
     */
    public void sendProcessMessageInTransaction(Long docId,Object txArg){
        String json;
        try {
            json = objectMapper.writeValueAsString(new KbDocProcessMessage(docId,0));
        }catch (JsonProcessingException e){
            throw new IllegalStateException("文档处理消息序列化失败",e);
        }

        Message<String> message = MessageBuilder.withPayload(json).build();
        rocketMQTemplate.sendMessageInTransaction(KB_DOC_TOPIC,message,txArg);



    }





}
