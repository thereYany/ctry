package com.wherelee.ctry.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 首次启动创建演示账号（管理员/教师/学员）时使用的初始密码。
 *
 * <p>留空则随机生成并在启动日志打印一次；账号已存在时不会重置密码。</p>
 */
@ConfigurationProperties(prefix = "app.seed")
public record SeedProperties(
        String adminPassword,
        String teacherPassword,
        String studentPassword
) {
}
