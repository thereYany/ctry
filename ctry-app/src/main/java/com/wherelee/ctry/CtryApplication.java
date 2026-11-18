package com.wherelee.ctry;

import com.wherelee.ctry.bootstrap.DemoProperties;
import com.wherelee.ctry.bootstrap.SeedProperties;
import com.wherelee.ctry.modules.learning.playback.PlaybackProperties;
import com.wherelee.ctry.modules.operations.alert.AlertProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 网课垂直应用启动类。
 *
 * <p>业务代码（课程/学习/订单/权益/运营）全在本仓库；登录、支付、审计、租户等内核能力
 * 通过 Maven 依赖 btry 的 btry-platform / btry-contracts / btry-identity / btry-payment。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.wherelee.ctry",
        "com.wherelee.base.modules.identity",
        "com.wherelee.base.modules.payment"
})
@MapperScan({
        "com.wherelee.ctry.modules.course.governance",
        "com.wherelee.ctry.modules.learning.playback"
})
@EnableConfigurationProperties({
        SeedProperties.class,
        DemoProperties.class,
        PlaybackProperties.class,
        AlertProperties.class,
        com.wherelee.ctry.modules.course.teacher.UploadProperties.class
})
@EnableScheduling
public class CtryApplication {

    public static void main(String[] args) {
        SpringApplication.run(CtryApplication.class, args);
    }
}
