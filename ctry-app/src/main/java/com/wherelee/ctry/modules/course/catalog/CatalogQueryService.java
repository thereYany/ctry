package com.wherelee.ctry.modules.course.catalog;

import com.wherelee.ctry.contracts.course.CatalogQuery;
import com.wherelee.edu.modules.contracts.identity.AccountDirectory;
import com.wherelee.edu.modules.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 课程目录读模型：只读课程域自己的表，教师昵称走 identity 的账号目录接口。
 *
 * <p>这里刻意不做"一次 SQL 查完所有东西"：章节/视频与目录同属内容域，允许本域内 join；
 * 教师昵称属于身份域，走接口而不是 join。</p>
 */
@Service
public class CatalogQueryService implements CatalogQuery {

    private static final String SERIES_COLUMNS = """
            s.id,
            s.title,
            s.summary,
            s.teacher_id,
            s.price_cents,
            s.currency,
            (SELECT COUNT(*) FROM t_series_chapter c WHERE c.series_id = s.id) AS chapter_count,
            (SELECT COUNT(*) FROM t_series_video v
             JOIN t_series_chapter c2 ON c2.id = v.chapter_id
             WHERE c2.series_id = s.id) AS video_count
            """;

    private final JdbcTemplate jdbc;
    private final AccountDirectory accountDirectory;

    public CatalogQueryService(JdbcTemplate jdbc, AccountDirectory accountDirectory) {
        this.jdbc = jdbc;
        this.accountDirectory = accountDirectory;
    }

    @Override
    public List<CatalogSeries> findOnSale() {
        List<RawSeries> rows = jdbc.query("SELECT " + SERIES_COLUMNS + """
                FROM t_series s
                WHERE s.status = 'PUBLISHED'
                  AND s.sale_status = 'ON_SALE'
                  AND s.price_cents > 0
                  AND s.tenant_id = ?
                ORDER BY s.sort, s.id
                """, (rs, rowNum) -> raw(rs), TenantContext.current());
        return toCatalogSeries(rows);
    }

    @Override
    public Optional<PurchasableSeries> findPurchasable(Long seriesId) {
        return jdbc.query("""
                SELECT id, title, price_cents, currency
                FROM t_series
                WHERE id = ? AND status = 'PUBLISHED'
                  AND sale_status = 'ON_SALE' AND price_cents > 0
                """, (rs, rowNum) -> new PurchasableSeries(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getLong("price_cents"),
                rs.getString("currency")
        ), seriesId).stream().findFirst();
    }

    @Override
    public List<CatalogSeries> findByIds(Collection<Long> seriesIds) {
        List<Long> ids = distinct(seriesIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = placeholders(ids.size());
        List<RawSeries> rows = jdbc.query("SELECT " + SERIES_COLUMNS + """
                FROM t_series s
                WHERE s.id IN (""" + placeholders + """
                )
                  AND s.tenant_id = ?
                ORDER BY s.sort, s.id
                """, (rs, rowNum) -> raw(rs), concat(ids, TenantContext.current()));
        return toCatalogSeries(rows);
    }

    @Override
    public Optional<CatalogSeries> findById(Long seriesId) {
        return findByIds(List.of(seriesId)).stream().findFirst();
    }

    @Override
    public List<SeriesOption> publishedOptions() {
        return jdbc.query("""
                SELECT id, title FROM t_series WHERE status = 'PUBLISHED' ORDER BY sort, id
                """, (rs, rowNum) -> new SeriesOption(rs.getLong("id"), rs.getString("title")));
    }

    @Override
    public List<ChapterRef> findChapters(Long seriesId) {
        return jdbc.query("""
                SELECT id, title
                FROM t_series_chapter
                WHERE series_id = ?
                ORDER BY sort, id
                """, (rs, rowNum) -> new ChapterRef(rs.getLong("id"), rs.getString("title")), seriesId);
    }

    @Override
    public List<VideoRef> findVideoRefs(Collection<Long> seriesIds) {
        List<Long> ids = distinct(seriesIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT v.id, c.series_id, v.chapter_id, v.title, v.duration_seconds, v.media_status
                FROM t_series_video v
                JOIN t_series_chapter c ON c.id = v.chapter_id
                WHERE c.series_id IN (""" + placeholders(ids.size()) + """
                )
                  AND c.tenant_id = ?
                ORDER BY c.sort, c.id, v.sort, v.id
                """, (rs, rowNum) -> new VideoRef(
                rs.getLong("id"),
                rs.getLong("series_id"),
                rs.getLong("chapter_id"),
                rs.getString("title"),
                rs.getInt("duration_seconds"),
                rs.getString("media_status")
        ), concat(ids, TenantContext.current()));
    }

    @Override
    public Optional<VideoContext> findVideo(Long videoId) {
        return jdbc.query("""
                SELECT v.id, c.series_id, v.title, v.video_key, v.duration_seconds,
                       v.media_status, s.status AS series_status
                FROM t_series_video v
                JOIN t_series_chapter c ON c.id = v.chapter_id
                JOIN t_series s ON s.id = c.series_id
                WHERE v.id = ? AND s.tenant_id = ?
                """, (rs, rowNum) -> new VideoContext(
                rs.getLong("id"),
                rs.getLong("series_id"),
                rs.getString("title"),
                rs.getString("video_key"),
                rs.getInt("duration_seconds"),
                rs.getString("media_status"),
                rs.getString("series_status")
        ), videoId, TenantContext.current()).stream().findFirst();
    }

    private List<CatalogSeries> toCatalogSeries(List<RawSeries> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> teacherNames = accountDirectory.nicknames(
                rows.stream().map(RawSeries::teacherId).toList());
        return rows.stream()
                .map(row -> new CatalogSeries(
                        row.id(), row.title(), row.summary(),
                        teacherNames.getOrDefault(row.teacherId(), ""),
                        row.chapterCount(), row.videoCount(), row.priceCents(), row.currency()))
                .toList();
    }

    private static RawSeries raw(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RawSeries(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getLong("teacher_id"),
                rs.getLong("chapter_count"),
                rs.getLong("video_count"),
                rs.getLong("price_cents"),
                rs.getString("currency"));
    }

    private static List<Long> distinct(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null).distinct().toList();
    }

    private static Object[] concat(java.util.List<Long> ids, long tenantId) {
        Object[] args = new Object[ids.size() + 1];
        for (int i = 0; i < ids.size(); i++) {
            args[i] = ids.get(i);
        }
        args[ids.size()] = tenantId;
        return args;
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private record RawSeries(long id, String title, String summary, long teacherId,
                             long chapterCount, long videoCount, long priceCents, String currency) {
    }
}
