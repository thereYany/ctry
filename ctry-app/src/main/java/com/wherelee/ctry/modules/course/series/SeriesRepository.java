package com.wherelee.ctry.modules.course.series;

import com.wherelee.ctry.contracts.commerce.EntitlementQuery;
import com.wherelee.ctry.contracts.commerce.EntitlementQuery.ActiveEntitlement;
import com.wherelee.ctry.contracts.course.CatalogQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery.CatalogSeries;
import com.wherelee.ctry.contracts.course.CatalogQuery.VideoContext;
import com.wherelee.ctry.contracts.course.CatalogQuery.VideoRef;
import com.wherelee.ctry.modules.course.series.dto.SeriesDtos;
import com.wherelee.ctry.contracts.learning.ProgressQuery;
import com.wherelee.ctry.contracts.learning.ProgressQuery.ProgressSnapshot;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学习空间读模型：由三段数据拼装，不再写跨域 SQL。
 *
 * <pre>
 * 课程/章节/视频 ← course.api.CatalogQuery（课程域自己的表）
 * 有没有权益      ← commerce.api.EntitlementQuery
 * 看到哪了        ← learning.api.ProgressQuery
 * </pre>
 *
 * <p>原实现的一条 SQL join 了 catalog + identity + learning 三个域的表；
 * 拆开后每次页面渲染固定 4 次查询（权益 1 + 课程 1 + 视频 1 + 进度 1），
 * 换来的是学习空间不再依赖任何其他域的表结构。</p>
 */
@Repository
public class SeriesRepository {

    private final CatalogQuery catalogQuery;
    private final EntitlementQuery entitlementQuery;
    private final ProgressQuery progressQuery;

    public SeriesRepository(CatalogQuery catalogQuery,
                            EntitlementQuery entitlementQuery,
                            ProgressQuery progressQuery) {
        this.catalogQuery = catalogQuery;
        this.entitlementQuery = entitlementQuery;
        this.progressQuery = progressQuery;
    }

    public List<SeriesDtos.SeriesSummary> findForStudent(Long userId) {
        List<ActiveEntitlement> entitlements = entitlementQuery.activeEntitlements(userId);
        if (entitlements.isEmpty()) {
            return List.of();
        }
        List<Long> seriesIds = entitlements.stream().map(ActiveEntitlement::seriesId).toList();
        Map<Long, CatalogSeries> catalog = catalogQuery.findByIds(seriesIds).stream()
                .collect(Collectors.toMap(CatalogSeries::seriesId, value -> value, (a, b) -> a));
        Map<Long, List<Long>> videosBySeries = catalogQuery.findVideoRefs(seriesIds).stream()
                .collect(Collectors.groupingBy(VideoRef::seriesId,
                        Collectors.mapping(VideoRef::id, Collectors.toList())));
        Set<Long> allVideoIds = videosBySeries.values().stream()
                .flatMap(List::stream).collect(Collectors.toSet());
        Map<Long, ProgressSnapshot> progress = progressQuery.byVideoIds(userId, allVideoIds);

        List<SeriesDtos.SeriesSummary> result = new ArrayList<>();
        for (ActiveEntitlement entitlement : entitlements) {
            CatalogSeries series = catalog.get(entitlement.seriesId());
            if (series == null) {
                continue;
            }
            long completed = videosBySeries.getOrDefault(entitlement.seriesId(), List.of()).stream()
                    .map(progress::get)
                    .filter(snapshot -> snapshot != null && snapshot.completed())
                    .count();
            result.add(new SeriesDtos.SeriesSummary(
                    series.seriesId(), series.title(), series.summary(), series.teacherName(),
                    series.chapterCount(), series.videoCount(), completed));
        }
        return result;
    }

    public Optional<SeriesHeader> findAssignedSeries(Long userId, Long seriesId) {
        if (!entitlementQuery.hasActive(userId, seriesId)) {
            return Optional.empty();
        }
        return catalogQuery.findById(seriesId)
                .map(series -> new SeriesHeader(
                        series.seriesId(), series.title(), series.summary(), series.teacherName()));
    }

    public List<ChapterRow> findChapters(Long seriesId) {
        return catalogQuery.findChapters(seriesId).stream()
                .map(chapter -> new ChapterRow(chapter.id(), chapter.title()))
                .toList();
    }

    public List<VideoRow> findVideos(Long seriesId, Long userId) {
        List<VideoRef> videos = catalogQuery.findVideoRefs(List.of(seriesId));
        if (videos.isEmpty()) {
            return List.of();
        }
        Map<Long, ProgressSnapshot> progress = progressQuery.byVideoIds(
                userId, videos.stream().map(VideoRef::id).toList());
        return videos.stream()
                .map(video -> {
                    ProgressSnapshot snapshot = progress.get(video.id());
                    return new VideoRow(
                            video.id(), video.chapterId(), video.title(), video.durationSeconds(),
                            snapshot == null ? 0 : snapshot.positionSeconds(),
                            snapshot != null && snapshot.completed());
                })
                .toList();
    }

    /**
     * 可播放校验：权益 + 课程已发布 + 视频就绪，三者都要满足。
     *
     * <p>原实现只校验权益，导致课程下架后已购学员仍能拿到播放地址——本次一并修复。</p>
     */
    public Optional<SeriesDtos.PlayableVideo> findPlayableVideo(Long userId, Long videoId) {
        VideoContext video = catalogQuery.findVideo(videoId).orElse(null);
        if (video == null) {
            return Optional.empty();
        }
        if (!"PUBLISHED".equals(video.seriesStatus()) || !"READY".equals(video.mediaStatus())) {
            return Optional.empty();
        }
        if (!entitlementQuery.hasActive(userId, video.seriesId())) {
            return Optional.empty();
        }
        return Optional.of(new SeriesDtos.PlayableVideo(
                video.id(), video.seriesId(), video.title(), video.videoKey()));
    }

    public record SeriesHeader(Long id, String title, String summary, String teacherName) {
    }

    public record ChapterRow(Long id, String title) {
    }

    public record VideoRow(Long id, Long chapterId, String title, int durationSeconds,
                           int positionSeconds, boolean completed) {
    }
}
