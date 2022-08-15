package com.xxxx.seckill.config;

import com.xxxx.seckill.pojo.User;

/**
 * 秒杀意味着高并发，多线程
 * ThreadLocal存放的是当前线程的私有值，保证了线程安全
 */
public class UserContext {

    private static ThreadLocal<User> userHolder = new ThreadLocal<>();

    public static void setUser(User user){
        userHolder.set(user);
    }

    public static User getUser(){
        return userHolder.get();
    }
}
