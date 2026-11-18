-- 网课垂直域：课程内容（系列/章节/视频）+ 销售定价字段。
-- 内核只负责账号/支付/审计；课程数据完全归本仓库所有。

CREATE TABLE t_series
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    teacher_id      BIGINT       NOT NULL,
    title           VARCHAR(100) NOT NULL,
    summary         VARCHAR(300) NULL,
    sort            INT          NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    published_at    DATETIME(3)  NULL,
    sale_status     VARCHAR(20)  NOT NULL DEFAULT 'OFF_SALE',
    price_cents     BIGINT       NOT NULL DEFAULT 0,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    sale_updated_at DATETIME(3)  NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_series_teacher (teacher_id, sort),
    KEY idx_series_teacher_status (teacher_id, status, sort),
    KEY idx_series_sale (sale_status, status, price_cents),
    KEY idx_series_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='课程系列';

CREATE TABLE t_series_chapter
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT       NOT NULL DEFAULT 0,
    series_id  BIGINT       NOT NULL,
    title      VARCHAR(100) NOT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                   ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_chapter_series (series_id, sort),
    KEY idx_chapter_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='系列章节';

CREATE TABLE t_series_video
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    chapter_id       BIGINT       NOT NULL,
    title            VARCHAR(100) NOT NULL,
    sort             INT          NOT NULL DEFAULT 0,
    video_key        VARCHAR(255) NULL,
    media_status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    original_filename VARCHAR(255) NULL,
    file_size_bytes  BIGINT       NULL,
    content_type     VARCHAR(100) NULL,
    pending_object_key VARCHAR(255) NULL,
    uploaded_at      DATETIME(3)  NULL,
    duration_seconds INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                         ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_video_chapter (chapter_id, sort),
    KEY idx_video_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='系列视频';
