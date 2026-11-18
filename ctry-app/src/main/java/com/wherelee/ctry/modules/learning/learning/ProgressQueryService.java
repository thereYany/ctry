package com.wherelee.ctry.modules.learning.learning;

import com.wherelee.ctry.contracts.learning.ProgressQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习进度读侧实现：只读进度表，不 join 任何内容表。
 *
 * <p>内容归属由调用方（课程域）自己提供，学习域不猜"哪些视频属于哪门课"。</p>
 */
@Service
public class ProgressQueryService implements ProgressQuery {

    private final JdbcTemplate jdbc;

    public ProgressQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, ProgressSnapshot> byVideoIds(Long userId, Collection<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Map.of();
        }
        List<Long> unique = videoIds.stream().filter(id -> id != null).distinct().toList();
        if (unique.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(unique.size(), "?"));
        Object[] args = new Object[unique.size() + 1];
        args[0] = userId;
        for (int i = 0; i < unique.size(); i++) {
            args[i + 1] = unique.get(i);
        }
        Map<Long, ProgressSnapshot> result = new HashMap<>();
        jdbc.query("SELECT video_id, position_seconds, completed FROM t_learning_progress "
                        + "WHERE user_id = ? AND video_id IN (" + placeholders + ")",
                rs -> {
                    result.put(rs.getLong("video_id"),
                            new ProgressSnapshot(rs.getInt("position_seconds"), rs.getBoolean("completed")));
                }, args);
        return result;
    }
}
