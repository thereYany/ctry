-- 网课垂直域：学习进度、播放日志与统计视图。

CREATE TABLE t_learning_progress
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT      NOT NULL DEFAULT 0,
    user_id          BIGINT      NOT NULL,
    series_id        BIGINT      NOT NULL,
    video_id         BIGINT      NOT NULL,
    position_seconds INT         NOT NULL DEFAULT 0,
    duration_seconds INT         NOT NULL DEFAULT 0,
    completed        TINYINT(1)  NOT NULL DEFAULT 0,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                         ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_progress_user_video (user_id, video_id),
    KEY idx_learning_progress_user_series (user_id, series_id, updated_at),
    KEY idx_progress_tenant_user (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='学员学习进度';

CREATE TABLE t_playback_log
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    user_id          BIGINT       NOT NULL,
    video_id         BIGINT       NULL,
    series_id        BIGINT       NULL,
    source           VARCHAR(20)  NOT NULL,
    event_type       VARCHAR(30)  NOT NULL,
    position_seconds INT          NULL,
    duration_seconds INT          NULL,
    cache_hit        TINYINT(1)   NULL,
    message          VARCHAR(500) NULL,
    user_agent       VARCHAR(300) NULL,
    session_id       VARCHAR(64)  NULL,
    trace_id         VARCHAR(64)  NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_playback_user_time (user_id, created_at),
    KEY idx_playback_video_time (video_id, created_at),
    KEY idx_playback_event_time (event_type, created_at),
    KEY idx_playback_trace (trace_id),
    KEY idx_playback_tenant_created (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='播放行为与错误日志';

CREATE OR REPLACE VIEW v_playback_daily AS
SELECT DATE(created_at) AS day,
       source,
       event_type,
       video_id,
       cache_hit,
       COUNT(*)         AS event_count
FROM t_playback_log
GROUP BY DATE(created_at), source, event_type, video_id, cache_hit;

CREATE OR REPLACE VIEW v_playback_url_cache_daily AS
SELECT DATE(created_at) AS day,
       SUM(CASE WHEN cache_hit = 1 THEN 1 ELSE 0 END) AS cache_hits,
       SUM(CASE WHEN cache_hit = 0 THEN 1 ELSE 0 END) AS cache_misses,
       COUNT(*) AS requests
FROM t_playback_log
WHERE source = 'BACKEND'
  AND event_type = 'URL_REQUEST'
GROUP BY DATE(created_at);
