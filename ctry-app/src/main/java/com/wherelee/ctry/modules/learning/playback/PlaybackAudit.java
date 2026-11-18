package com.wherelee.ctry.modules.learning.playback;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 播放链路审计标记：垂直域自己的观测切面，内核不感知媒体概念。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlaybackAudit {
    String value() default "";
}
