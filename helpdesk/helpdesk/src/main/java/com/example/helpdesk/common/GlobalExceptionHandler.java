package com.example.helpdesk.common;


import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 全局异常处理器：兜住所有未处理异常，统一包装成 Result 信封返回
 * <p>作用：任何接口抛异常，都不会把堆栈和 500 页面甩给前端</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        log.warn("请求了不存在的资源: {}", e.getResourcePath());
        return Result.error(404, "资源不存在");
    }

    /*
    参数校验失败，比如@notblank/@notnull不满足时抛这个
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e){
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        // getField() = 字段名，getDefaultMessage() = 注解里写的提示语（别把整个 FieldError 拼进字符串，
        // 它的 toString() 会输出一长串内部信息，不适合给前端看）
        String message = errors.isEmpty()
                ? "参数校验失败"
                : "参数校验失败：" + errors.get(0).getField() + " " + errors.get(0).getDefaultMessage();
        log.warn("参数校验失败: {}", message);
        return Result.error(400,message);
    }

    /*
    请求体读不出来，body整个缺失,JSON语法错误，字段类型对不上
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e){
        log.warn("请求体读不出来: {}", e.getMessage());
        return Result.error(400, "请求体缺失或者格式错误");
    }






    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e){
        return Result.error(e.getCode(),e.getMessage());
    }




    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
