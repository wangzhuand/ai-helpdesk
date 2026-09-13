package com.example.helpdesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ClassName:kbDocument
 * Package:com.example.helpdesk.entity
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/12 16:28
 * @Version 1.0
 */
/*
这张表对应数据库文档那张表,一份上传的文档会被分成多个kbChunk
 */
    @Data
    @TableName("kb_document")
public class KbDocument {
@TableId(type = IdType.AUTO)
private Long id;

private String title;

private String sourceType;

private String status;

private Integer chunkNum;

private Long createdBy;

private LocalDateTime createdAt;


}
