package com.wherelee.ctry.modules.commerce.integration;

import com.wherelee.ctry.contracts.commerce.AdminOrderOperations;
import com.wherelee.base.modules.payment.PaymentNo;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 证据测试：支付反转后四条事实成立。
 *
 * <ol>
 *   <li>发起支付：订单只存 payment_no，支付单承载状态，尝试按 payment_no 关联；</li>
 *   <li>支付确认：支付单 PAID，订单由业务侧履约到 FULFILLED；</li>
 *   <li>退款：支付单与订单同步到 REFUNDED；</li>
 *   <li>发件箱与指标可用（积压/死信/已投递）。</li>
 * </ol>
 */
class PaymentReversalIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private AdminOrderOperations adminOrderOperations;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("发起支付：订单只留 payment_no，支付单承载状态，尝试按 payment_no 关联")
    void initiationWritesPaymentOrderAndLinksAttempt() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        pay(orderNo);

        String paymentNo = PaymentNo.of(orderNo);
        assertThat(jdbc.queryForObject(
                "SELECT payment_no FROM t_order WHERE order_no = ? AND tenant_id = 0", String.class, orderNo))
                .isEqualTo(paymentNo);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_payment_order WHERE payment_no = ? AND tenant_id = 0",
                String.class, paymentNo)).isEqualTo("PAYING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_payment_attempt WHERE payment_no = ? AND tenant_id = 0",
                Integer.class, paymentNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_payment_attempt WHERE payment_no = ? AND order_id IS NULL AND tenant_id = 0",
                Integer.class, paymentNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("支付确认：支付单 PAID、订单 FULFILLED，运营快照能按支付单查到尝试与事件")
    void confirmationSettlesPaymentAndFulfillsOrder() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        completePayment(orderNo);

        String paymentNo = PaymentNo.of(orderNo);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_payment_order WHERE payment_no = ? AND tenant_id = 0",
                String.class, paymentNo)).isEqualTo("PAID");
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().status().name())
                .isEqualTo("FULFILLED");

        AdminOrderOperations.Snapshot snapshot = adminOrderOperations.snapshot(orderNo);
        assertThat(snapshot.attempts()).isNotEmpty();
        assertThat(snapshot.events()).isNotEmpty();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ? AND tenant_id = 0",
                Integer.class, paymentNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("退款：支付单与订单同步到 REFUNDED")
    void refundAdvancesPaymentOrderState() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        completePayment(orderNo);
        refundService.refund(orderNo, fx.adminId(), "P2 反转验收退款", false);

        String paymentNo = PaymentNo.of(orderNo);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_payment_order WHERE payment_no = ? AND tenant_id = 0",
                String.class, paymentNo)).isEqualTo("REFUNDED");
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().status().name())
                .isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("主动查单：过期未交易支付单关闭，业务订单经 PAYMENT_EXPIRED 事件关闭")
    void expiredPaymentClosesOrderThroughEvent() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        pay(orderNo);
        String paymentNo = PaymentNo.of(orderNo);
        jdbc.update("""
                UPDATE t_payment_order
                SET expire_at = TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP(3)),
                    updated_at = TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP(3))
                WHERE payment_no = ? AND tenant_id = 0
                """, paymentNo);

        // 支付单与订单同时到期：支付域判过期，订单侧才允许关闭支付中的订单
        jdbc.update("UPDATE t_order SET expire_at = TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP(3)) "
                + "WHERE order_no = ? AND tenant_id = 0", orderNo);

        paymentService.reconcile();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_payment_order WHERE payment_no = ? AND tenant_id = 0",
                String.class, paymentNo)).isEqualTo("CLOSED");
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().status())
                .isEqualTo(com.wherelee.ctry.modules.commerce.order.OrderStatus.CLOSED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ? AND event_type = 'PAYMENT_EXPIRED'",
                Integer.class, paymentNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("发件箱指标已注册：积压、死信、已投递")
    void outboxMetricsAreRegistered() {
        assertThat(meterRegistry.find("payment.outbox.in_flight").gauge()).isNotNull();
        assertThat(meterRegistry.find("payment.outbox.dead").gauge()).isNotNull();
        assertThat(meterRegistry.find("payment.outbox.sent").counter()).isNotNull();
        assertThat(meterRegistry.find("payment.outbox.failed").counter()).isNotNull();
        assertThat(meterRegistry.find("payment.outbox.dead_lettered").counter()).isNotNull();
    }
}
