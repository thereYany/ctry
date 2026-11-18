package com.wherelee.ctry.modules.commerce.order;

import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.api.PaymentSettledListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 订单侧支付结果消费者：支付域只发事件，订单自己决定怎么履约。
 *
 * <p>幂等保证：订单已是 PAID/FULFILLED 时直接返回；权益发放由履约服务用唯一键兜底。
 * 事件重复投递不会重复发货。</p>
 */
@Slf4j
@Component
public class OrderPaymentSettledListener implements PaymentSettledListener {

    private final OrderRepository orderRepository;
    private final OrderFulfillmentService fulfillmentService;

    public OrderPaymentSettledListener(OrderRepository orderRepository,
                                       OrderFulfillmentService fulfillmentService) {
        this.orderRepository = orderRepository;
        this.fulfillmentService = fulfillmentService;
    }

    @Override
    public void onSettled(PaymentSettledEvent event) {
        // 只处理课程订单事件；其他垂直域（资料包等）由各自监听器消费
        if (!"COURSE_ORDER".equals(event.bizType())) {
            return;
        }
        OrderRecord order = orderRepository.findByOrderNo(event.bizNo()).orElse(null);
        if (order == null) {
            // 抛出而不是忽略：让投递器重试，最终进死信暴露问题
            throw new IllegalStateException("支付事件对应的订单不存在 bizNo=" + event.bizNo());
        }
        switch (event.eventType()) {
            case PaymentEventTypes.PAYMENT_SUCCEEDED -> onSucceeded(order, event);
            case PaymentEventTypes.PAYMENT_FAILED -> onFailed(order, event);
            case PaymentEventTypes.PAYMENT_EXPIRED -> onExpired(order, event);
            default -> log.debug("忽略非结算类支付事件 type={} bizNo={}",
                    event.eventType(), event.bizNo());
        }
    }

    private void onSucceeded(OrderRecord order, PaymentSettledEvent event) {
        if (order.status() == OrderStatus.PAID || order.status() == OrderStatus.FULFILLED
                || order.status().paid()) {
            log.debug("订单已支付，跳过重复事件 bizNo={} status={}", order.orderNo(), order.status());
            return;
        }
        orderRepository.markPaid(order.orderNo(), event.providerTradeNo(), event.channel());
        fulfillmentService.fulfill(order.orderNo());
        log.info("支付事件驱动履约完成 bizNo={} paymentNo={}", order.orderNo(), event.paymentNo());
    }

    private void onExpired(OrderRecord order, PaymentSettledEvent event) {
        if (order.status() == OrderStatus.CREATED) {
            orderRepository.close(order.orderNo(), false);
        } else if (order.status() == OrderStatus.PAYING) {
            // 支付域已向渠道确认无此交易，才允许关闭支付中的订单
            orderRepository.closeAfterProviderNotFound(order.orderNo());
        } else {
            return;
        }
        log.info("支付单过期，业务订单已关闭 bizNo={} paymentNo={}",
                order.orderNo(), event.paymentNo());
    }

    private void onFailed(OrderRecord order, PaymentSettledEvent event) {
        if (order.status() != OrderStatus.PAYING) {
            log.debug("订单不在支付中，忽略失败事件 bizNo={} status={}", order.orderNo(), order.status());
            return;
        }
        orderRepository.markPayFailed(order.orderNo(), "支付平台返回失败");
    }
}
