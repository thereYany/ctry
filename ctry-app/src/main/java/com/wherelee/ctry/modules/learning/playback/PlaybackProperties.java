package com.wherelee.ctry.modules.learning.playback;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 学习域播放配置：播放日志保留天数（签名 URL 缓存参数在 platform 的 app.storage 下）。
 */
@ConfigurationProperties(prefix = "app.playback")
public record PlaybackProperties(int logRetentionDays) {
}
