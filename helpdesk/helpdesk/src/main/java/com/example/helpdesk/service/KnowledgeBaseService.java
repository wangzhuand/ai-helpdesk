package com.example.helpdesk.service;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.example.helpdesk.dto.KbDocProcessMessage;

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
    */
   public Long upload(String title,String content,Long createBy){
       //1.落库
       // 状态写PENDING，PROCESSING交给消费者去置，这样前端能区分派对中和处理中
       //正文必须写进来，异步处理之后它的代码跑在另一个线程里，拿不到方法参数
       KbDocument doc = new KbDocument();
       doc.setTitle(title);
       doc.setContent(content);
       doc.setSourceType("TEXT");
       doc.setStatus("PENDING");
       doc.setChunkNum(0);
       doc.setCreatedBy(createBy);
       doc.setCreatedAt(LocalDateTime.now());
       kbDocumentMapper.insert(doc);

        //2,把这个要处理的文档放进MQ
       sendProcessMessage(doc.getId(),0,0);

       //3.立刻返回
       log.info("文档已入库并投递处理消息，docId={},title={}",doc.getId(),doc.getTitle());
       return doc.getId();
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



}
