package com.wherelee.ctry.modules.learning.playback;

import com.wherelee.ctry.contracts.learning.PlaybackStatsQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 播放统计实现：唯一允许直接读 t_playback_log 统计的组件（学习域自有）。
 */
@Service
public class PlaybackStatsQueryService implements PlaybackStatsQuery {

    private final JdbcTemplate jdbc;

    public PlaybackStatsQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countEvents(String eventType, LocalDateTime since) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_playback_log
                WHERE event_type = ? AND created_at >= ?
                """, Long.class, eventType, Timestamp.valueOf(since));
        return value == null ? 0 : value;
    }

    @Override
    public CacheStats cacheStats(LocalDateTime since) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN cache_hit = 1 THEN 1 ELSE 0 END), 0) AS hits,
                       COALESCE(SUM(CASE WHEN cache_hit = 0 THEN 1 ELSE 0 END), 0) AS misses,
                       COUNT(*) AS requests
                FROM t_playback_log
                WHERE source = 'BACKEND' AND event_type = 'URL_REQUEST' AND created_at >= ?
                """, (rs, rowNum) -> new CacheStats(
                rs.getLong("hits"), rs.getLong("misses"), rs.getLong("requests")
        ), Timestamp.valueOf(since));
    }
}
