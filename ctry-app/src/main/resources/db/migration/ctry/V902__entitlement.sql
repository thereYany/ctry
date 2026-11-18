-- 网课垂直域：权益（谁买了/被分配了哪门课，有效期多久）。

CREATE TABLE t_student_series
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    user_id      BIGINT       NOT NULL,
    series_id    BIGINT       NOT NULL,
    sort         INT          NOT NULL DEFAULT 0,
    source       VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    order_id     BIGINT       NULL,
    valid_from   DATETIME(3)  NULL,
    valid_to     DATETIME(3)  NULL,
    granted_by   BIGINT       NULL,
    granted_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at   DATETIME(3)  NULL,
    revoke_reason VARCHAR(200) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_series_source (user_id, series_id, source),
    KEY idx_student_series_user (user_id, sort),
    KEY idx_student_series_active (user_id, status, series_id),
    KEY idx_student_series_order (order_id),
    KEY idx_entitlement_tenant_user (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='课程权益';
