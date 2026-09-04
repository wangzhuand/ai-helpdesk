package com.example.helpdesk.common;

import lombok.Getter;

/**
 * ClassName:BusinessException
 * Package:com.example.helpdesk.common
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 19:17
 * @Version 1.0
 */
@Getter
public class BusinessException extends RuntimeException{
    private final int code;

    public BusinessException(int code,String message){
        super(message);
        this.code=code;
    }
    public BusinessException(String message){
        this(400,message);
    }


}
