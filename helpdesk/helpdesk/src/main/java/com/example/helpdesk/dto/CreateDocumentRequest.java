package com.example.helpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * ClassName:CreateDocumentRequest
 * Package:com.example.helpdesk.dto
 * Description:
 *   上传知识文档的入参
 * @Author 妄汐霜
 * @Create 2026/9/13 15:51
 * @Version 1.0
 */
@Data
public class CreateDocumentRequest {
    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;


}
