package com.wherelee.ctry.modules.learning.learning;

import com.wherelee.ctry.contracts.commerce.EntitlementQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery;
import com.wherelee.ctry.contracts.course.CatalogQuery.VideoContext;
import com.wherelee.edu.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningProgressServiceTest {

    @Mock
    private LearningProgressRepository repository;
    @Mock
    private CatalogQuery catalogQuery;
    @Mock
    private EntitlementQuery entitlementQuery;

    private LearningProgressService service;

    @BeforeEach
    void setUp() {
        service = new LearningProgressService(repository, catalogQuery, entitlementQuery);
    }

    @Test
    void rejectsVideoNotAssignedToStudent() {
        when(catalogQuery.findVideo(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(1L, 10L,
                new LearningProgressDtos.UpdateRequest(30, 181, false)))
                .isInstanceOf(BizException.class)
                .hasMessage("视频不存在或无权观看");

        verify(repository, never()).upsert(1L, 1L, 10L, 30, 181, false);
    }

    @Test
    void clampsPositionAndCompletesNearEnd() {
        when(catalogQuery.findVideo(10L)).thenReturn(Optional.of(
                new VideoContext(10L, 1L, "视频", "k/1.mp4", 181, "READY", "PUBLISHED")));
        when(entitlementQuery.hasActive(1L, 1L)).thenReturn(true);

        service.update(1L, 10L, new LearningProgressDtos.UpdateRequest(200, 181, false));

        verify(repository).upsert(1L, 1L, 10L, 181, 181, true);
    }

    @Test
    void usesCatalogDurationWhenClientOmitsIt() {
        when(catalogQuery.findVideo(10L)).thenReturn(Optional.of(
                new VideoContext(10L, 1L, "视频", "k/1.mp4", 181, "READY", "PUBLISHED")));
        when(entitlementQuery.hasActive(1L, 1L)).thenReturn(true);

        service.update(1L, 10L, new LearningProgressDtos.UpdateRequest(30, 0, false));

        verify(repository).upsert(1L, 1L, 10L, 30, 181, false);
    }

    @Test
    void explicitCompletionMovesPositionToEnd() {
        when(catalogQuery.findVideo(10L)).thenReturn(Optional.of(
                new VideoContext(10L, 1L, "视频", "k/1.mp4", 181, "READY", "PUBLISHED")));
        when(entitlementQuery.hasActive(1L, 1L)).thenReturn(true);

        service.update(1L, 10L, new LearningProgressDtos.UpdateRequest(30, 181, true));

        verify(repository).upsert(1L, 1L, 10L, 181, 181, true);
    }
}
