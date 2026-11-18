package com.wherelee.ctry.contracts.commerce;

import java.util.List;

/**
 * 权益读侧契约：谁拥有哪个资源、有效期到什么时候。
 *
 * <p>其他模块判断"能不能看"时只依赖这个接口，不直接查权益表。
 * 注意这里只回答"有没有权益"，不回答"内容是否已发布"——那是课程域的判断。</p>
 */
public interface EntitlementQuery {

    boolean hasActive(Long userId, Long seriesId);

    /** 该用户当前有效的全部权益，按学习空间排序返回。 */
    List<ActiveEntitlement> activeEntitlements(Long userId);

    /** 批量取每门课当前有效学员数（管理端课程列表用）。 */
    java.util.Map<Long, Integer> activeStudentCounts(java.util.Collection<Long> seriesIds);

    /** 批量取每个用户当前有效权益数（管理端用户列表用）。 */
    java.util.Map<Long, Integer> activeCountsByUser(java.util.Collection<Long> userIds);

    record ActiveEntitlement(Long seriesId, int sort) {
    }
}
