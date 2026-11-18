package com.wherelee.ctry.integration;

import com.jayway.jsonpath.JsonPath;
import com.wherelee.ctry.modules.commerce.integration.CommerceIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ctry 全链路验收测试：真实 HTTP（MockMvc）+ 真实 MySQL/Redis + 真实内核支付事件链路。
 *
 * <p>顺序完全对应用户旅程：登录 → 课程商店 → 下单 → 发起 Mock 支付 → 模拟支付成功
 * → 支付事件投递（btry 内核发件箱）→ 订单履约 → 权益发放 → 学习空间 → 播放地址 → 学习进度。</p>
 *
 * <p>这是「ctry 独立可运行、依赖 btry 内核」的可复现证据：登录/支付/邀请等内核能力来自
 * btry 依赖 jar，课程/订单/权益/学习全部由本仓库提供。</p>
 */
@AutoConfigureMockMvc
class CtryEndToEndIntegrationTest extends CommerceIntegrationTestSupport {

    private static final String BEARER = "Bearer ";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("全链路：登录 → 课程商店 → 下单 → Mock 支付 → 权益发放 → 学习空间/播放地址/进度")
    void fullCoursePurchaseAndLearningJourney() throws Exception {
        // ---------- 1. 登录（内核 btry-identity 提供） ----------
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("it_student")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.user.username").value("it_student"))
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = JsonPath.read(loginBody, "$.token");
        assertThat(token).isNotBlank();

        // ---------- 2. 课程商店：在售课程可见且未拥有 ----------
        String catalogBody = mockMvc.perform(get("/api/catalog/series").header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.seriesId == " + fx.seriesId() + ")]").exists())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Number>>read(catalogBody, "$[*].seriesId"))
                .extracting(Number::longValue)
                .contains(fx.seriesId());
        assertThat(JsonPath.<List<Boolean>>read(catalogBody, "$[*].owned")).containsOnly(false);

        // ---------- 3. 未购买时学习空间为空 ----------
        String emptySpace = mockMvc.perform(get("/api/learning/series").header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Object>>read(emptySpace, "$")).isEmpty();

