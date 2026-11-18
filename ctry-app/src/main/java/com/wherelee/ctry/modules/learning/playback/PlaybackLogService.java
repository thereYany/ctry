package com.wherelee.ctry.modules.learning.playback;

import com.wherelee.ctry.contracts.commerce.EntitlementQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery.VideoContext;
import com.wherelee.edu.modules.platform.aop.Measured;
import com.wherelee.ctry.contracts.learning.PlaybackEventPublisher;
import com.wherelee.edu.modules.platform.common.web.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 播放行为记录。
 *
 * <p>归属校验走课程域 + 权益域契约，不再 join 其他域的表；
 * 与进度写入共用同一套权益口径，避免两个子系统判定不一致。</p>
 */
@Slf4j
@Service
public class PlaybackLogService implements PlaybackEventPublisher {

    private final PlaybackLogRepository repository;
    private final PlaybackLogQueue queue;
    private final CatalogQuery catalogQuery;
    private final EntitlementQuery entitlementQuery;

    public PlaybackLogService(PlaybackLogRepository repository,
                              PlaybackLogQueue queue,
                              CatalogQuery catalogQuery,
                              EntitlementQuery entitlementQuery) {
        this.repository = repository;
        this.queue = queue;
        this.catalogQuery = catalogQuery;
        this.entitlementQuery = entitlementQuery;
    }

    @Override
    public void recordUrlRequest(Long userId, Long videoId, Long seriesId,
                                 boolean cacheHit, String userAgent) {
        offer(new PlaybackLogEvent(
                userId, videoId, seriesId, "BACKEND", "URL_REQUEST",
                null, null, cacheHit, null, limit(userAgent, 300), null,
                TraceIdFilter.currentTraceId()));
    }

    @Measured("playback.log.record")
    public void recordFrontend(Long userId, PlaybackEventRequest request, String userAgent) {
        VideoContext video = catalogQuery.findVideo(request.videoId()).orElse(null);
        if (video == null || !entitlementQuery.hasActive(userId, video.seriesId())) {
            log.warn("忽略无权或不存在的视频播放日志 userId={} videoId={}", userId, request.videoId());
            return;
        }
        offer(new PlaybackLogEvent(
                userId, video.id(), video.seriesId(), "FRONTEND",
                request.eventType(), request.positionSeconds(), request.durationSeconds(),
                null, limit(request.message(), 500), limit(userAgent, 300), limit(request.sessionId(), 64),
                TraceIdFilter.currentTraceId()));
    }

    private void offer(PlaybackLogEvent event) {
        if (!queue.offer(event)) {
            log.warn("播放日志队列已满，丢弃事件 source={} type={} userId={}",
                    event.getSource(), event.getEventType(), event.getUserId());
        }
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
