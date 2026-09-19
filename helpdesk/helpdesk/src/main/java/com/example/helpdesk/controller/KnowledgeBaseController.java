package com.example.helpdesk.controller;

import com.example.helpdesk.common.Result;
import com.example.helpdesk.common.UserContext;
import com.example.helpdesk.dto.CreateDocumentRequest;
import com.example.helpdesk.dto.RetrievedChunk;
import com.example.helpdesk.entity.KbDocument;
import com.example.helpdesk.service.KnowledgeBaseService;
import com.example.helpdesk.service.RetrievalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ClassName:KnowledgeBaseController
 * Package:com.example.helpdesk.controller
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/13 16:41
 * @Version 1.0
 */

@RestController
@RequestMapping("/api/console/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;

    @PostMapping("/documents")
    public Result<Long> upload(@Valid @RequestBody CreateDocumentRequest request){
        Long docId = knowledgeBaseService.upload(
                request.getTitle(),
                request.getContent(),
                UserContext.getUserId()
        );
        return Result.success(docId);
    }

    @GetMapping("/documents")
    public Result<List<KbDocument>> list(){
        return Result.success(knowledgeBaseService.list());
    }

    //测试es接口
    @GetMapping("/search-test")
    public Result<List<RetrievedChunk>> searchTest(@RequestParam("q") String q,
                                                   @RequestParam(defaultValue = "3") Integer k
    ){
        return Result.success(retrievalService.searchByVector(q,k));
    }

}
