package com.wherelee.ctry.modules.course.governance;

import com.wherelee.ctry.contracts.commerce.EntitlementQuery;
import com.wherelee.ctry.contracts.course.CourseGovernance;
import com.wherelee.base.modules.contracts.identity.AccountDirectory;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import com.wherelee.base.modules.platform.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CourseGovernanceService implements CourseGovernance {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 10_000;

    private final AdminSeriesMapper mapper;
    private final AccountDirectory accountDirectory;
    private final EntitlementQuery entitlementQuery;

    public CourseGovernanceService(AdminSeriesMapper mapper,
                                   AccountDirectory accountDirectory,
                                   EntitlementQuery entitlementQuery) {
        this.mapper = mapper;
        this.accountDirectory = accountDirectory;
        this.entitlementQuery = entitlementQuery;
    }

    @Override
    public PageResult<SeriesSummary> page(String keyword, String status, Long teacherId, int page, int size) {
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        // 关键字匹配教师名在身份域做，课程库只按 teacherId 列表过滤
        List<Long> teacherIds = keyword == null || keyword.isBlank()
                ? null : accountDirectory.searchIds(keyword, 200);
        long total = mapper.count(keyword, normalizedStatus, teacherId, teacherIds, TenantContext.current());
        if (total == 0) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        List<AdminSeriesVO> rows = mapper.findPage(keyword, normalizedStatus, teacherId, teacherIds,
                (safePage - 1) * safeSize, safeSize, TenantContext.current());
        enrich(rows);
        List<SeriesSummary> records = rows.stream().map(this::summary).toList();
        return new PageResult<>(records, total, safePage, safeSize);
    }

    @Override
    public SeriesDetail detail(Long seriesId) {
        AdminSeriesHeaderVO header = mapper.findHeader(seriesId, TenantContext.current());
        if (header == null) {
            throw BizException.notFound("系列不存在");
        }
        header.setTeacherName(accountDirectory.nickname(header.getTeacherId()));
        Map<Long, List<VideoItem>> videosByChapter = new LinkedHashMap<>();
        for (AdminSeriesVideoVO video : mapper.findVideos(seriesId, TenantContext.current())) {
            videosByChapter.computeIfAbsent(video.getChapterId(), key -> new ArrayList<>())
                    .add(new VideoItem(
                            video.getId(), video.getTitle(), video.getSort(),
                            video.getMediaStatus(), video.getDurationSeconds(), video.getFileSizeBytes()));
        }
        List<ChapterItem> chapters = mapper.findChapters(seriesId, TenantContext.current()).stream()
                .map(chapter -> new ChapterItem(
                        chapter.getId(), chapter.getTitle(), chapter.getSort(),
                        videosByChapter.getOrDefault(chapter.getId(), List.of())))
                .toList();
        return new SeriesDetail(
                header.getId(), header.getTitle(), header.getSummary(), header.getStatus(),
                header.getSaleStatus(), header.getPriceCents(), header.getCurrency(),
                header.getTeacherId(), header.getTeacherName(), header.getUpdatedAt(), chapters);
    }

    @Override
    @Transactional
    public void updateStatus(Long seriesId, String status) {
        AdminSeriesHeaderVO series = mapper.findHeader(seriesId, TenantContext.current());
        if (series == null) {
            throw BizException.notFound("系列不存在");
        }
        if (status.equals(series.getStatus())) {
            return;
        }
        if (!"DRAFT".equals(status) && !"ARCHIVED".equals(status)) {
            throw BizException.badRequest("管理员只能下架或归档系列");
        }
        mapper.updateStatus(seriesId, status, TenantContext.current());
    }

    @Override
    @Transactional
    public void updateSale(Long seriesId, String saleStatus, long priceCents) {
        AdminSeriesHeaderVO series = mapper.findHeader(seriesId, TenantContext.current());
        if (series == null) {
            throw BizException.notFound("系列不存在");
        }
        if ("ON_SALE".equals(saleStatus)) {
            if (!"PUBLISHED".equals(series.getStatus())) {
                throw BizException.badRequest("只有已发布系列才能开启销售");
            }
            if (priceCents <= 0) {
                throw BizException.badRequest("销售价格必须大于 0");
            }
        }
        if (priceCents > 100_000_00L) {
            throw BizException.badRequest("课程价格过高");
        }
        mapper.updateSale(seriesId, saleStatus, priceCents, TenantContext.current());
    }

    /** 教师名与学员数来自身份域/权益域契约，课程库不再 join 它们。 */
    private void enrich(List<AdminSeriesVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, String> names = accountDirectory.nicknames(
                rows.stream().map(AdminSeriesVO::getTeacherId).toList());
        Map<Long, Integer> students = entitlementQuery.activeStudentCounts(
                rows.stream().map(AdminSeriesVO::getId).toList());
        for (AdminSeriesVO row : rows) {
            row.setTeacherName(names.getOrDefault(row.getTeacherId(), ""));
            row.setAssignedStudentCount((long) students.getOrDefault(row.getId(), 0));
        }
    }

    private SeriesSummary summary(AdminSeriesVO row) {
        return new SeriesSummary(
                row.getId(), row.getTitle(), row.getSummary(), row.getStatus(),
                row.getSaleStatus(), row.getPriceCents(), row.getCurrency(),
                row.getTeacherId(), row.getTeacherName(), row.getChapterCount(),
                row.getVideoCount(), row.getAssignedStudentCount(),
                row.getPublishedAt(), row.getUpdatedAt());
    }
}