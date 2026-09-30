package com.example.helpdesk.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ClassName:SseSessionRegistry
 * Package:com.example.helpdesk.component
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/29 17:10
 * @Version 1.0
 */
@Component
@Slf4j
public class SseSessionRegistry {
    //会话id->这个会话的所有订阅连接(一个会话可能开多个标签页)
    private final Map<Long, List<SseEmitter>> sessions = new ConcurrentHashMap<>();

    //1.访客订阅，注册一条连接
    public void register(Long conversationId,SseEmitter emitter){
        sessions.computeIfAbsent(conversationId,k->new CopyOnWriteArrayList<>()).add(emitter);

        //注册时就绑定清理  --否则访客一关页面就留一个死连接，内存一直涨
        emitter.onCompletion(()-> remove(conversationId,emitter));
        emitter.onTimeout(() -> remove(conversationId,emitter));
        emitter.onError(e->remove(conversationId,emitter));
    }

    //2.移除（幂等：重复调用不会报错）
    public void remove(Long conversationId,SseEmitter emitter){
        List<SseEmitter> list = sessions.get(conversationId);
        if(list != null){
            list.remove(emitter);
            if (list.isEmpty()){
                sessions.remove(conversationId);
            }

        }
    }

    //3.推送:把事件推给某个会话的所有连接
    public void push(Long conversationId,String eventName,Object data) {
        List<SseEmitter> list = sessions.get(conversationId);
        if (list == null || list.isEmpty()) {
            return;  //访客不在线，不推送
        }

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                //推送失败的话，直接把这条连接清理了
                remove(conversationId, emitter);
                log.info("推送失败，清理连接={}",conversationId);
            }

        }


    }
}
