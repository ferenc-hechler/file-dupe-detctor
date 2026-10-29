-- Embedded H2 schema for the Duplicate File Detector (data-model.md)
-- Idempotent: safe to run on every application startup.

CREATE TABLE IF NOT EXISTS folder_record (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                 VARCHAR(512)  NOT NULL,
    parent_folder_id     BIGINT        NULL,
    CONSTRAINT fk_folder_record_parent_folder FOREIGN KEY (parent_folder_id) REFERENCES folder_record (id)
);

CREATE TABLE IF NOT EXISTS file_record (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                 VARCHAR(256)  NOT NULL,
    parent_folder_id     BIGINT        NOT NULL,
    size_bytes           BIGINT        NOT NULL,
    sha1_hash            VARCHAR(64)   NULL,
    last_modified_at     TIMESTAMP     NOT NULL,
    CONSTRAINT fk_file_record_parent_folder FOREIGN KEY (parent_folder_id) REFERENCES folder_record (id)
);


CREATE TABLE IF NOT EXISTS scan_location (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_name    VARCHAR(512)  NOT NULL,
    root_path       VARCHAR(2048) NOT NULL,
    vol_name        VARCHAR(512)  NOT NULL DEFAULT '?',
    vol_type        VARCHAR(128)  NOT NULL DEFAULT '?',
    vol_size        BIGINT        NOT NULL DEFAULT -1,
    vol_vsn         VARCHAR(64)   NOT NULL DEFAULT '?',
    type            VARCHAR(16)   NOT NULL,
    root_folder_id    BIGINT      NULL,
    CONSTRAINT fk_scan_location_root_folder FOREIGN KEY (root_folder_id) REFERENCES folder_record (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_scan_location_display_name ON scan_location (display_name);

CREATE UNIQUE INDEX IF NOT EXISTS ux_file_record_parent_folder_name ON file_record (parent_folder_id, name);
CREATE INDEX IF NOT EXISTS ix_file_record_sha1_hash ON file_record (sha1_hash);

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