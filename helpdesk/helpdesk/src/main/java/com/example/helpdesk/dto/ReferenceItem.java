package com.example.helpdesk.dto;

/**
 * ClassName:ReferenceItem
 * Package:com.example.helpdesk.dto
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/21 18:21
 * @Version 1.0
 */
public record ReferenceItem
    (Long documentId,
    String docTitle,
    Integer chunkIndex,
    Double score,
    String snippet){
}
