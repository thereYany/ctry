package com.wherelee.ctry.modules.commerce.order;

import com.wherelee.edu.modules.payment.*;
import com.wherelee.edu.modules.payment.PaymentGateway;
import com.wherelee.edu.modules.payment.PaymentMetrics;
import com.wherelee.edu.modules.payment.PaymentOrderRepository;
import com.wherelee.edu.modules.payment.PaymentOrderView;
import com.wherelee.edu.modules.payment.PaymentProperties;
import com.wherelee.edu.modules.payment.PaymentRepository;
import com.wherelee.edu.modules.payment.RefundRecord;
import com.wherelee.edu.modules.platform.alert.AlertPublisher;
import com.wherelee.edu.modules.platform.common.BizException;
import com.wherelee.edu.modules.platform.tracing.TraceSpans;
import lombok.extern.slf4j.Slf4j;
import com.wherelee.ctry.modules.commerce.entitlement.EntitlementRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class OrderRefundService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final EntitlementRepository entitlementRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final PaymentMetrics metrics;
    private final AlertPublisher alertPublisher;
    private final TraceSpans traceSpans;

    public OrderRefundService(OrderService orderService,
                         OrderRepository orderRepository,
                         PaymentRepository paymentRepository,
                         PaymentOrderRepository paymentOrderRepository,
                         EntitlementRepository entitlementRepository,
                         PaymentGateway paymentGateway,
                         PaymentProperties properties,
                         PlatformTransactionManager transactionManager,
                         PaymentMetrics metrics,
                         AlertPublisher alertPublisher,
                         TraceSpans traceSpans) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.entitlementRepository = entitlementRepository;
        this.paymentGateway = paymentGateway;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.alertPublisher = alertPublisher;
        this.traceSpans = traceSpans;
    }

    public RefundRecord refund(String orderNo, Long operatorId, String reason, boolean simulateFailure) {
        return refundInternal(orderNo, operatorId, reason, simulateFailure);
    }

    private RefundRecord refundInternal(String orderNo, Long operatorId, String reason, boolean simulateFailure) {
        OrderRecord order = orderService.require(orderNo);
        RefundRecord refund = paymentRepository.findRefundByOrder(order.id()).orElse(null);
        // 幂等：已成功或处理中的退款直接返回，不再校验订单状态。
        // 并发退款时，先到线程已把订单改成 REFUNDING，后到线程若先校验状态会误报"不能退款"。
        if (refund != null && ("SUCCEEDED".equals(refund.status()) || "PROCESSING".equals(refund.status()))) {
            return refund;
        }
        try {
            OrderTransitionPolicy.requireRefundable(order.status());
        } catch (BizException e) {
            // 状态刚被并发线程改掉：只要已经存在退款单，就按幂等返回它
            RefundRecord raced = paymentRepository.findRefundByOrder(order.id()).orElse(null);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
        if (refund == null) {
            String refundNo = "REF" + LocalDateTime.now().format(TIME)
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    paymentRepository.createRefund(refundNo, order.id(), order.priceCents(), reason, operatorId);
                    orderRepository.markRefunding(order.orderNo());
                    orderRepository.findPaymentNo(order.orderNo())
                            .ifPresent(paymentOrderRepository::markRefunding);
                });
            } catch (DuplicateKeyException e) {
                return paymentRepository.findRefundByOrder(order.id())
                        .orElseThrow(() -> new IllegalStateException("并发退款创建后不可读取"));
            }
            refund = paymentRepository.findRefundByOrder(order.id())
                    .orElseThrow(() -> new IllegalStateException("退款创建后不可读取"));
        } else {
            if ("PROCESSING".equals(refund.status())) {
                return refund;
            }
            Long refundId = refund.id();
            Integer claimed = transactionTemplate.execute(status -> {
                int changed = paymentRepository.retryRefund(refundId);
                if (changed == 1) {
                    orderRepository.markRefunding(order.orderNo());
                }
                return changed;
            });
            if (claimed == null || claimed != 1) {
                return paymentRepository.findRefundByOrder(order.id()).orElse(refund);
            }
            refund = paymentRepository.findRefundByOrder(order.id())
                    .orElseThrow(() -> new IllegalStateException("退款重试后不可读取"));
        }
        RefundRecord prepared = refund;
        return retryOnDeadlock(() -> processRefund(order, prepared, simulateFailure));
    }

    /**
     * MySQL 死锁是并发写同一行时的正常现象（事务被选为牺牲者），重试即可。
     * 只重试死锁/锁等待超时，其他异常照常抛出。
     */
    private RefundRecord retryOnDeadlock(java.util.function.Supplier<RefundRecord> action) {
        for (int attempt = 0; ; attempt++) {
            try {
                return action.get();
            } catch (CannotAcquireLockException | DeadlockLoserDataAccessException e) {
                if (attempt >= 2) {
                    throw e;
                }
                try {
                    Thread.sleep(20L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public void reconcile() {
        for (RefundRecord refund : paymentRepository.findPendingRefunds(
                properties.queryAfterSeconds(), properties.reconcileBatchSize())) {
            if (paymentRepository.claimPendingRefund(refund.id(), properties.queryAfterSeconds()) != 1) {
                continue;
            }
            OrderRecord order = orderRepository.findById(refund.orderId()).orElse(null);
            if (order != null) {
                processRefund(order, refund, false);
            }
        }
        for (RefundRecord refund : paymentRepository.findRetryableRefunds(
                properties.safeRefundMaxAttempts(), properties.reconcileBatchSize())) {
            if (paymentRepository.retryRefund(refund.id()) != 1) {
                continue;
            }
            OrderRecord order = orderRepository.findById(refund.orderId()).orElse(null);
            if (order != null) {
                orderRepository.markRefunding(order.orderNo());
                processRefund(order, refund, false);
            }
        }
    }

    private RefundRecord processRefund(OrderRecord order, RefundRecord refund, boolean simulateFailure) {
        return traceSpans.trace("payment.refund", TraceSpans.tags(
                "order.no", order.orderNo(),
                "refund.no", refund.refundNo(),
                "refund.simulate_failure", String.valueOf(simulateFailure)
        ), () -> processRefundInternal(order, refund, simulateFailure));
    }

    private RefundRecord processRefundInternal(OrderRecord order, RefundRecord refund, boolean simulateFailure) {
                PaymentOrderView view = new PaymentOrderView(
                order.orderNo(), order.orderNo(), "btry", "COURSE_ORDER",
                order.seriesTitle(), order.priceCents(), order.currency(), order.status().name(),
                order.payChannel(), order.providerTradeNo(), order.expireAt(), order.paidAt());
        PaymentGateway.RefundResult result = paymentGateway.refund(view, refund, simulateFailure);
        transactionTemplate.executeWithoutResult(status -> {
            OrderRecord locked = orderRepository.findByOrderNoForUpdate(order.orderNo()).orElse(order);
            if (locked.status() != OrderStatus.REFUNDED && locked.status() != OrderStatus.REFUNDING) {
                orderRepository.markRefunding(locked.orderNo());
            }
            if (result.success()) {
                metrics.refundSucceeded();
                paymentRepository.markRefundSucceeded(refund.id(), result.providerRefundNo());
                orderRepository.markRefunded(locked.orderNo());
                orderRepository.findPaymentNo(locked.orderNo())
                        .ifPresent(paymentOrderRepository::markRefunded);
                entitlementRepository.revokeOrder(
                        locked.userId(), locked.seriesId(), locked.id(),
                        "订单退款：" + refund.refundNo()
                );
            } else {
                metrics.refundFailed();
                paymentRepository.markRefundFailed(refund.id(), result.message(), nextRetryAt(refund));
                orderRepository.markRefundFailed(locked.orderNo(), result.message());
                orderRepository.findPaymentNo(locked.orderNo())
                        .ifPresent(paymentOrderRepository::markRefundFailed);
                alertRefund(locked.orderNo(), result.message());
            }
        });
        RefundRecord current = paymentRepository.findRefundByOrder(order.id())
                .orElseThrow(() -> new IllegalStateException("退款处理后不可读取"));
        if (result.success()) {
            log.info("payment_refund_result orderNo={} refundNo={} success=true status={} attemptCount={} providerRefundNo={}",
                    order.orderNo(), current.refundNo(), current.status(),
                    current.attemptCount(), current.providerRefundNo());
        } else {
            log.warn("payment_refund_result orderNo={} refundNo={} success=false status={} attemptCount={} nextRetryAt={} message={}",
                    order.orderNo(), current.refundNo(), current.status(),
                    current.attemptCount(), current.nextRetryAt(), result.message());
        }
        return current;
    }

    private LocalDateTime nextRetryAt(RefundRecord refund) {
        int nextAttempt = refund.attemptCount() + 1;
        if (nextAttempt >= properties.safeRefundMaxAttempts()) {
            return null;
        }
        long multiplier = 1L << Math.min(nextAttempt - 1, 6);
        long seconds = Math.min(3600, (long) properties.safeRefundRetryBaseSeconds() * multiplier);
        return LocalDateTime.now().plusSeconds(seconds);
    }

    private void alertRefund(String orderNo, String message) {
        try {
            alertPublisher.publish("PAYMENT_REFUND_FAILED", "ERROR",
                    "订单退款失败 orderNo=" + orderNo + " reason=" + message, false,
                    "webhook delivery not configured for payment alerts");
        } catch (Exception ignored) {
        }
    }
}
