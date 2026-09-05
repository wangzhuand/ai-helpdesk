package com.example.helpdesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;

import java.time.LocalDateTime;

/**
 * ClassName:Visitor
 * Package:com.example.helpdesk.entity
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 21:16
 * @Version 1.0
 */
@Data
@TableName("visitor")
public class Visitor {
    @TableId(type = IdType.AUTO)
private Long id;
private String nickname;
private String lastIp;
private LocalDateTime createdAt;
}
