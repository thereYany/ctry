package com.wherelee.ctry.contracts.learning;

import java.util.Collection;
import java.util.Map;

/**
 * 学习进度读侧契约。
 *
 * <p>课程域展示"看到哪了/完成几个"时只依赖这个接口，不直接查进度表。</p>
 */
public interface ProgressQuery {

    /** 批量取一批视频的进度；没有记录的 videoId 不出现在结果里。 */
    Map<Long, ProgressSnapshot> byVideoIds(Long userId, Collection<Long> videoIds);

    record ProgressSnapshot(int positionSeconds, boolean completed) {
    }
}
