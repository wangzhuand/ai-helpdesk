package com.example.helpdesk.common;

import javax.management.relation.Role;

/**
 * ClassName:UserContext
 * Package:com.example.helpdesk.common
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/6 15:22
 * @Version 1.0
 */
/*
当前用户的上下文，保证每个请求是独立的
 */
public class UserContext {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    public static void set(Long userId,String role){
        USER_ID.set(userId);
        ROLE.set(role);
    }
    public static Long getUserId(){
        return USER_ID.get();
    }
    public static String getRole(){
        return ROLE.get();
    }

    //结束请求
    public static void clear(){
        USER_ID.remove();
        ROLE.remove();
    }


}
