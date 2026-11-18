package com.wherelee.ctry.modules.commerce.order;

import com.wherelee.ctry.contracts.commerce.OrderDtos;
import com.wherelee.edu.modules.payment.MockSignatureService;
import com.wherelee.edu.modules.payment.PaymentAttemptRecord;
import com.wherelee.edu.modules.payment.PaymentConfirmationService;
import com.wherelee.ctry.contracts.payment.PaymentDtos;
import com.wherelee.edu.modules.payment.PaymentGateway;
import com.wherelee.edu.modules.payment.PaymentMetrics;
import com.wherelee.edu.modules.payment.PaymentNo;
import com.wherelee.edu.modules.payment.PaymentNotification;
import com.wherelee.edu.modules.payment.PaymentOrderRepository;
import com.wherelee.edu.modules.payment.PaymentOrderView;
import com.wherelee.edu.modules.payment.PaymentProperties;
import com.wherelee.edu.modules.payment.PaymentQueryService;
import com.wherelee.edu.modules.payment.outbox.PaymentOutboxDispatcher;
import com.wherelee.edu.modules.payment.PaymentFacade;
import com.wherelee.edu.modules.contracts.payment.PaymentOperations;
import com.wherelee.edu.modules.payment.PaymentRepository;
import com.wherelee.edu.modules.platform.tenant.TenantContext;
import com.wherelee.edu.modules.platform.common.BizException;
import com.wherelee.ctry.modules.commerce.entitlement.EntitlementRepository;
import com.wherelee.edu.modules.platform.tracing.TraceSpans;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OrderPaymentService {

    private static final List<String> MOCK_SCENARIOS = List.of(
            "SUCCESS",
            "FAILED",
            "LOST_CALLBACK",
            "DELAYED",
            "DUPLICATE",
            "AMOUNT_MISMATCH",
            "FULFILLMENT_FAIL_ONCE"
    );

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final EntitlementRepository entitlementRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentConfirmationService confirmationService;
    private final OrderFulfillmentService fulfillmentService;
    private final MockSignatureService signatureService;
    private final PaymentProperties properties;
    private final PaymentMetrics metrics;
    private final TraceSpans traceSpans;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentOperations paymentOperations;
    private final PaymentQueryService paymentQueryService;
    private final PaymentOutboxDispatcher outboxDispatcher;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mock-payment-delay");
        thread.setDaemon(true);
        return thread;
    });

    public OrderPaymentService(OrderService orderService,
                          OrderRepository orderRepository,
                          PaymentRepository paymentRepository,
                          EntitlementRepository entitlementRepository,
                          PaymentGateway paymentGateway,
                          PaymentConfirmationService confirmationService,
                          OrderFulfillmentService fulfillmentService,
                          MockSignatureService signatureService,
                          PaymentProperties properties,
                          PaymentMetrics metrics,
                          TraceSpans traceSpans,
                          PaymentOrderRepository paymentOrderRepository,
                          PaymentFacade paymentFacade,
                          PaymentQueryService paymentQueryService,
                          PaymentOutboxDispatcher outboxDispatcher) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.entitlementRepository = entitlementRepository;
        this.paymentGateway = paymentGateway;
        this.confirmationService = confirmationService;
        this.fulfillmentService = fulfillmentService;
        this.signatureService = signatureService;
        this.properties = properties;
        this.metrics = metrics;
        this.traceSpans = traceSpans;
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentOperations = paymentFacade;
        this.paymentQueryService = paymentQueryService;
        this.outboxDispatcher = outboxDispatcher;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDtos.PayResponse pay(Long userId, String orderNo) {
        return payInternal(userId, orderNo);
    }

    private OrderDtos.PayResponse payInternal(Long userId, String orderNo) {
        OrderRecord order = orderService.requireOwned(userId, orderNo);
        if (entitlementRepository.hasActive(userId, order.seriesId())) {
            throw new BizException(HttpStatus.CONFLICT, "你已经拥有这个课程");
        }
        if (order.status() == OrderStatus.FULFILLED) {
            throw new BizException(HttpStatus.CONFLICT, "课程权益已经发放");
        }
        if (order.status() != OrderStatus.PAYING && order.expired()) {
            orderRepository.close(order.orderNo(), false);
            throw BizException.badRequest("订单已过期，请重新下单");
        }

        PaymentOperations.InitiateResult payment = paymentOperations.initiate(
                new PaymentOperations.InitiateCommand(
                        PaymentNo.of(orderNo), "COURSE_ORDER", orderNo,
                        order.priceCents(), order.currency(), order.seriesTitle(), order.expireAt()));
        orderRepository.setPaymentNo(orderNo, payment.paymentNo());
        int changed = orderRepository.markPaying(order.orderNo(), payment.channel());
        if (changed == 0) {
            OrderRecord current = orderService.require(orderNo);
            if (current.status() == OrderStatus.PAYING) {
                return new OrderDtos.PayResponse(current.orderNo(), current.status().name(),
                        payment.channel(), payment.cashierUrl());
            }
            throw new BizException(HttpStatus.CONFLICT, "当前订单状态不能发起支付");
        }
        return new OrderDtos.PayResponse(order.orderNo(), OrderStatus.PAYING.name(),
                payment.channel(), payment.cashierUrl());
    }

    public PaymentDtos.MockCashierView cashier(Long userId, String orderNo) {
        requireMockEnabled();
        OrderRecord order = orderService.requireOwned(userId, orderNo);
        PaymentAttemptRecord attempt = paymentRepository.findLatestAttemptByPaymentNo(PaymentNo.of(order.orderNo()))
                .orElseThrow(() -> BizException.badRequest("请先发起支付"));
        if (order.status() != OrderStatus.PAYING || !"PROCESSING".equals(attempt.status())) {
            throw BizException.badRequest("当前订单不在可模拟支付状态");
        }
        return new PaymentDtos.MockCashierView(
                order.orderNo(), order.seriesTitle(), order.priceCents(),
                order.currency(), order.status().name(), MOCK_SCENARIOS
        );
    }

    public PaymentDtos.MockEventResponse simulate(Long userId, String orderNo, String scenario) {
        requireMockEnabled();
        String normalized = scenario == null ? "" : scenario.trim().toUpperCase(Locale.ROOT);
        if (!MOCK_SCENARIOS.contains(normalized)) {
            throw BizException.badRequest("不支持的 Mock 支付场景");
        }
        OrderRecord order = orderService.requireOwned(userId, orderNo);
        if (order.status() != OrderStatus.PAYING) {
            throw BizException.badRequest("订单当前不是支付中状态");
        }
        PaymentAttemptRecord attempt = paymentRepository.findLatestAttemptByPaymentNo(PaymentNo.of(order.orderNo()))
                .orElseThrow(() -> BizException.badRequest("支付尝试不存在"));
        if (!"PROCESSING".equals(attempt.status())) {
            throw BizException.badRequest("支付尝试已经结束");
        }

        switch (normalized) {
            case "SUCCESS" -> {
                markProvider(attempt, "PAID", order.priceCents(), normalized);
                finishNotification(successNotification(order, attempt), "模拟支付成功");
            }
            case "FAILED" -> {
                markProvider(attempt, "FAILED", order.priceCents(), normalized);
                finishNotification(failureNotification(order, attempt), "模拟支付失败");
            }
            case "LOST_CALLBACK" -> markProvider(attempt, "PAID", order.priceCents(), normalized);
            case "DELAYED" -> {
                markProvider(attempt, "PAID", order.priceCents(), normalized);
                PaymentNotification notification = successNotification(order, attempt);
                scheduler.schedule(() -> safelyAccept(notification), 5, TimeUnit.SECONDS);
            }
            case "DUPLICATE" -> {
                markProvider(attempt, "PAID", order.priceCents(), normalized);
                PaymentNotification notification = successNotification(order, attempt);
                finishNotification(notification, "第一次支付通知");
                finishNotification(notification, "重复支付通知");
            }
            case "AMOUNT_MISMATCH" -> {
                long wrongAmount = order.priceCents() + 100;
                markProvider(attempt, "PAID", wrongAmount, normalized);
                PaymentNotification notification = new PaymentNotification(
                        paymentGateway.channel(),
                        "MOCK:" + order.orderNo() + ":" + attempt.attemptNo() + ":MISMATCH",
                        order.orderNo(), "PAYMENT_SUCCESS", wrongAmount,
                        attempt.providerTradeNo(), null
                );
                accept(sign(notification));
            }
            case "FULFILLMENT_FAIL_ONCE" -> {
                markProvider(attempt, "PAID", order.priceCents(), normalized);
                accept(successNotification(order, attempt));
            }
            default -> throw BizException.badRequest("不支持的 Mock 支付场景");
        }

        OrderRecord current = orderService.require(orderNo);
        String message = switch (normalized) {
            case "LOST_CALLBACK" -> "平台已扣款但回调丢失，等待主动查单补偿";
            case "DELAYED" -> "支付成功事件将在 5 秒后送达";
            case "DUPLICATE" -> "重复通知已处理，权益不会重复生成";
            case "AMOUNT_MISMATCH" -> "支付金额不一致，订单未发货并已记录异常";
            case "FULFILLMENT_FAIL_ONCE" -> "支付已确认，首次履约失败，等待自动补偿";
            default -> "模拟支付场景已执行";
        };
        return new PaymentDtos.MockEventResponse(orderNo, current.status().name(), message);
    }

    public PaymentConfirmationService.ConfirmationResult accept(PaymentNotification notification) {
        requireMockEnabled();
        if (!signatureService.verify(notification)) {
            metrics.callbackInvalid();
            throw new BizException(HttpStatus.UNAUTHORIZED, "Mock 支付通知签名无效");
        }
        return acceptVerified(notification);
    }

    /**
     * 已完成渠道验签的通知统一走这里：幂等确认 + 履约。
     * Mock 由 MockSignatureService 验签后调用，支付宝由 AlipayNotifyService 验签后调用，
     * 主动查单补偿是服务端内部发起，同样走这里。
     */
    public PaymentConfirmationService.ConfirmationResult acceptVerified(PaymentNotification notification) {
        return traceSpans.trace("payment.confirm", TraceSpans.tags(
                "order.no", notification.orderNo(),
                "payment.provider", notification.provider(),
                "payment.event.type", notification.eventType()
        ), () -> acceptVerifiedInternal(notification));
    }

    private PaymentConfirmationService.ConfirmationResult acceptVerifiedInternal(PaymentNotification notification) {
        PaymentConfirmationService.ConfirmationResult result = confirmationService.accept(notification);
        // 事件驱动：本地同步触发一轮投递，mock/测试路径仍可立即观察到订单状态
        outboxDispatcher.dispatchOnce();
        String orderStatus = orderRepository.findByOrderNo(notification.orderNo())
                .map(order -> order.status().name()).orElse("UNKNOWN");
        if (result.success()) {
            log.info("payment_confirm_result orderNo={} eventKey={} provider={} tradeNo={} amountCents={} success=true duplicate={} orderStatus={}",
                    notification.orderNo(), notification.eventKey(), notification.provider(),
                    notification.tradeNo(), notification.amountCents(), result.duplicate(), orderStatus);
        } else {
            log.warn("payment_confirm_result orderNo={} eventKey={} provider={} tradeNo={} amountCents={} success=false duplicate={} orderStatus={} message={}",
                    notification.orderNo(), notification.eventKey(), notification.provider(),
                    notification.tradeNo(), notification.amountCents(), result.duplicate(),
                    orderStatus, result.message());
        }
        return result;
    }

    /**
     * 补偿任务：主动查单交给支付域，订单侧只做业务补偿（过期关单、已支付未履约补发）。
     */
    public void reconcile() {
        try {
            paymentQueryService.queryPending();
            outboxDispatcher.dispatchOnce();
        } catch (Exception e) {
            log.warn("支付域主动查单失败", e);
        }
        for (OrderRecord order : orderRepository.findExpired(properties.reconcileBatchSize())) {
            orderRepository.close(order.orderNo(), false);
        }
        for (OrderRecord order : orderRepository.findPaidUnfulfilled(properties.reconcileBatchSize())) {
            try {
                fulfillmentService.fulfill(order.orderNo());
            } catch (Exception e) {
                log.warn("订单补履约失败 orderNo={}", order.orderNo(), e);
            }
        }
    }

    /** 管理员立即查单：委托支付域按业务单号查，然后同步投递事件。 */
    public OrderRecord reconcileOrder(OrderRecord order) {
        if (order.status() == OrderStatus.PAYING) {
            paymentQueryService.queryByBizNo(order.orderNo());
            outboxDispatcher.dispatchOnce();
        }
        return orderService.require(order.orderNo());
    }

    public OrderRecord queryNow(String orderNo) {
        return reconcileOrder(orderService.require(orderNo));
    }

    /** 订单事实 → 支付域视图（唯一允许的跨域数据传递点，后续会由发起命令替代）。 */
    private PaymentOrderView view(OrderRecord order) {
        return new PaymentOrderView(
                PaymentNo.of(order.orderNo()), order.orderNo(), "btry", "COURSE_ORDER",
                order.seriesTitle(), order.priceCents(), order.currency(), order.status().name(),
                order.payChannel(), order.providerTradeNo(), order.expireAt(), order.paidAt());
    }

    private PaymentAttemptRecord requireProcessingAttempt(String paymentNo) {
        PaymentAttemptRecord attempt = paymentRepository.findLatestAttemptByPaymentNo(paymentNo)
                .orElseThrow(() -> new BizException(HttpStatus.CONFLICT, "支付尝试不存在，请重新下单"));
        if (!"PROCESSING".equals(attempt.status())) {
            throw new BizException(HttpStatus.CONFLICT, "支付尝试已结束，请刷新订单状态");
        }
        return attempt;
    }

    private PaymentGateway.PaymentAction initiate(OrderRecord order, PaymentAttemptRecord attempt) {
        PaymentGateway.PaymentAction action = traceSpans.trace("payment.gateway.initiate", TraceSpans.tags(
                "order.no", order.orderNo(),
                "payment.channel", paymentGateway.channel()
        ), () -> paymentGateway.initiate(view(order), attempt));
        log.info("payment_initiate orderNo={} attemptNo={} channel={} amountCents={} providerTradeNo={}",
                order.orderNo(), attempt.attemptNo(), paymentGateway.channel(),
                order.priceCents(), attempt.providerTradeNo());
        return action;
    }
    private void finishNotification(PaymentNotification notification, String message) {
        PaymentConfirmationService.ConfirmationResult result = accept(notification);
        if (!result.success()) {
            throw new BizException(HttpStatus.CONFLICT, result.message());
        }
        log.info("{} orderNo={} status={}", message, notification.orderNo(), result.status());
    }

    private void safelyAccept(PaymentNotification notification) {
        try {
            finishNotification(notification, "延迟支付通知");
        } catch (Exception e) {
            log.warn("延迟 Mock 支付通知处理失败 orderNo={}", notification.orderNo(), e);
        }
    }

    private void markProvider(PaymentAttemptRecord attempt, String status, long amount, String scenario) {
        paymentRepository.updateProviderState(attempt.id(), status, amount, scenario);
    }

    private PaymentNotification successNotification(OrderRecord order, PaymentAttemptRecord attempt) {
        return sign(new PaymentNotification(
                paymentGateway.channel(),
                "MOCK:" + order.orderNo() + ":" + attempt.attemptNo() + ":PAID",
                order.orderNo(),
                "PAYMENT_SUCCESS",
                order.priceCents(),
                attempt.providerTradeNo(),
                null
        ));
    }

    private PaymentNotification failureNotification(OrderRecord order, PaymentAttemptRecord attempt) {
        return sign(new PaymentNotification(
                paymentGateway.channel(),
                "MOCK:" + order.orderNo() + ":" + attempt.attemptNo() + ":FAILED",
                order.orderNo(),
                "PAYMENT_FAILED",
                order.priceCents(),
                attempt.providerTradeNo(),
                null
        ));
    }

    private PaymentNotification sign(PaymentNotification notification) {
        String signature = signatureService.sign(notification);
        return new PaymentNotification(
                notification.provider(), notification.eventKey(), notification.orderNo(),
                notification.eventType(), notification.amountCents(), notification.tradeNo(), signature
        );
    }

    private void requireMockEnabled() {
        if (!properties.mockEnabled() || !"mock".equalsIgnoreCase(properties.provider())) {
            throw new BizException(HttpStatus.NOT_FOUND, "Mock 支付未启用");
        }
    }

    private String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
