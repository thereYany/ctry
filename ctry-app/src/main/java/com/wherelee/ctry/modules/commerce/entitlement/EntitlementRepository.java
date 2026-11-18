package com.wherelee.ctry.modules.commerce.entitlement;

import com.wherelee.ctry.contracts.commerce.EntitlementQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Repository
public class EntitlementRepository {

    private final JdbcTemplate jdbc;

    public EntitlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasActive(Long userId, Long seriesId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM t_student_series
                WHERE user_id = ? AND series_id = ? AND status = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP(3))
                  AND (valid_to IS NULL OR valid_to > CURRENT_TIMESTAMP(3))
                """, Integer.class, userId, seriesId);
        return count != null && count > 0;
    }

    /** 管理端：该用户由管理员分配的有效课程。 */
    public java.util.List<Long> findAdminSeriesIds(Long userId) {
        return jdbc.queryForList("""
                SELECT series_id FROM t_student_series
                WHERE user_id = ? AND source = 'ADMIN' AND status = 'ACTIVE'
                ORDER BY sort, id
                """, Long.class, userId);
    }

    public void deleteAdminGrants(Long userId) {
        jdbc.update("DELETE FROM t_student_series WHERE user_id = ? AND source = 'ADMIN'", userId);
    }

    public void insertAdminGrant(Long userId, Long seriesId, int sort) {
        jdbc.update("""
                INSERT INTO t_student_series
                    (user_id, series_id, sort, source, status, granted_at)
                VALUES (?, ?, ?, 'ADMIN', 'ACTIVE', CURRENT_TIMESTAMP(3))
                """, userId, seriesId, sort);
    }

    /** 管理端用户列表用：批量统计每个用户的有效权益数。 */
    public java.util.Map<Long, Integer> countActiveByUser(java.util.Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Map.of();
        }
        var ids = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        java.util.Map<Long, Integer> result = new java.util.HashMap<>();
        jdbc.query("SELECT user_id, COUNT(*) AS cnt FROM t_student_series "
                        + "WHERE status = 'ACTIVE' AND user_id IN (" + placeholders + ") GROUP BY user_id",
                rs -> {
                    result.put(rs.getLong("user_id"), rs.getInt("cnt"));
                }, ids.toArray());
        return result;
    }

    /** 管理端课程列表用：批量统计每门课的有效学员数。 */
    public java.util.Map<Long, Integer> countActiveStudents(java.util.Collection<Long> seriesIds) {
        if (seriesIds == null || seriesIds.isEmpty()) {
            return java.util.Map.of();
        }
        var ids = seriesIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        java.util.Map<Long, Integer> result = new java.util.HashMap<>();
        jdbc.query("SELECT series_id, COUNT(DISTINCT user_id) AS students FROM t_student_series "
                        + "WHERE status = 'ACTIVE' AND series_id IN (" + placeholders + ") GROUP BY series_id",
                rs -> {
                    result.put(rs.getLong("series_id"), rs.getInt("students"));
                }, ids.toArray());
        return result;
    }

    /** 学习空间用：该用户当前有效权益及排序。 */
    public java.util.List<EntitlementQuery.ActiveEntitlement> findActiveEntitlements(Long userId) {
        return jdbc.query("""
                SELECT series_id, sort
                FROM t_student_series
                WHERE user_id = ? AND status = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP(3))
                  AND (valid_to IS NULL OR valid_to > CURRENT_TIMESTAMP(3))
                ORDER BY sort, series_id
                """, (rs, rowNum) -> new EntitlementQuery.ActiveEntitlement(
                rs.getLong("series_id"), rs.getInt("sort")), userId);
    }

    /** 批量取某用户当前有效的已购系列（商店列表用，避免 N+1）。 */
    public Set<Long> activeSeriesIds(Long userId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT series_id
                FROM t_student_series
                WHERE user_id = ? AND status = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP(3))
                  AND (valid_to IS NULL OR valid_to > CURRENT_TIMESTAMP(3))
                """, Long.class, userId));
    }

    public void grantOrder(Long userId, Long seriesId, Long orderId) {
        jdbc.update("""
                INSERT INTO t_student_series
                    (user_id, series_id, sort, source, status, order_id,
                     valid_from, granted_at)
                VALUES (?, ?, 1, 'ORDER', 'ACTIVE', ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE
                    status = 'ACTIVE',
                    order_id = VALUES(order_id),
                    valid_from = COALESCE(valid_from, CURRENT_TIMESTAMP(3)),
                    granted_at = CURRENT_TIMESTAMP(3),
                    revoked_at = NULL,
                    revoke_reason = NULL
                """, userId, seriesId, orderId);
    }

    public void revokeOrder(Long userId, Long seriesId, Long orderId, String reason) {
        jdbc.update("""
                UPDATE t_student_series
                SET status = 'REVOKED',
                    revoked_at = ?,
                    revoke_reason = ?
                WHERE user_id = ? AND series_id = ? AND order_id = ?
                  AND source = 'ORDER' AND status = 'ACTIVE'
                """, Timestamp.valueOf(LocalDateTime.now()), reason, userId, seriesId, orderId);
    }
}
