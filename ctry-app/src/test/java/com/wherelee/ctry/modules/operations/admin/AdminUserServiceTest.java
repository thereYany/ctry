package com.wherelee.ctry.modules.operations.admin;

import com.wherelee.ctry.contracts.commerce.EntitlementAdmin;
import com.wherelee.ctry.contracts.commerce.EntitlementQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery.SeriesOption;
import com.wherelee.edu.modules.contracts.identity.AccountQuery;
import com.wherelee.edu.modules.contracts.identity.AccountQuery.AccountProfile;
import com.wherelee.edu.modules.contracts.identity.UserAccount;
import com.wherelee.edu.modules.contracts.identity.UserAccountOperations;
import com.wherelee.ctry.modules.operations.admin.dto.AdminUserVO;
import com.wherelee.ctry.modules.operations.admin.dto.AssignSeriesRequest;
import com.wherelee.ctry.modules.operations.admin.dto.CreateUserRequest;
import com.wherelee.edu.modules.platform.common.BizException;
import com.wherelee.edu.modules.platform.common.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private AccountQuery accountQuery;
    @Mock
    private UserAccountOperations userAccounts;
    @Mock
    private EntitlementQuery entitlementQuery;
    @Mock
    private EntitlementAdmin entitlementAdmin;
    @Mock
    private CatalogQuery catalogQuery;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(accountQuery, userAccounts, entitlementQuery,
                entitlementAdmin, catalogQuery);
    }

    @Test
    void onlyStudentCanReceiveSeries() {
        when(userAccounts.require(2L)).thenReturn(new UserAccount(2L, "teacher", "教师", "TEACHER", "ACTIVE"));

        assertThatThrownBy(() -> service.assignSeries(2L, new AssignSeriesRequest(List.of(1L))))
                .isInstanceOf(BizException.class)
                .hasMessage("只有学员可以分配系列");

        verify(entitlementAdmin, never()).replaceAdminGrants(anyLong(), anyList());
    }

    @Test
    void rejectsUnknownSeriesBeforeReplacingExistingAssignments() {
        when(userAccounts.require(2L)).thenReturn(new UserAccount(2L, "student", "学生", "STUDENT", "ACTIVE"));
        when(catalogQuery.publishedOptions()).thenReturn(List.of(new SeriesOption(1L, "物理必修一")));

        assertThatThrownBy(() -> service.assignSeries(2L, new AssignSeriesRequest(List.of(1L, 99L))))
                .isInstanceOf(BizException.class)
                .hasMessage("包含不存在的系列");

        verify(entitlementAdmin, never()).replaceAdminGrants(anyLong(), anyList());
    }

    @Test
    void assignReplacesAdminGrantsThroughContract() {
        when(userAccounts.require(2L)).thenReturn(new UserAccount(2L, "student", "学生", "STUDENT", "ACTIVE"));
        when(catalogQuery.publishedOptions()).thenReturn(List.of(
                new SeriesOption(1L, "物理必修一"), new SeriesOption(2L, "物理必修二")));

        service.assignSeries(2L, new AssignSeriesRequest(List.of(2L, 1L)));

        verify(entitlementAdmin).replaceAdminGrants(2L, List.of(2L, 1L));
    }

    @Test
    void pageEnrichesSeriesCountFromEntitlementContract() {
        AccountProfile profile = new AccountProfile(2L, "student", "学生", "STUDENT", "ACTIVE",
                false, LocalDateTime.now());
        when(accountQuery.count(null, null, null)).thenReturn(1L);
        when(accountQuery.page(null, null, null, 0, 20))
                .thenReturn(new PageResult<>(List.of(profile), 1, 1, 20));
        when(entitlementQuery.activeCountsByUser(List.of(2L))).thenReturn(Map.of(2L, 3));

        PageResult<AdminUserVO> page = service.page(null, null, null, 1, 20);

        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).getSeriesCount()).isEqualTo(3L);
        assertThat(page.records().get(0).getUsername()).isEqualTo("student");
    }

    @Test
    void createRejectsNonStudentRole() {
        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("teacher2", "教师二", "Pass1234", "TEACHER")))
                .isInstanceOf(BizException.class)
                .hasMessage("教师账号必须通过邀请注册");

        verifyNoInteractions(accountQuery, userAccounts, entitlementQuery, entitlementAdmin, catalogQuery);
    }
}
