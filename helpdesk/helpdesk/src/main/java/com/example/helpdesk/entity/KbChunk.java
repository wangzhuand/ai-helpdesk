package com.example.helpdesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ClassName:KbChunk
 * Package:com.example.helpdesk.entity
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/12 16:38
 * @Version 1.0
 */
@Data
@TableName("kb_chunk")
public class KbChunk {
@TableId(type = IdType.AUTO)
private Long id;
//属于哪篇文章
private Long documentId;
//是这篇文章的那一块
private Integer chunkIndex;
//分块内容
private String content;
//ES中的ID
private String esId;

}
