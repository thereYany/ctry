package com.wherelee.ctry.modules.commerce.integration;

import com.wherelee.edu.modules.contracts.payment.AdminReconciliationOperations;
import com.wherelee.edu.modules.contracts.payment.AdminReconciliationOperations.DiffView;
import com.wherelee.edu.modules.contracts.payment.AdminReconciliationOperations.ReconSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A 期证据测试：对账链路（渠道账单 vs 我方支付单）。
 *
 * <p>覆盖：一致零差异、四类差异识别、重跑幂等、人工处理闭环。</p>
 */
class ReconciliationIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private AdminReconciliationOperations reconciliation;

    @Test
    @DisplayName("账单与支付单一致时对账零差异，且重跑结果稳定")
    void matchedBillProducesZeroDiffAndIsIdempotent() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        pay(orderNo);
        paymentService.acceptVerified(successNotification(orderNo));

        ReconSummary first = reconciliation.run("MOCK", LocalDate.now());
        assertThat(first.totalCount()).isEqualTo(1);
        assertThat(first.matchedCount()).isEqualTo(1);
        assertThat(first.diffCount()).isZero();
        assertThat(first.diffByType()).isEmpty();

        // 重跑：任务号不变、差异集不重复写入
        ReconSummary second = reconciliation.run("MOCK", LocalDate.now());
        assertThat(second.taskNo()).isEqualTo(first.taskNo());
        assertThat(second.matchedCount()).isEqualTo(1);
        assertThat(second.diffCount()).isZero();
        assertThat(reconciliation.pageDiffs("OPEN", 1, 10).total()).isZero();
    }

    @Test
    @DisplayName("我方有单渠道无单：报 LOCAL_ONLY")
    void localOnlyIsDetected() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        pay(orderNo);
        paymentService.acceptVerified(successNotification(orderNo));
        // 抹掉渠道侧的支付尝试：渠道账单里没有这笔，我方却已支付
        jdbc.update("DELETE FROM t_payment_attempt WHERE tenant_id = 0 "
                + "AND payment_no = CONCAT('P', ?)", orderNo);

        ReconSummary summary = reconciliation.run("MOCK", LocalDate.now());
        assertThat(summary.diffCount()).isEqualTo(1);
        assertThat(summary.diffByType()).containsExactly(entry("LOCAL_ONLY", 1));

        DiffView diff = reconciliation.pageDiffs("OPEN", 1, 10).records().get(0);
        assertThat(diff.diffType()).isEqualTo("LOCAL_ONLY");
        assertThat(diff.paymentNo()).isEqualTo("P" + orderNo);
    }

    @Test
    @DisplayName("渠道有单我方无单：报 PROVIDER_ONLY")
    void providerOnlyIsDetected() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        pay(orderNo);
        paymentService.acceptVerified(successNotification(orderNo));
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM t_order WHERE order_no = ?", Long.class, orderNo);
        jdbc.update("""
                INSERT INTO t_payment_attempt
                    (order_id, payment_no, attempt_no, channel, status, provider_trade_no,
                     provider_status, provider_amount_cents)
                VALUES (?, ?, 99, 'MOCK', 'SUCCESS', 'MOCK-STRAY-1', 'PAID', 100)
                """, orderId, "P" + orderNo);

        ReconSummary summary = reconciliation.run("MOCK", LocalDate.now());
        assertThat(summary.diffByType()).containsEntry("PROVIDER_ONLY", 1);
    }

    @Test
    @DisplayName("金额不一致与状态不一致分别归类")
    void amountAndStatusMismatchAreClassified() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        pay(orderNo);
        paymentService.acceptVerified(successNotification(orderNo));
        jdbc.update("UPDATE t_payment_attempt SET provider_amount_cents = provider_amount_cents + 100 "
                + "WHERE tenant_id = 0 AND payment_no = CONCAT('P', ?)", orderNo);

        ReconSummary amountSummary = reconciliation.run("MOCK", LocalDate.now());
        assertThat(amountSummary.diffByType()).containsEntry("AMOUNT", 1);

        // 修回金额，改状态：我方 PAID、渠道 FAILED
        jdbc.update("UPDATE t_payment_attempt SET provider_amount_cents = provider_amount_cents - 100, "
                + "status = 'FAILED' WHERE tenant_id = 0 "
                + "AND payment_no = CONCAT('P', ?)", orderNo);
        ReconSummary statusSummary = reconciliation.run("MOCK", LocalDate.now());
        assertThat(statusSummary.diffByType()).containsEntry("STATUS", 1);
    }

    @Test
    @DisplayName("差异人工处理闭环：OPEN → HANDLED，重复处理被拒绝")
    void diffCanBeHandledOnce() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        pay(orderNo);
        paymentService.acceptVerified(successNotification(orderNo));
        jdbc.update("DELETE FROM t_payment_attempt WHERE tenant_id = 0 "
                + "AND payment_no = CONCAT('P', ?)", orderNo);
        reconciliation.run("MOCK", LocalDate.now());

        assertThat(reconciliation.pageDiffs("OPEN", 1, 10).total()).isEqualTo(1);
        Long diffId = reconciliation.pageDiffs("OPEN", 1, 10).records().get(0).id();

        reconciliation.handle(diffId, fx.adminId(), "HANDLED");
        assertThat(reconciliation.pageDiffs("OPEN", 1, 10).total()).isZero();
        assertThat(reconciliation.pageDiffs("HANDLED", 1, 10).total()).isEqualTo(1);

        assertThatThrownBy(() -> reconciliation.handle(diffId, fx.adminId(), "HANDLED"))
                .hasMessageContaining("已处理");
        assertThatThrownBy(() -> reconciliation.handle(diffId, fx.adminId(), "WHATEVER"))
                .hasMessageContaining("HANDLED");
    }
}
