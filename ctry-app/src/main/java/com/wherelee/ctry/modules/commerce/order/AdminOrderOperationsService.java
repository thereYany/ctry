package com.wherelee.ctry.modules.commerce.order;

import com.wherelee.ctry.contracts.commerce.AdminOrderOperations;
import com.wherelee.ctry.contracts.commerce.OrderDtos;
import com.wherelee.edu.modules.contracts.payment.PaymentDtos;
import com.wherelee.edu.modules.payment.PaymentRepository;
import com.wherelee.edu.modules.payment.RefundRecord;
import com.wherelee.edu.modules.platform.common.BizException;
import com.wherelee.edu.modules.platform.common.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * commerce 模块内部实现运营端订单门面。运营端不直接接触具体 Repository 和补偿服务。
 */
@Service
public class AdminOrderOperationsService implements AdminOrderOperations {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderPaymentService paymentService;
    private final OrderFulfillmentService fulfillmentService;
    private final OrderRefundService refundService;

    public AdminOrderOperationsService(OrderService orderService,
                                       OrderRepository orderRepository,
                                       PaymentRepository paymentRepository,
                                       OrderPaymentService paymentService,
                                       OrderFulfillmentService fulfillmentService,
                                       OrderRefundService refundService) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.fulfillmentService = fulfillmentService;
        this.refundService = refundService;
    }

    @Override
    public PageResult<OrderRow> page(String keyword, String status, List<Long> userIds,
                                     int page, int size) {
        int safePage = Math.min(Math.max(page, 1), 10_000);
        int safeSize = Math.min(Math.max(size, 1), 50);
        long total = orderRepository.countAdminPage(keyword, status, userIds);
        if (total == 0) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        List<OrderRow> rows = orderRepository.findAdminPage(keyword, status, userIds,
                        (safePage - 1) * safeSize, safeSize).stream()
                .map(AdminOrderOperationsService::row).toList();
        return new PageResult<>(rows, total, safePage, safeSize);
    }

    @Override
    public PageResult<OrderRow> exceptions(int page, int size) {
        int safePage = Math.min(Math.max(page, 1), 10_000);
        int safeSize = Math.min(Math.max(size, 1), 50);
        long total = orderRepository.countAdminExceptions();
        if (total == 0) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        List<OrderRow> rows = orderRepository.findAdminExceptions((safePage - 1) * safeSize, safeSize)
                .stream().map(AdminOrderOperationsService::row).toList();
        return new PageResult<>(rows, total, safePage, safeSize);
    }

    @Override
    public OrderRow find(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).map(AdminOrderOperationsService::row)
                .orElseThrow(() -> BizException.notFound("订单不存在"));
    }

    private static OrderRow row(OrderRecord order) {
        return new OrderRow(order.orderNo(), order.userId(), order.seriesId(), order.seriesTitle(),
                order.priceCents(), order.currency(), order.status().name(), order.payChannel(),
                order.providerTradeNo(), order.expireAt(), order.paidAt(), order.fulfilledAt(),
                order.closedAt(), order.fulfillmentAttempts(), order.lastError(),
                order.createdAt(), order.updatedAt());
    }

    @Override
    public Snapshot snapshot(String orderNo) {
        OrderDtos.OrderDetail detail = orderService.detailForAdmin(orderNo);
        OrderRecord order = orderService.require(orderNo);
        String paymentNo = orderRepository.findPaymentNo(order.orderNo()).orElse(null);
        var attemptRecords = paymentNo == null
                ? paymentRepository.findAttempts(order.id())
                : paymentRepository.findAttemptsByPaymentNo(paymentNo);
        List<PaymentDtos.AttemptView> attempts = attemptRecords.stream()
                .map(attempt -> new PaymentDtos.AttemptView(
                        attempt.attemptNo(), attempt.status(), attempt.providerTradeNo(),
                        attempt.providerStatus(), attempt.mockScenario(),
                        attempt.createdAt(), attempt.finishedAt()))
                .toList();
        var eventRecords = paymentNo == null
                ? paymentRepository.findEvents(order.id())
                : paymentRepository.findEventsByPaymentNo(paymentNo);
        List<PaymentDtos.EventView> events = eventRecords.stream()
                .map(event -> new PaymentDtos.EventView(
                        event.eventKey(), event.eventType(), event.amountCents(),
                        event.processStatus(), event.errorMessage(),
                        event.receivedAt(), event.processedAt()))
                .toList();
        PaymentDtos.RefundView refund = paymentRepository.findRefundByOrder(order.id())
                .map(this::refundView).orElse(null);
        return new Snapshot(detail, attempts, events, refund);
    }

    @Override
    public void queryNow(String orderNo) {
        paymentService.queryNow(orderNo);
    }

    @Override
    public void fulfill(String orderNo) {
        if (!fulfillmentService.fulfill(orderNo)) {
            throw new BizException(HttpStatus.CONFLICT, "订单暂未完成履约，请查看最近错误后重试");
        }
    }

    @Override
    public void close(String orderNo) {
        OrderRecord order = orderService.require(orderNo);
        OrderTransitionPolicy.requireAdminClosable(order.status());
        orderRepository.close(orderNo, true);
    }

    @Override
    public void refund(String orderNo, Long operatorId, String reason, boolean simulateFailure) {
        refundService.refund(orderNo, operatorId, reason, simulateFailure);
    }

    private PaymentDtos.RefundView refundView(RefundRecord refund) {
        return new PaymentDtos.RefundView(
                refund.refundNo(), refund.amountCents(), refund.status(),
                refund.attemptCount(), refund.nextRetryAt(), refund.reason(),
                refund.requestedAt(), refund.succeededAt(), refund.errorMessage());
    }
}