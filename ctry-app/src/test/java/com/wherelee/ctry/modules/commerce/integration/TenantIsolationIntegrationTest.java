package com.wherelee.ctry.modules.commerce.integration;

import com.wherelee.ctry.modules.commerce.order.OrderRepository;
import com.wherelee.edu.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 期证据测试：多租户隔离。
 *
 * <p>覆盖三件事：写入自动打租户、读取自动按租户过滤、跨租户访问查不到数据。</p>
 */
class TenantIsolationIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private Long insertTenantSeries(long tenantId, String title) {
        jdbc.update("""
                INSERT INTO t_series
                    (tenant_id, teacher_id, title, summary, sort, status, published_at,
                     sale_status, price_cents, currency, sale_updated_at)
                VALUES (?, ?, ?, 'x', 1, 'PUBLISHED', CURRENT_TIMESTAMP(3),
                        'ON_SALE', 9900, 'CNY', CURRENT_TIMESTAMP(3))
                """, tenantId, fx.teacherId(), title);
        return jdbc.queryForObject(
                "SELECT id FROM t_series WHERE title = ? AND tenant_id = ?",
                Long.class, title, tenantId);
    }

    @Test
    @DisplayName("订单写入自动打上当前租户；其他租户查不到")
    void orderIsScopedByTenant() {
        TenantContext.set(0L);
        String tenant0Order = createOrder(nextRequestId()).orderNo();

        TenantContext.set(1L);
        Long tenant1Series = insertTenantSeries(1L, "租户1的课程");
        String tenant1Order = orderService.create(fx.studentId(),
                new com.wherelee.ctry.contracts.commerce.OrderDtos.CreateOrderRequest(
                        tenant1Series, nextRequestId())).orderNo();

        assertThat(orderRepository.findByOrderNo(tenant0Order)).isEmpty();
        assertThat(orderRepository.findByOrderNo(tenant1Order)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT tenant_id FROM t_order WHERE order_no = ? AND tenant_id = 1",
                Long.class, tenant1Order)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT tenant_id FROM t_order WHERE order_no = ? AND tenant_id = 0",
                Long.class, tenant0Order)).isEqualTo(0L);

        TenantContext.set(0L);
        assertThat(orderRepository.findByOrderNo(tenant0Order)).isPresent();
        assertThat(orderRepository.findByOrderNo(tenant1Order)).isEmpty();
    }

    @Test
    @DisplayName("商品目录按租户隔离：看不到其他租户的课程")
    void catalogIsScopedByTenant() {
        TenantContext.set(1L);
        insertTenantSeries(1L, "租户1的课程");

        TenantContext.set(0L);
        assertThat(orderService.catalog(fx.studentId()))
                .noneMatch(item -> item.title().equals("租户1的课程"));
        assertThat(orderService.catalog(fx.studentId()))
                .anyMatch(item -> item.seriesId().equals(fx.seriesId()));

        TenantContext.set(1L);
        assertThat(orderService.catalog(fx.studentId()))
                .anyMatch(item -> item.title().equals("租户1的课程"));
    }

    @Test
    @DisplayName("权益与学习空间按租户隔离")
    void entitlementIsScopedByTenant() {
        TenantContext.set(1L);
        assertThat(entitlementRepository.hasActive(fx.studentId(), fx.seriesId())).isFalse();
        TenantContext.set(0L);
        String orderNo = createOrder(nextRequestId()).orderNo();
        completePayment(orderNo);
        assertThat(entitlementRepository.hasActive(fx.studentId(), fx.seriesId())).isTrue();

        // 另一个租户看不到这条权益
        TenantContext.set(1L);
        assertThat(entitlementRepository.hasActive(fx.studentId(), fx.seriesId())).isFalse();
    }
}
