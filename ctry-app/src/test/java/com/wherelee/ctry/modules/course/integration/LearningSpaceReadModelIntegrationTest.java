package com.wherelee.ctry.modules.course.integration;

import com.wherelee.ctry.modules.commerce.integration.CommerceIntegrationTestSupport;
import com.wherelee.ctry.modules.course.series.dto.SeriesDtos;
import com.wherelee.ctry.modules.course.series.SeriesRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 期证据测试：学习空间读模型从「跨三域一条 SQL」改成「契约拼装」后行为等价。
 *
 * <p>同时覆盖本轮修复的正确性问题：课程下架 / 视频未就绪后，已购学员不能再拿到播放地址。</p>
 */
class LearningSpaceReadModelIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private SeriesRepository seriesRepository;

    @Test
    @DisplayName("未购买时学习空间为空，详情与播放都拿不到")
    void spaceIsEmptyBeforePurchase() {
        assertThat(seriesRepository.findForStudent(fx.studentId())).isEmpty();
        assertThat(seriesRepository.findAssignedSeries(fx.studentId(), fx.seriesId())).isEmpty();
        assertThat(seriesRepository.findPlayableVideo(fx.studentId(), fx.videoId())).isEmpty();
    }

    @Test
    @DisplayName("购买后：列表含章节/视频计数，详情带进度，播放可取到视频元信息")
    void spaceShowsCountsProgressAndPlayableVideo() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        completePayment(orderNo);

        List<SeriesDtos.SeriesSummary> list = seriesRepository.findForStudent(fx.studentId());
        assertThat(list).hasSize(1);
        SeriesDtos.SeriesSummary summary = list.get(0);
        assertThat(summary.id()).isEqualTo(fx.seriesId());
        assertThat(summary.chapterCount()).isEqualTo(1);
        assertThat(summary.videoCount()).isEqualTo(1);
        assertThat(summary.completedVideoCount()).isZero();
        assertThat(summary.teacherName()).isEqualTo("集成测试老师");

        // 写入一条播放进度（第 30 秒、已完成）
        jdbc.update("""
                INSERT INTO t_learning_progress
                    (user_id, series_id, video_id, position_seconds, duration_seconds, completed)
                VALUES (?, ?, ?, 30, 60, 1)
                """, fx.studentId(), fx.seriesId(), fx.videoId());

        List<SeriesRepository.VideoRow> videos = seriesRepository.findVideos(fx.seriesId(), fx.studentId());
        assertThat(videos).hasSize(1);
        assertThat(videos.get(0).positionSeconds()).isEqualTo(30);
        assertThat(videos.get(0).completed()).isTrue();

        List<SeriesDtos.SeriesSummary> afterProgress = seriesRepository.findForStudent(fx.studentId());
        assertThat(afterProgress.get(0).completedVideoCount()).isEqualTo(1);

        var playable = seriesRepository.findPlayableVideo(fx.studentId(), fx.videoId());
        assertThat(playable).isPresent();
        assertThat(playable.orElseThrow().videoKey()).isEqualTo("it/video.mp4");
    }

    @Test
    @DisplayName("课程下架或视频未就绪时，已购学员也不能播放（本轮修复）")
    void unpublishedSeriesOrNotReadyVideoIsNotPlayable() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        completePayment(orderNo);
        assertThat(seriesRepository.findPlayableVideo(fx.studentId(), fx.videoId())).isPresent();

        jdbc.update("UPDATE t_series SET status = 'ARCHIVED' WHERE id = ?", fx.seriesId());
        assertThat(seriesRepository.findPlayableVideo(fx.studentId(), fx.videoId())).isEmpty();

        jdbc.update("UPDATE t_series SET status = 'PUBLISHED' WHERE id = ?", fx.seriesId());
        jdbc.update("UPDATE t_series_video SET media_status = 'PENDING' WHERE id = ?", fx.videoId());
        assertThat(seriesRepository.findPlayableVideo(fx.studentId(), fx.videoId())).isEmpty();
    }
}
