package com.example.helpdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.helpdesk.entity.Message;
import jakarta.validation.constraints.NotBlank;
import org.apache.ibatis.annotations.Mapper;

/**
 * ClassName:MessageMapper
 * Package:com.example.helpdesk.mapper
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/5 20:08
 * @Version 1.0
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
