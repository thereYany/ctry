package com.wherelee.ctry.modules.commerce.order;

import com.wherelee.edu.modules.payment.*;

import com.wherelee.edu.modules.platform.common.BizException;
import com.wherelee.edu.modules.platform.tracing.TraceSpans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentServiceTest {

    @Mock private OrderService orderService;
    @Mock private OrderRepository orderRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private com.wherelee.ctry.modules.commerce.entitlement.EntitlementRepository entitlementRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private com.wherelee.edu.modules.payment.PaymentFacade paymentFacade;
    @Mock private PaymentConfirmationService confirmationService;
    @Mock private OrderFulfillmentService fulfillmentService;
    @Mock private MockSignatureService signatureService;
    @Mock private PaymentMetrics metrics;
    @Mock private PaymentQueryService paymentQueryService;
    @Mock private com.wherelee.edu.modules.payment.outbox.PaymentOutboxDispatcher outboxDispatcher;

    private OrderPaymentService service;

    @BeforeEach
    void setUp() {
        service = new OrderPaymentService(
                orderService, orderRepository, paymentRepository, entitlementRepository,
                paymentGateway, confirmationService, fulfillmentService, signatureService,
                new PaymentProperties("alipay", false, null, 15, 60, 50, 60000, 30000, 5, 30),
                metrics, TraceSpans.noop(), paymentOrderRepository, paymentFacade,
                paymentQueryService, outboxDispatcher);
    }

    @Test
    void reconcileOrderDelegatesToPaymentDomainQuery() {
        OrderRecord order = order();
        when(paymentQueryService.queryByBizNo("ORD-1")).thenReturn(true);
        when(orderService.require("ORD-1")).thenReturn(order);

        OrderRecord result = service.reconcileOrder(order);

        verify(paymentQueryService).queryByBizNo("ORD-1");
        verify(outboxDispatcher).dispatchOnce();
        org.assertj.core.api.Assertions.assertThat(result).isSameAs(order);
    }

    @Test
    void payingOrderReusesGenericPaymentInitiation() {
        OrderRecord order = order();
        when(orderService.requireOwned(1L, "ORD-1")).thenReturn(order);
        when(paymentFacade.initiate(any())).thenReturn(
                new com.wherelee.edu.modules.contracts.payment.PaymentOperations.InitiateResult(
                        "PORD-1", "PAYING", "ALIPAY", "/mock-pay/ORD-1"));
        when(orderRepository.markPaying(eq("ORD-1"), eq("ALIPAY"))).thenReturn(1);

        var response = service.pay(1L, "ORD-1");

        org.assertj.core.api.Assertions.assertThat(response.cashierUrl()).isEqualTo("/mock-pay/ORD-1");
        verify(paymentFacade).initiate(any());
    }
    private OrderRecord order() {
        LocalDateTime now = LocalDateTime.now();
        return new OrderRecord(1L, "ORD-1", 1L, 1L, "物理必修一", 1L, "CNY",
                OrderStatus.PAYING, "ALIPAY", null, "req", "1:1",
                now.minusMinutes(1), null, null, null, 0, null, now.minusMinutes(20), now.minusMinutes(20));
    }

    private PaymentAttemptRecord attempt() {
        LocalDateTime now = LocalDateTime.now();
        return new PaymentAttemptRecord(1L, 1L, 1, "ALIPAY", "PROCESSING", "ORD-1_1",
                "CREATED", 1L, "digest", null, null, null, now, null);
    }
}
