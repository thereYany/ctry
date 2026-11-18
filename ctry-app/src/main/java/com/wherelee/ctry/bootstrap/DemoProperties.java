package com.wherelee.ctry.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 演示课程种子数据使用的视频对象 key 与时长。
 */
@ConfigurationProperties(prefix = "app.demo")
public record DemoProperties(String videoKey, int videoDurationSeconds) {
}
