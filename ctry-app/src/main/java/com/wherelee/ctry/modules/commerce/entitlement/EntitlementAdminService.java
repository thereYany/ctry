package com.wherelee.ctry.modules.commerce.entitlement;

import com.wherelee.ctry.contracts.commerce.EntitlementAdmin;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 权益管理写侧实现：负责 source=ADMIN 这一类的全量覆盖。
 */
@Service
public class EntitlementAdminService implements EntitlementAdmin {

    private final EntitlementRepository repository;

    public EntitlementAdminService(EntitlementRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Long> adminSeriesIds(Long userId) {
        return repository.findAdminSeriesIds(userId);
    }

    @Override
    @Transactional
    public void replaceAdminGrants(Long userId, List<Long> seriesIds) {
        repository.deleteAdminGrants(userId);
        int sort = 1;
        for (Long seriesId : seriesIds.stream().filter(id -> id != null).distinct().toList()) {
            repository.insertAdminGrant(userId, seriesId, sort++);
        }
    }
}
