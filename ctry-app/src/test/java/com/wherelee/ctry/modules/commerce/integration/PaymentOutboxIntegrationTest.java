package com.wherelee.ctry.modules.commerce.integration;

import com.wherelee.edu.modules.payment.api.PaymentEventTypes;
import com.wherelee.edu.modules.payment.api.PaymentSettledEvent;
import com.wherelee.edu.modules.payment.outbox.PaymentOutboxDispatcher;
import com.wherelee.edu.modules.payment.outbox.PaymentOutboxRepository;
import com.wherelee.edu.modules.payment.PaymentNo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 证据测试：支付确认 → 事务性发件箱 → 投递器 → 订单侧消费。
 *
 * <p>验证三件事：事件与支付状态同一事务落库、投递成功、重复投递幂等。</p>
 */
class PaymentOutboxIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private PaymentOutboxRepository outboxRepository;
    @Autowired
    private PaymentOutboxDispatcher dispatcher;

    @Test
    @DisplayName("支付确认在同事务写发件箱，投递后事件被消费")
    void paymentWritesOutboxAndDispatches() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        completePayment(orderNo);

        // 确认服务写箱后，模拟支付路径会同步触发一轮投递（生产由定时投递器负责）
        assertThat(outboxStatus(PaymentNo.of(orderNo))).isEqualTo("SENT");
        dispatcher.dispatchOnce();
        assertThat(outboxStatus(PaymentNo.of(orderNo))).isEqualTo("SENT");
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().status().name())
                .isEqualTo("FULFILLED");
    }

    @Test
    @DisplayName("重复事件不重复发货：订单已履约则直接跳过")
    void duplicateEventIsIdempotent() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        completePayment(orderNo);
        dispatcher.dispatchOnce();
        Integer entitlementBefore = count("t_student_series", "user_id = ? AND series_id = ? AND status = 'ACTIVE'",
                fx.studentId(), fx.seriesId());
        assertThat(entitlementBefore).isEqualTo(1);

        // 模拟 at-least-once 重复投递
        outboxRepository.append(new PaymentSettledEvent(
                PaymentEventTypes.PAYMENT_SUCCEEDED,
                PaymentNo.of(orderNo), "COURSE_ORDER", orderNo,
                fx.priceCents(), "CNY", "MOCK", "MOCK-" + orderNo + "-1", LocalDateTime.now()));
        dispatcher.dispatchOnce();

        assertThat(count("t_student_series", "user_id = ? AND series_id = ? AND status = 'ACTIVE'",
                fx.studentId(), fx.seriesId())).isEqualTo(1);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().status().name())
                .isEqualTo("FULFILLED");
    }

    @Test
    @DisplayName("消费失败按退避重试，超过上限进入死信")
    void failingEventIsRetriedThenDeadLettered() {
        outboxRepository.append(new PaymentSettledEvent(
                PaymentEventTypes.PAYMENT_SUCCEEDED,
                "P-UNKNOWN", "COURSE_ORDER", "NO-SUCH-ORDER",
                100L, "CNY", "MOCK", "MOCK-X", LocalDateTime.now()));

        dispatcher.dispatchOnce();
        String afterFirst = outboxStatus("P-UNKNOWN");
        assertThat(afterFirst).isEqualTo("PENDING");
        int attempts = jdbc.queryForObject(
                "SELECT attempts FROM t_payment_outbox WHERE aggregate_no = 'P-UNKNOWN' AND tenant_id = 0",
                Integer.class);
        assertThat(attempts).isEqualTo(1);

        for (int i = 0; i < 6; i++) {
            jdbc.update("UPDATE t_payment_outbox SET next_at = TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP(3)) "
                    + "WHERE aggregate_no = 'P-UNKNOWN' AND tenant_id = 0");
            dispatcher.dispatchOnce();
        }
        assertThat(outboxStatus("P-UNKNOWN")).isEqualTo("DEAD");
    }

    private String outboxStatus(String paymentNo) {
        return jdbc.queryForObject(
                "SELECT status FROM t_payment_outbox WHERE aggregate_no = ? AND tenant_id = 0 ORDER BY id DESC LIMIT 1",
                String.class, paymentNo);
    }
}