        // ---------- 4. 下单（ctry 课程订单） ----------
        String orderBody = mockMvc.perform(post("/api/orders")
                        .header("Authorization", BEARER + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seriesId":%d,"clientRequestId":"e2e-%d"}
                                """.formatted(fx.seriesId(), System.nanoTime())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seriesId").value(fx.seriesId()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.priceCents").value(fx.priceCents()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String orderNo = JsonPath.read(orderBody, "$.orderNo");
        assertThat(orderNo).isNotBlank();

        // ---------- 5. 发起支付：业务单号交给 btry 支付内核 ----------
        mockMvc.perform(post("/api/orders/" + orderNo + "/pay").header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYING"))
                .andExpect(jsonPath("$.channel").value("MOCK"))
                .andExpect(jsonPath("$.cashierUrl").value("/mock-pay/" + orderNo));
        String paymentNo = jdbc.queryForObject(
                "SELECT payment_no FROM t_order WHERE order_no = ?", String.class, orderNo);
        assertThat(paymentNo).isNotBlank();
        assertThat(count("t_payment_order", "payment_no = ? AND biz_type = 'COURSE_ORDER' AND biz_no = ?",
                paymentNo, orderNo)).isEqualTo(1);

        // ---------- 6. Mock 收银台与模拟支付成功 ----------
        mockMvc.perform(get("/api/payments/mock/" + orderNo).header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(orderNo))
                .andExpect(jsonPath("$.priceCents").value(fx.priceCents()))
                .andExpect(jsonPath("$.scenarios").isArray());
        mockMvc.perform(post("/api/payments/mock/" + orderNo + "/events")
                        .header("Authorization", BEARER + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(orderNo))
                .andExpect(jsonPath("$.orderStatus").value("FULFILLED"));

        // ---------- 7. 支付事件 → 履约 → 权益：三张事实表互证 ----------
        String orderDetail = mockMvc.perform(get("/api/orders/" + orderNo).header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("FULFILLED"))
                .andExpect(jsonPath("$.order.owned").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(orderDetail).isNotBlank();
        assertThat(count("t_order", "order_no = ? AND status = 'FULFILLED'", orderNo)).isEqualTo(1);
        assertThat(count("t_student_series", "user_id = ? AND series_id = ? AND status = 'ACTIVE'",
                fx.studentId(), fx.seriesId())).isEqualTo(1);
        assertThat(count("t_payment_order", "payment_no = ? AND status = 'PAID'", paymentNo)).isEqualTo(1);
        assertThat(count("t_payment_outbox", "aggregate_no = ? AND status = 'SENT'", paymentNo)).isEqualTo(1);

        // ---------- 8. 学习空间 / 系列详情 ----------
        String spaceBody = mockMvc.perform(get("/api/learning/series").header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Number>>read(spaceBody, "$[*].id"))
                .extracting(Number::longValue)
                .containsExactly(fx.seriesId());
        String seriesBody = mockMvc.perform(get("/api/learning/series/" + fx.seriesId())
                        .header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fx.seriesId()))
                .andExpect(jsonPath("$.chapters[0].videos[0].id").value(fx.videoId()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(seriesBody).isNotBlank();

        // ---------- 9. 播放地址：内核对象存储预签名 URL + 播放日志 ----------
        String playBody = mockMvc.perform(get("/api/learning/videos/" + fx.videoId() + "/play")
                        .header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(fx.videoId()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String playUrl = JsonPath.read(playBody, "$.playUrl");
        long urlExpiresAt = ((Number) JsonPath.read(playBody, "$.urlExpiresAt")).longValue();
        assertThat(playUrl).startsWith("http").contains("ci-test-bucket");
        assertThat(urlExpiresAt).isGreaterThan(System.currentTimeMillis() / 1000);

        // 播放日志由异步写线程批量落库，最多等 10s
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(count("t_playback_log",
                        "user_id = ? AND video_id = ? AND event_type = 'URL_REQUEST'",
                        fx.studentId(), fx.videoId())).isEqualTo(1));

        // ---------- 10. 学习进度写入且幂等 ----------
        mockMvc.perform(put("/api/learning/videos/" + fx.videoId() + "/progress")
                        .header("Authorization", BEARER + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"positionSeconds":30,"durationSeconds":60,"completed":false}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/learning/series/" + fx.seriesId())
                        .header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters[0].videos[0].positionSeconds").value(30));
        mockMvc.perform(put("/api/learning/videos/" + fx.videoId() + "/progress")
                        .header("Authorization", BEARER + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"positionSeconds":35,"durationSeconds":60,"completed":true}
                                """))
                .andExpect(status().isOk());
        assertThat(count("t_learning_progress", "user_id = ? AND video_id = ?",
                fx.studentId(), fx.videoId())).isEqualTo(1);
        // 完成标记会把进度归到片尾（position=duration），且多写一次不会产生第二行
        assertThat(jdbc.queryForObject(
                "SELECT position_seconds FROM t_learning_progress WHERE user_id = ? AND video_id = ?",
                Integer.class, fx.studentId(), fx.videoId())).isEqualTo(60);
        assertThat(jdbc.queryForObject(
                "SELECT completed FROM t_learning_progress WHERE user_id = ? AND video_id = ?",
                Boolean.class, fx.studentId(), fx.videoId())).isTrue();

        // ---------- 11. 教师端：内容管理入口同样走内核身份 + ctry 课程域 ----------
        String teacherLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("it_teacher")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String teacherToken = JsonPath.read(teacherLogin, "$.token");
        String teacherSeriesBody = mockMvc.perform(get("/api/teacher/series")
                        .header("Authorization", BEARER + teacherToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Number>>read(teacherSeriesBody, "$[*].id"))
                .extracting(Number::longValue)
                .contains(fx.seriesId());

        System.out.printf("""
                [E2E] ctry 全链路通过：login(it_student) -> catalog -> order %s -> pay %s -> mock SUCCESS
                -> order FULFILLED -> entitlement ACTIVE -> learning space -> playUrl(%s) -> progress=35
                %n""", orderNo, paymentNo, playUrl);
    }

    private String loginJson(String username) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + USER_PASSWORD + "\"}";
    }
}
