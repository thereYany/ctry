package com.wherelee.ctry.bootstrap;

// 演示/初始种子数据加载器。
//
// 它横跨 t_user / t_series / t_series_chapter / t_series_video / t_student_series 五张业务表，
// 属于「业务装配」而不是「内核能力」，因此放在 bootstrap 包、不归属任何业务模块，
// 避免 platform 内核反向依赖业务表（A 期数据所有权规则 R1）。

import com.wherelee.edu.modules.platform.common.PasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Component
@Profile("!integration")
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final DemoProperties demo;
    private final SeedProperties seed;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUser("admin", seed.adminPassword(), "管理员", "ADMIN");
        Long teacherId = seedUser("teacher", seed.teacherPassword(), "物理老师", "TEACHER");
        Long studentId = seedUser("student", seed.studentPassword(), "演示学生", "STUDENT");

        if (count("t_series") == 0) {
            seedSeries(teacherId);
        }

        // 只把「物理必修一」分配给演示学生用于学习空间；「物理必修二」保持未购买，用于演示课程商店 → 下单 → Mock 支付全链路
        Long physics1 = idByTitle("物理必修一");
        assign(studentId, physics1, 1);
        log.info("学习空间示例数据就绪 student={} series={}；课程商店可购买系列：物理必修二", studentId, physics1);
    }

    private Long seedUser(String username, String configuredPassword, String nickname, String role) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE tenant_id = 0 AND username = ?", Integer.class, username);
        if (existing == null || existing == 0) {
            boolean generated = configuredPassword == null || configuredPassword.isBlank();
            String password = generated ? PasswordGenerator.generate() : configuredPassword;
            jdbc.update("""
                    INSERT INTO t_user
                        (tenant_id, username, password_hash, nickname, role, status,
                         must_change_password, token_version)
                    VALUES (0, ?, ?, ?, ?, 'ACTIVE', TRUE, 0)
                    """, username, passwordEncoder.encode(password), nickname, role);
            if (generated) {
                log.warn("初始化账号 username={} role={} 随机初始密码={}（仅本次启动打印，首次登录必须改密）",
                        username, role, password);
            } else {
                log.info("创建账号 username={} role={}，初始密码来自配置，首次登录必须改密", username, role);
            }
        }
        Long userId = jdbc.queryForObject(
                "SELECT id FROM t_user WHERE tenant_id = 0 AND username = ?", Long.class, username);
        jdbc.update("""
                INSERT IGNORE INTO t_user_role (tenant_id, user_id, role_id)
                SELECT 0, ?, r.id FROM t_role r WHERE r.tenant_id = 0 AND r.code = ?
                """, userId, role);
        return userId;
    }

    private void seedSeries(Long teacherId) {
        Long physics1 = insertSeries(teacherId, "物理必修一", "高中物理必修一 · 录播课程", 1, 9900L);
        Long c11 = insertChapter(physics1, "第一章 运动的描述", 1);
        insertVideo(c11, "1.1 质点 参考系", 1);
        insertVideo(c11, "1.2 时间 位移", 2);
        insertVideo(c11, "1.3 位置变化快慢的描述——速度", 3);
        Long c12 = insertChapter(physics1, "第二章 匀变速直线运动", 2);
        insertVideo(c12, "2.1 实验：探究小车速度随时间变化的规律", 1);
        insertVideo(c12, "2.2 匀变速直线运动的速度与时间的关系", 2);
        insertVideo(c12, "2.3 匀变速直线运动的位移与时间的关系", 3);

        Long physics2 = insertSeries(teacherId, "物理必修二", "高中物理必修二 · 录播课程", 2, 12900L);
        Long c21 = insertChapter(physics2, "第一章 抛体运动", 1);
        insertVideo(c21, "1.1 曲线运动", 1);
        insertVideo(c21, "1.2 运动的合成与分解", 2);
        insertVideo(c21, "1.3 实验：探究平抛运动的特点", 3);
        Long c22 = insertChapter(physics2, "第二章 圆周运动", 2);
        insertVideo(c22, "2.1 圆周运动", 1);
        insertVideo(c22, "2.2 向心力", 2);
        insertVideo(c22, "2.3 向心加速度", 3);
    }

    private Long insertSeries(Long teacherId, String title, String summary, int sort, long priceCents) {
        jdbc.update("""
                INSERT INTO t_series (tenant_id, teacher_id, title, summary, sort, status, published_at, sale_status, price_cents, currency)
                VALUES (0, ?, ?, ?, ?, 'PUBLISHED', CURRENT_TIMESTAMP(3), 'ON_SALE', ?, 'CNY')
                """, teacherId, title, summary, sort, priceCents);
        return jdbc.queryForObject("SELECT id FROM t_series WHERE title = ?", Long.class, title);
    }

    private Long insertChapter(Long seriesId, String title, int sort) {
        jdbc.update("""
                INSERT INTO t_series_chapter (tenant_id, series_id, title, sort)
                VALUES (0, ?, ?, ?)
                """, seriesId, title, sort);
        return jdbc.queryForObject("""
                SELECT id FROM t_series_chapter WHERE series_id = ? AND title = ?
                """, Long.class, seriesId, title);
    }

    private void insertVideo(Long chapterId, String title, int sort) {
        jdbc.update("""
                INSERT INTO t_series_video
                    (tenant_id, chapter_id, title, sort, video_key, media_status, duration_seconds)
                VALUES (0, ?, ?, ?, ?, 'READY', ?)
                """, chapterId, title, sort, demo.videoKey(), demo.videoDurationSeconds());
    }

    private void assign(Long studentId, Long seriesId, int sort) {
        if (studentId == null || seriesId == null) {
            return;
        }
        jdbc.update("""
                INSERT IGNORE INTO t_student_series (tenant_id, user_id, series_id, sort, source, status, granted_by, granted_at)
                VALUES (0, ?, ?, ?, 'ADMIN', 'ACTIVE', 1, CURRENT_TIMESTAMP(3))
                """, studentId, seriesId, sort);
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private Long idByTitle(String title) {
        return jdbc.queryForObject("SELECT id FROM t_series WHERE title = ?", Long.class, title);
    }
}
