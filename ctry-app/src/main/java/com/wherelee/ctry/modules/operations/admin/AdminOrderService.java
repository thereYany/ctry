package com.wherelee.ctry.modules.operations.admin;

import com.wherelee.ctry.contracts.commerce.AdminOrderOperations;
import com.wherelee.ctry.contracts.commerce.AdminOrderOperations.OrderRow;
import com.wherelee.ctry.contracts.payment.PaymentDtos;
import com.wherelee.edu.modules.contracts.identity.AccountDirectory;
import com.wherelee.edu.modules.contracts.identity.AccountQuery.AccountProfile;
import com.wherelee.edu.modules.platform.common.PageResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 运营端订单管理：列表/详情查询走 commerce 契约，下单人信息走身份域契约。
 *
 * <p>本服务不再持有订单表 SQL。</p>
 */
@Service
public class AdminOrderService {

    private final AdminOrderOperations orderOperations;
    private final AccountDirectory accountDirectory;

    public AdminOrderService(AdminOrderOperations orderOperations,
                             AccountDirectory accountDirectory) {
        this.orderOperations = orderOperations;
        this.accountDirectory = accountDirectory;
    }

    public PageResult<AdminOrderVO> page(String keyword, String status, int page, int size) {
        String normalizedKeyword = blankToNull(keyword);
        // 按用户名/昵称搜索在身份域完成，订单库只按 userId 集合过滤
        List<Long> userIds = normalizedKeyword == null
                ? null : accountDirectory.searchIds(normalizedKeyword, 200);
        PageResult<OrderRow> result = orderOperations.page(
                normalizedKeyword, blankToNull(status), userIds, page, size);
        return new PageResult<>(enrich(result.records()), result.total(), result.page(), result.size());
    }

    public PageResult<AdminOrderVO> exceptions(int page, int size) {
        PageResult<OrderRow> result = orderOperations.exceptions(page, size);
        return new PageResult<>(enrich(result.records()), result.total(), result.page(), result.size());
    }

    public AdminOrderDtos.Detail detail(String orderNo) {
        AdminOrderVO row = enrich(List.of(orderOperations.find(orderNo))).get(0);
        AdminOrderOperations.Snapshot snapshot = orderOperations.snapshot(orderNo);
        return new AdminOrderDtos.Detail(
                row,
                snapshot.orderDetail(),
                snapshot.attempts(),
                snapshot.events(),
                snapshot.refund()
        );
    }

    public AdminOrderDtos.Detail query(String orderNo) {
        orderOperations.queryNow(orderNo);
        return detail(orderNo);
    }

    public AdminOrderDtos.Detail fulfill(String orderNo) {
        orderOperations.fulfill(orderNo);
        return detail(orderNo);
    }

    public AdminOrderDtos.Detail close(String orderNo) {
        orderOperations.close(orderNo);
        return detail(orderNo);
    }

    public AdminOrderDtos.Detail refund(String orderNo, Long operatorId,
                                        PaymentDtos.RefundRequest request) {
        orderOperations.refund(orderNo, operatorId, request.reason(), request.simulateFailure());
        return detail(orderNo);
    }

    /** 下单人展示信息来自身份域契约，订单库不再 join 用户表。 */
    private List<AdminOrderVO> enrich(List<OrderRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, AccountProfile> profiles = accountDirectory.profiles(
                rows.stream().map(OrderRow::userId).toList());
        return rows.stream().map(row -> {
            AdminOrderVO vo = toVo(row);
            AccountProfile profile = profiles.get(row.userId());
            if (profile != null) {
                vo.setUsername(profile.username());
                vo.setNickname(profile.nickname());
            }
            return vo;
        }).toList();
    }

    private static AdminOrderVO toVo(OrderRow row) {
        AdminOrderVO vo = new AdminOrderVO();
        vo.setOrderNo(row.orderNo());
        vo.setUserId(row.userId());
        vo.setSeriesId(row.seriesId());
        vo.setSeriesTitle(row.seriesTitle());
        vo.setPriceCents(row.priceCents());
        vo.setCurrency(row.currency());
        vo.setStatus(row.status());
        vo.setPayChannel(row.payChannel());
        vo.setProviderTradeNo(row.providerTradeNo());
        vo.setExpireAt(row.expireAt());
        vo.setPaidAt(row.paidAt());
        vo.setFulfilledAt(row.fulfilledAt());
        vo.setClosedAt(row.closedAt());
        vo.setFulfillmentAttempts(row.fulfillmentAttempts());
        vo.setLastError(row.lastError());
        vo.setCreatedAt(row.createdAt());
        vo.setUpdatedAt(row.updatedAt());
        return vo;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
