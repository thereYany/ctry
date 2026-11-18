package com.wherelee.ctry.modules.operations.integration;

import com.wherelee.ctry.modules.commerce.integration.CommerceIntegrationTestSupport;
import com.wherelee.ctry.modules.operations.admin.AdminOrderService;
import com.wherelee.ctry.modules.operations.admin.AdminUserService;
import com.wherelee.ctry.modules.operations.admin.dto.AdminUserVO;
import com.wherelee.ctry.modules.operations.admin.dto.AssignSeriesRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 期证据测试：运营端改为「账号/权益/课程各走契约」后，管理功能行为不变。
 *
 * <p>覆盖：用户分页（含权益数）、分配课程（走权益域契约）、订单分页（下单人信息来自身份域）。</p>
 */
class AdminConsoleIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private AdminOrderService adminOrderService;

    @Test
    @DisplayName("用户列表带权益数；分配课程走权益契约并落 source=ADMIN")
    void assignSeriesThroughEntitlementContract() {
        var page = adminUserService.page(null, "STUDENT", null, 1, 20);
        assertThat(page.records()).hasSize(1);
        AdminUserVO student = page.records().get(0);
        assertThat(student.getUsername()).isEqualTo("it_student");
        assertThat(student.getSeriesCount()).isZero();

        adminUserService.assignSeries(fx.studentId(), new AssignSeriesRequest(List.of(fx.seriesId())));

        assertThat(adminUserService.seriesIds(fx.studentId())).containsExactly(fx.seriesId());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_student_series
                WHERE user_id = ? AND series_id = ? AND source = 'ADMIN' AND status = 'ACTIVE'
                """, Integer.class, fx.studentId(), fx.seriesId())).isEqualTo(1);

        var afterAssign = adminUserService.page(null, "STUDENT", null, 1, 20).records().get(0);
        assertThat(afterAssign.getSeriesCount()).isEqualTo(1);

        // 全量覆盖为空 → 权益被回收
        adminUserService.assignSeries(fx.studentId(), new AssignSeriesRequest(List.of()));
        assertThat(adminUserService.seriesIds(fx.studentId())).isEmpty();
    }

    @Test
    @DisplayName("订单列表与详情中的下单人信息来自身份域契约")
    void orderPageEnrichesCustomerFromIdentityContract() {
        String orderNo = createOrder(nextRequestId()).orderNo();

        var page = adminOrderService.page("it_student", null, 1, 20);
        assertThat(page.records()).isNotEmpty();
        var row = page.records().get(0);
        assertThat(row.getOrderNo()).isEqualTo(orderNo);
        assertThat(row.getUsername()).isEqualTo("it_student");
        assertThat(row.getNickname()).isEqualTo("集成测试学生");

        var detail = adminOrderService.detail(orderNo);
        assertThat(detail.order().getUsername()).isEqualTo("it_student");
    }
}
