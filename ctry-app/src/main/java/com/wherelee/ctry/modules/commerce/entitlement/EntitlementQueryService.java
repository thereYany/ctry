package com.wherelee.ctry.modules.commerce.entitlement;

import com.wherelee.ctry.contracts.commerce.EntitlementQuery;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权益读侧契约实现：只读权益表，不懂课程内容。
 */
@Service
public class EntitlementQueryService implements EntitlementQuery {

    private final EntitlementRepository repository;

    public EntitlementQueryService(EntitlementRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean hasActive(Long userId, Long seriesId) {
        return repository.hasActive(userId, seriesId);
    }

    @Override
    public List<ActiveEntitlement> activeEntitlements(Long userId) {
        return repository.findActiveEntitlements(userId);
    }

    @Override
    public java.util.Map<Long, Integer> activeStudentCounts(java.util.Collection<Long> seriesIds) {
        return repository.countActiveStudents(seriesIds);
    }

    @Override
    public java.util.Map<Long, Integer> activeCountsByUser(java.util.Collection<Long> userIds) {
        return repository.countActiveByUser(userIds);
    }
}
