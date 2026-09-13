package com.example.helpdesk.controller;

import com.example.helpdesk.Result;
import com.example.helpdesk.common.UserContext;
import com.example.helpdesk.dto.CreateDocumentRequest;
import com.example.helpdesk.entity.KbDocument;
import com.example.helpdesk.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

}
