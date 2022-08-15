package com.xxxx.seckill.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手机号码校验工具类
 */
public class ValidatorUtil {

    //正则表达式定义手机号格式规则
    private static final Pattern mobile_pattern=Pattern.compile("[1]([3-9])[0-9]{9}$");

    //判断给定的手机号是否符合上述规则
    public static boolean isMobile(String mobile){
        if(StringUtils.isEmpty(mobile)){
            return false;
        }
        Matcher matcher=mobile_pattern.matcher(mobile);
        return matcher.matches();
    }
}
