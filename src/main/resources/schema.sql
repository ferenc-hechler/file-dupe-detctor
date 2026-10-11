-- Embedded H2 schema for the Duplicate File Detector (data-model.md)
-- Idempotent: safe to run on every application startup.

CREATE TABLE IF NOT EXISTS scan_location (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_name    VARCHAR(512)  NOT NULL,
    root_path       VARCHAR(2048) NOT NULL,
    type            VARCHAR(16)   NOT NULL,
    last_scanned_at TIMESTAMP     NULL,
    available       BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_scan_location_root_path ON scan_location (root_path);

CREATE TABLE IF NOT EXISTS file_record (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    scan_location_id     BIGINT        NOT NULL,
    relative_path        VARCHAR(4000) NOT NULL,
    size_bytes           BIGINT        NOT NULL,
    content_hash         VARCHAR(64)   NULL,
    last_modified_at     TIMESTAMP     NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    last_scan_session_id BIGINT        NULL,
    last_seen_at         TIMESTAMP     NOT NULL,
    CONSTRAINT fk_file_record_location FOREIGN KEY (scan_location_id) REFERENCES scan_location (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_file_record_location_path ON file_record (scan_location_id, relative_path);
CREATE INDEX IF NOT EXISTS ix_file_record_size_hash ON file_record (size_bytes, content_hash);

CREATE TABLE IF NOT EXISTS scan_session (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    started_at            TIMESTAMP   NOT NULL,
    ended_at              TIMESTAMP   NULL,
    files_processed       INT         NOT NULL DEFAULT 0,
    files_skipped_errored INT         NOT NULL DEFAULT 0,
    files_missing         INT         NOT NULL DEFAULT 0,
    outcome               VARCHAR(16) NULL
);

CREATE TABLE IF NOT EXISTS scan_session_location (
    scan_session_id  BIGINT NOT NULL,
    scan_location_id BIGINT NOT NULL,
    PRIMARY KEY (scan_session_id, scan_location_id),
    CONSTRAINT fk_ssl_session FOREIGN KEY (scan_session_id) REFERENCES scan_session (id),
    CONSTRAINT fk_ssl_location FOREIGN KEY (scan_location_id) REFERENCES scan_location (id)
);
