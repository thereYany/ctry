package com.wherelee.ctry.contracts.commerce;

import com.wherelee.edu.modules.contracts.payment.PaymentDtos;
import com.wherelee.edu.modules.platform.common.PageResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运营端订单操作门面。
 *
 * <p>operations.admin 只依赖这个应用契约，不直接访问订单/支付/退款 Repository，
 * 也不自行编排履约与补偿流程。</p>
 */
public interface AdminOrderOperations {

    /** 运营端订单列表：关键字（单号/课程名/用户 id 集合）+ 状态过滤。 */
    PageResult<OrderRow> page(String keyword, String status, List<Long> userIds, int page, int size);

    /** 需要人工关注的订单：已支付未履约、支付中堆积、有错误信息。 */
    PageResult<OrderRow> exceptions(int page, int size);

    OrderRow find(String orderNo);

    Snapshot snapshot(String orderNo);

    void queryNow(String orderNo);

    void fulfill(String orderNo);

    void close(String orderNo);

    void refund(String orderNo, Long operatorId, String reason, boolean simulateFailure);

    record OrderRow(
            String orderNo,
            Long userId,
            Long seriesId,
            String seriesTitle,
            long priceCents,
            String currency,
            String status,
            String payChannel,
            String providerTradeNo,
            LocalDateTime expireAt,
            LocalDateTime paidAt,
            LocalDateTime fulfilledAt,
            LocalDateTime closedAt,
            int fulfillmentAttempts,
            String lastError,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    record Snapshot(
            OrderDtos.OrderDetail orderDetail,
            List<PaymentDtos.AttemptView> attempts,
            List<PaymentDtos.EventView> events,
            PaymentDtos.RefundView refund
    ) {
    }
}