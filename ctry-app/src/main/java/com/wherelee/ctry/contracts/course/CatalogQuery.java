package com.wherelee.ctry.contracts.course;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 课程目录读侧契约。
 *
 * <p>交易 / 学习空间等模块要展示课程与视频元信息时只依赖这个接口，不直接查课程库表。</p>
 */
public interface CatalogQuery {

    /** 在售课程列表（已发布 + 已开售 + 价格有效）。 */
    List<CatalogSeries> findOnSale();

    /** 可购买校验：返回价格快照，未发布/未开售/价格无效时为空。 */
    Optional<PurchasableSeries> findPurchasable(Long seriesId);

    /** 按 id 批量取课程（不过滤发布/销售状态，学习空间需要展示已分配但已下架的课程）。 */
    List<CatalogSeries> findByIds(Collection<Long> seriesIds);

    Optional<CatalogSeries> findById(Long seriesId);

    List<ChapterRef> findChapters(Long seriesId);

    /** 批量取这些课程下的全部视频（按章节顺序、视频顺序）。 */
    List<VideoRef> findVideoRefs(Collection<Long> seriesIds);

    /** 已发布课程的下拉选项（管理端分配课程用）。 */
    List<SeriesOption> publishedOptions();

    /** 取单个视频的播放上下文（含所属课程状态，供播放鉴权用）。 */
    Optional<VideoContext> findVideo(Long videoId);

    record CatalogSeries(
            Long seriesId,
            String title,
            String summary,
            String teacherName,
            long chapterCount,
            long videoCount,
            long priceCents,
            String currency
    ) {
    }

    record PurchasableSeries(Long seriesId, String title, long priceCents, String currency) {
    }

    record ChapterRef(Long id, String title) {
    }

    record SeriesOption(Long id, String title) {
    }

    record VideoRef(Long id, Long seriesId, Long chapterId, String title,
                    int durationSeconds, String mediaStatus) {
    }

    record VideoContext(Long id, Long seriesId, String title, String videoKey,
                        int durationSeconds, String mediaStatus, String seriesStatus) {
    }
}
