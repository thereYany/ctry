package com.wherelee.ctry.modules.operations.admin;

import com.wherelee.ctry.contracts.commerce.EntitlementAdmin;
import com.wherelee.ctry.contracts.commerce.EntitlementQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery;
import com.wherelee.edu.modules.contracts.identity.AccountQuery;
import com.wherelee.edu.modules.contracts.identity.AccountQuery.AccountProfile;
import com.wherelee.edu.modules.contracts.identity.UserAccount;
import com.wherelee.edu.modules.contracts.identity.UserAccountOperations;
import com.wherelee.ctry.modules.operations.admin.dto.AdminUserVO;
import com.wherelee.ctry.modules.operations.admin.dto.AssignSeriesRequest;
import com.wherelee.ctry.modules.operations.admin.dto.CreateUserRequest;
import com.wherelee.ctry.modules.operations.admin.dto.SeriesOptionVO;
import com.wherelee.edu.modules.platform.common.BizException;
import com.wherelee.edu.modules.platform.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运营端用户管理：账号数据全部走身份域契约，权益分配走权益域契约。
 *
 * <p>本服务不再持有任何用户表/权益表的 SQL——运营端只做编排与展示拼装。</p>
 */
@Service
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 10_000;

    private final AccountQuery accountQuery;
    private final UserAccountOperations userAccounts;
    private final EntitlementQuery entitlementQuery;
    private final EntitlementAdmin entitlementAdmin;
    private final CatalogQuery catalogQuery;

    public AdminUserService(AccountQuery accountQuery,
                            UserAccountOperations userAccounts,
                            EntitlementQuery entitlementQuery,
                            EntitlementAdmin entitlementAdmin,
                            CatalogQuery catalogQuery) {
        this.accountQuery = accountQuery;
        this.userAccounts = userAccounts;
        this.entitlementQuery = entitlementQuery;
        this.entitlementAdmin = entitlementAdmin;
        this.catalogQuery = catalogQuery;
    }

    public PageResult<AdminUserVO> page(String keyword, String role, String status, int page, int size) {
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = accountQuery.count(blankToNull(keyword), blankToNull(role), blankToNull(status));
        if (total == 0) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        PageResult<AccountProfile> accounts = accountQuery.page(
                blankToNull(keyword), blankToNull(role), blankToNull(status),
                (safePage - 1) * safeSize, safeSize);
        Map<Long, Integer> counts = entitlementQuery.activeCountsByUser(
                accounts.records().stream().map(AccountProfile::id).toList());
        List<AdminUserVO> records = accounts.records().stream()
                .map(profile -> toVo(profile, counts.getOrDefault(profile.id(), 0)))
                .toList();
        return new PageResult<>(records, total, safePage, safeSize);
    }

    public Long create(CreateUserRequest request) {
        if (!"STUDENT".equals(request.role())) {
            throw BizException.badRequest("教师账号必须通过邀请注册");
        }
        return userAccounts.createUser(
                request.username(),
                request.initialPassword(),
                request.nickname(),
                "STUDENT"
        );
    }

    public void changeStatus(Long operatorId, Long userId, String status) {
        userAccounts.changeStatus(operatorId, userId, status);
    }

    public void resetPassword(Long operatorId, Long userId, String newPassword) {
        userAccounts.resetPassword(operatorId, userId, newPassword);
    }

    @Transactional
    public void assignSeries(Long userId, AssignSeriesRequest request) {
        UserAccount target = userAccounts.require(userId);
        if (!"STUDENT".equals(target.role())) {
            throw BizException.badRequest("只有学员可以分配系列");
        }
        List<Long> seriesIds = request.seriesIds() == null
                ? List.of() : request.seriesIds().stream().distinct().toList();
        Set<Long> validIds = catalogQuery.publishedOptions().stream()
                .map(CatalogQuery.SeriesOption::id)
                .collect(Collectors.toSet());
        if (!validIds.containsAll(seriesIds)) {
            throw BizException.badRequest("包含不存在的系列");
        }
        entitlementAdmin.replaceAdminGrants(userId, seriesIds);
    }

    public List<Long> seriesIds(Long userId) {
        userAccounts.require(userId);
        return entitlementAdmin.adminSeriesIds(userId);
    }

    public List<SeriesOptionVO> seriesOptions() {
        return catalogQuery.publishedOptions().stream()
                .map(option -> new SeriesOptionVO(option.id(), option.title()))
                .toList();
    }

    private AdminUserVO toVo(AccountProfile profile, int seriesCount) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(profile.id());
        vo.setUsername(profile.username());
        vo.setNickname(profile.nickname());
        vo.setRole(profile.role());
        vo.setStatus(profile.status());
        vo.setMustChangePassword(profile.mustChangePassword());
        vo.setSeriesCount((long) seriesCount);
        vo.setCreatedAt(profile.createdAt());
        return vo;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
