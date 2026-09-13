package com.example.helpdesk;

import lombok.Data;

/**
 * 统一响应体：所有接口返回同一个信封 { code, message, data }
 * <p>code=0 成功，非 0 失败；data 用泛型，成功时能装任意类型</p>
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(0);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}