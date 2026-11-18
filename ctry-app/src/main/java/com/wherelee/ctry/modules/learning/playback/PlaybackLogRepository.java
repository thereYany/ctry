package com.wherelee.ctry.modules.learning.playback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Repository
public class PlaybackLogRepository {

    private final JdbcTemplate jdbc;

    public PlaybackLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long userId, Long videoId, Long seriesId, String source, String eventType,
                       Integer positionSeconds, Integer durationSeconds, Boolean cacheHit,
                       String message, String userAgent, String sessionId) {
        jdbc.update("""
                INSERT INTO t_playback_log
                    (user_id, video_id, series_id, source, event_type, position_seconds,
                     duration_seconds, cache_hit, message, user_agent, session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, videoId, seriesId, source, eventType, positionSeconds,
                durationSeconds, cacheHit, message, userAgent, sessionId);
    }

    public int deleteOlderThan(LocalDateTime cutoff) {
        return jdbc.update("DELETE FROM t_playback_log WHERE created_at < ?", Timestamp.valueOf(cutoff));
    }
}
