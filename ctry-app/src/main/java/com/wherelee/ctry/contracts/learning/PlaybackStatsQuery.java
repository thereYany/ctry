package com.wherelee.ctry.contracts.learning;

import java.time.LocalDateTime;

/**
 * 播放统计数据契约（告警/看板用）。
 *
 * <p>播放日志是学习域的数据，其他模块统计播放情况时走这个接口，不直接查日志表。</p>
 */
public interface PlaybackStatsQuery {

    long countEvents(String eventType, LocalDateTime since);

    CacheStats cacheStats(LocalDateTime since);

    record CacheStats(long hits, long misses, long requests) {
    }
}
