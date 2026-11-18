package com.wherelee.ctry.modules.learning.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LearningProgressRepository {

    private final JdbcTemplate jdbc;

    public LearningProgressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(Long userId, Long seriesId, Long videoId,
                       int positionSeconds, int durationSeconds, boolean completed) {
        jdbc.update("""
                INSERT INTO t_learning_progress
                    (user_id, series_id, video_id, position_seconds, duration_seconds, completed)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    series_id = VALUES(series_id),
                    position_seconds = VALUES(position_seconds),
                    duration_seconds = VALUES(duration_seconds),
                    completed = completed OR VALUES(completed)
                """, userId, seriesId, videoId, positionSeconds, durationSeconds, completed);
    }

}
