package com.wherelee.ctry.modules.learning.learning;

import com.wherelee.ctry.contracts.commerce.EntitlementQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery.VideoContext;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学习进度写入。
 *
 * <p>视频元信息来自课程域契约，观看权限来自权益域契约——学习域自己不 join 任何其他域的表。
 * 这也是修复点：进度与播放日志现在用同一套权益口径（含 valid_to），不会出现
 * "能写播放日志但写不了进度"的不一致。</p>
 */
@Service
public class LearningProgressService {

    private final LearningProgressRepository repository;
    private final CatalogQuery catalogQuery;
    private final EntitlementQuery entitlementQuery;

    public LearningProgressService(LearningProgressRepository repository,
                                   CatalogQuery catalogQuery,
                                   EntitlementQuery entitlementQuery) {
        this.repository = repository;
        this.catalogQuery = catalogQuery;
        this.entitlementQuery = entitlementQuery;
    }

    @Transactional
    public void update(Long userId, Long videoId, LearningProgressDtos.UpdateRequest request) {
        VideoContext video = catalogQuery.findVideo(videoId)
                .orElseThrow(() -> BizException.notFound("视频不存在或无权观看"));
        if (!entitlementQuery.hasActive(userId, video.seriesId())) {
            throw BizException.notFound("视频不存在或无权观看");
        }
        int duration = request.durationSeconds() > 0
                ? request.durationSeconds() : video.durationSeconds();
        int position = Math.max(0, request.positionSeconds());
        if (duration > 0) {
            position = Math.min(position, duration);
        }
        boolean completed = request.completed()
                || (duration > 0 && position >= Math.max(duration - 3, duration * 0.95));
        if (completed && duration > 0) {
            position = duration;
        }
        repository.upsert(userId, video.seriesId(), videoId, position, duration, completed);
    }
}
