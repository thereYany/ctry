package com.wherelee.ctry.modules.learning.integration;

import com.wherelee.ctry.modules.commerce.integration.CommerceIntegrationTestSupport;
import com.wherelee.ctry.modules.learning.learning.LearningProgressDtos;
import com.wherelee.ctry.modules.learning.learning.LearningProgressService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A 期证据测试：学习域不再 join 内容/权益表后，进度写入的鉴权语义保持正确。
 *
 * <p>覆盖本轮修复：进度与播放日志现在共用同一套权益口径（含有效期），
 * 不再出现"能写播放日志、写不了进度"的不一致。</p>
 */
class LearningAccessIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private LearningProgressService progressService;

    @Test
    @DisplayName("未购买时写入进度被拒绝")
    void progressRejectedWithoutEntitlement() {
        assertThatThrownBy(() -> progressService.update(fx.studentId(), fx.videoId(),
                new LearningProgressDtos.UpdateRequest(10, 60, false)))
                .hasMessageContaining("无权观看");
        assertThat(count("t_learning_progress", "user_id = ?", fx.studentId())).isZero();
    }

    @Test
    @DisplayName("购买后写入进度成功，且写入正确的 series_id")
    void progressAcceptedWithEntitlement() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        completePayment(orderNo);

        progressService.update(fx.studentId(), fx.videoId(),
                new LearningProgressDtos.UpdateRequest(30, 60, false));

        assertThat(count("t_learning_progress", "user_id = ? AND video_id = ?",
                fx.studentId(), fx.videoId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT series_id FROM t_learning_progress WHERE user_id = ? AND video_id = ?",
                Long.class, fx.studentId(), fx.videoId())).isEqualTo(fx.seriesId());
    }

    @Test
    @DisplayName("权益过期后写入进度被拒绝（与播放日志口径统一）")
    void progressRejectedWhenEntitlementExpired() {
        String orderNo = createOrder(nextRequestId()).orderNo();
        completePayment(orderNo);
        jdbc.update("""
                UPDATE t_student_series SET valid_from = DATE_SUB(NOW(3), INTERVAL 2 DAY),
                       valid_to = DATE_SUB(NOW(3), INTERVAL 1 DAY)
                WHERE user_id = ? AND series_id = ?
                """, fx.studentId(), fx.seriesId());

        assertThatThrownBy(() -> progressService.update(fx.studentId(), fx.videoId(),
                new LearningProgressDtos.UpdateRequest(10, 60, false)))
                .hasMessageContaining("无权观看");
    }
}
