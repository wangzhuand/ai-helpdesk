package com.example.helpdesk.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ClassName:KbDocTxArg
 * Package:com.example.helpdesk.dto
 * Description:
 * 这个类的作用是回传状态，也就是MQ事务消息的第四部
 *
 * @Author 妄汐霜
 * @Create 2026/10/8 16:19
 * @Version 1.0
 */
@Data
@NoArgsConstructor
public class KbDocTxArg {
    //输入时要定好的东西
    private Long docId;
    private String title;
    private String content;
    private Long createBy;

    //输出时候要带出来的东西
    private Exception error;


}