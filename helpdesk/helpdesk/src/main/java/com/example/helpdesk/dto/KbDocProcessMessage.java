package com.example.helpdesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ClassName:KbDocProcessMessage
 * Package:com.example.helpdesk.dto
 * Description:
 *
 * 文档处理消息体：
 * 只带docId，正文以mysql为准，不塞进消息
 * attempt是给失败重试用的
 *
 * @Author 妄汐霜
 * @Create 2026/10/4 22:03
 * @Version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbDocProcessMessage {
    private Long docId;
    private Integer attempt;
}
