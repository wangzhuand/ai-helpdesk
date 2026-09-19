package com.example.helpdesk.dto;

import lombok.Data;

/**
 * ClassName:RetrievedChunk
 * Package:com.example.helpdesk.dto
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/18 21:12
 * @Version 1.0
 */
@Data
public class RetrievedChunk {
    private Long documentId;
    private String docTitle;
    private Integer chunkIndex;
    private String content;
    private Double score;//相似度分数
}
