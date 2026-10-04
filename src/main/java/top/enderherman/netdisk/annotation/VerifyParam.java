package top.enderherman.netdisk.annotation;

import top.enderherman.netdisk.entity.enums.VerifyRegexEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 参数校验注解
 * Target 指定注解的使用位置
 * Retention 指定此注解的生命周期
 */
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VerifyParam {

    /**
     * 默认-1 不启用此校验
     */
    int min() default -1;

    /**
     * 默认-1 不启用此校验
     */
    int max() default -1;


    /**
     *默认不需要值
     */
    boolean required() default false;

    /**
     * 默认正则不校验
     */
    VerifyRegexEnum regex() default VerifyRegexEnum.NO;

}
