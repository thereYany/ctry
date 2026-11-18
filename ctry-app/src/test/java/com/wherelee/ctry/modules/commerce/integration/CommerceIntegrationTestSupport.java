package com.wherelee.ctry.modules.commerce.integration;

import com.wherelee.ctry.modules.commerce.entitlement.EntitlementRepository;
import com.wherelee.ctry.modules.commerce.order.OrderRepository;
import com.wherelee.ctry.contracts.commerce.OrderDtos;
import com.wherelee.ctry.modules.commerce.order.OrderFulfillmentService;
import com.wherelee.base.modules.payment.PaymentAttemptRecord;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.payment.PaymentNo;
import com.wherelee.base.modules.payment.PaymentRepository;
import com.wherelee.ctry.modules.commerce.order.OrderPaymentService;
import com.wherelee.ctry.modules.commerce.order.OrderRefundService;
import com.wherelee.ctry.modules.commerce.order.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "IT_DB_URL", matches = ".+")
public abstract class CommerceIntegrationTestSupport {

    private static final AtomicInteger REQUEST_SEQ = new AtomicInteger();

    /** 集成测试账号统一口令：用于走真实 /api/auth/login，而不是绕过登录直接签发 token。 */
    protected static final String USER_PASSWORD = "Integration1Pass";

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected OrderService orderService;
    @Autowired protected OrderPaymentService paymentService;
    @Autowired protected OrderRefundService refundService;
    @Autowired protected OrderFulfillmentService fulfillmentService;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected PaymentRepository paymentRepository;
    @Autowired protected EntitlementRepository entitlementRepository;

    protected Fixture fx;

    @BeforeEach
    void resetAndSeed() {
        truncateAll();
        fx = seedFixture();
    }

    protected String nextRequestId() {
        return "it-" + REQUEST_SEQ.incrementAndGet();
    }

    protected OrderDtos.OrderView createOrder(String clientRequestId) {
        return orderService.create(fx.studentId, new OrderDtos.CreateOrderRequest(fx.seriesId, clientRequestId));
    }

    protected void pay(String orderNo) {
        paymentService.pay(fx.studentId, orderNo);
    }

    protected PaymentNotification successNotification(String orderNo) {
        var order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        PaymentAttemptRecord attempt = paymentRepository
                .findLatestAttemptByPaymentNo(PaymentNo.of(orderNo)).orElseThrow();
        return new PaymentNotification(
                "MOCK",
                "IT:" + orderNo + ":PAID",
                orderNo,
                "PAYMENT_SUCCESS",
                order.priceCents(),
                attempt.providerTradeNo(),
                null
        );
    }

    protected void completePayment(String orderNo) {
        pay(orderNo);
        paymentService.acceptVerified(successNotification(orderNo));
    }

    protected int count(String table, String where, Object... args) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + (where == null ? "" : " WHERE " + where),
                Integer.class, args);
        return value == null ? 0 : value;
    }

    protected <T> List<CallResult<T>> runConcurrently(int threads, Callable<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CallResult<T>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!ready.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发线程未在时限内就绪");
                    }
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return new CallResult<>(action.call(), null);
                    } catch (Throwable error) {
                        return new CallResult<>(null, error);
                    }
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发线程未在时限内就绪");
            }
            start.countDown();
            List<CallResult<T>> results = new ArrayList<>();
            for (Future<CallResult<T>> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void truncateAll() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : List.of(
                "t_playback_log", "t_admin_audit_log", "t_alert_log",
                "t_recon_diff", "t_recon_task", "t_channel_bill", "t_payment_outbox", "t_payment_order",
                "t_refund", "t_payment_event", "t_payment_attempt", "t_order",
                "t_student_series", "t_learning_progress",
                "t_series_video", "t_series_chapter", "t_series",
                "t_user_invitation", "t_user_role", "t_user")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    private Fixture seedFixture() {
        Long teacherId = insertUser("it_teacher", "TEACHER", "集成测试老师");
        Long studentId = insertUser("it_student", "STUDENT", "集成测试学生");
        Long adminId = insertUser("it_admin", "ADMIN", "集成测试管理员");
        Long seriesId = insertSeries(teacherId, "IT 系列", 199L);
        Long chapterId = insertChapter(seriesId, "IT 第一章");
        Long videoId = insertVideo(chapterId);
        return new Fixture(teacherId, studentId, adminId, seriesId, chapterId, videoId, 199L);
    }

    private Long insertUser(String username, String role, String nickname) {
        jdbc.update("""
                INSERT INTO t_user
                    (username, password_hash, nickname, role, status, must_change_password, token_version)
                VALUES (?, ?, ?, ?, 'ACTIVE', FALSE, 0)
                """, username, passwordEncoder.encode(USER_PASSWORD), nickname, role);
        Long userId = jdbc.queryForObject("SELECT id FROM t_user WHERE username = ?", Long.class, username);
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM t_role WHERE tenant_id = 0 AND code = ?", Long.class, role);
        jdbc.update("INSERT IGNORE INTO t_user_role (tenant_id, user_id, role_id) VALUES (0, ?, ?)",
                userId, roleId);
        return userId;
    }

    private Long insertSeries(Long teacherId, String title, long priceCents) {
        jdbc.update("""
                INSERT INTO t_series
                    (teacher_id, title, summary, sort, status, published_at,
                     sale_status, price_cents, currency, sale_updated_at)
                VALUES (?, ?, '集成测试系列', 1, 'PUBLISHED', CURRENT_TIMESTAMP(3),
                        'ON_SALE', ?, 'CNY', CURRENT_TIMESTAMP(3))
                """, teacherId, title, priceCents);
        return jdbc.queryForObject("SELECT id FROM t_series WHERE title = ?", Long.class, title);
    }

    private Long insertChapter(Long seriesId, String title) {
        jdbc.update("INSERT INTO t_series_chapter (series_id, title, sort) VALUES (?, ?, 1)", seriesId, title);
        return jdbc.queryForObject(
                "SELECT id FROM t_series_chapter WHERE series_id = ? AND title = ?",
                Long.class, seriesId, title);
    }

    private Long insertVideo(Long chapterId) {
        jdbc.update("""
                INSERT INTO t_series_video
                    (chapter_id, title, sort, video_key, media_status, duration_seconds)
                VALUES (?, 'IT 视频', 1, 'it/video.mp4', 'READY', 60)
                """, chapterId);
        return jdbc.queryForObject(
                "SELECT id FROM t_series_video WHERE chapter_id = ? AND title = 'IT 视频'",
                Long.class, chapterId);
    }

    protected record Fixture(Long teacherId, Long studentId, Long adminId,
                             Long seriesId, Long chapterId, Long videoId, long priceCents) {
    }

    protected record CallResult<T>(T value, Throwable error) {
        public boolean success() {
            return error == null;
        }
    }
}
