package com.wherelee.ctry.modules.commerce.integration;

import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.payment.PaymentRepository;
import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4.5 证据：与业务无关的支付契约（第二垂直域复用底座）。
 */
class PaymentOperationsIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private PaymentOperations paymentOperations;
    @Autowired
    private PaymentConfirmationService confirmationService;
    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @DisplayName("任意 bizType/bizNo 可发起支付，支付域自动建单建尝试")
    void genericInitiationCreatesPaymentOrderAndAttempt() {
        PaymentOperations.InitiateResult result = paymentOperations.initiate(
                new PaymentOperations.InitiateCommand(null, "RESOURCE_PACK", "RP-1",
                        9900L, "CNY", "资料包-数据结构", LocalDateTime.now().plusMinutes(15)));

        assertThat(result.status()).isEqualTo("PAYING");
        assertThat(result.paymentNo()).startsWith("P");
        assertThat(result.cashierUrl()).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_payment_order WHERE payment_no = ? AND tenant_id = 0",
                String.class, result.paymentNo())).isEqualTo("PAYING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_payment_attempt WHERE payment_no = ?", Integer.class,
                result.paymentNo())).isEqualTo(1);
        // 幂等：同一 biz 重复发起不会新建支付单/尝试
        PaymentOperations.InitiateResult second = paymentOperations.initiate(
                new PaymentOperations.InitiateCommand(null, "RESOURCE_PACK", "RP-1",
                        9900L, "CNY", "资料包-数据结构", LocalDateTime.now().plusMinutes(15)));
        assertThat(second.paymentNo()).isEqualTo(result.paymentNo());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_payment_attempt WHERE payment_no = ?", Integer.class,
                result.paymentNo())).isEqualTo(1);
    }

    @Test
    @DisplayName("通用支付的成功事件写入发件箱，业务侧可据此履约")
    void genericPaymentSettlesAndEmitsBusinessEvent() {
        PaymentOperations.InitiateResult result = paymentOperations.initiate(
                new PaymentOperations.InitiateCommand(null, "RESOURCE_PACK", "RP-2",
                        9900L, "CNY", "资料包-算法", LocalDateTime.now().plusMinutes(15)));
        String tradeNo = paymentRepository.findLatestAttemptByPaymentNo(result.paymentNo())
                .orElseThrow().providerTradeNo();

        confirmationService.accept(new PaymentNotification(
                "MOCK", "IT:RP-2:PAID", "RP-2", "PAYMENT_SUCCESS", 9900L, tradeNo, null));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_payment_order WHERE payment_no = ?", String.class,
                result.paymentNo())).isEqualTo("PAID");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ? AND payload LIKE '%RESOURCE_PACK%'",
                Integer.class, result.paymentNo())).isEqualTo(1);
        assertThat(paymentOperations.findByBiz("RESOURCE_PACK", "RP-2").orElseThrow().status())
                .isEqualTo("PAID");
    }
}
