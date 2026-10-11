package com.filedupedetector.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted information about a single scanned file (FR-005).
 */
public final class FileRecord {

    public enum FileStatus {
        /** Seen and readable in the most recent scan. */
        PRESENT,
        /** Previously seen, not found in the latest scan of its location. */
        MISSING,
        /** Found but could not be read/hashed (permission/lock/placeholder). */
        ERRORED
    }

    private long id;
    private long scanLocationId;
    private String relativePath;
    private long sizeBytes;
    private String contentHash; // nullable
    private Instant lastModifiedAt;
    private FileStatus status;
    private Long lastScanSessionId; // nullable
    private Instant lastSeenAt;

    public FileRecord() {
    }

    public FileRecord(long id, long scanLocationId, String relativePath, long sizeBytes,
                       String contentHash, Instant lastModifiedAt, FileStatus status,
                       Long lastScanSessionId, Instant lastSeenAt) {
        this.id = id;
        this.scanLocationId = scanLocationId;
        this.relativePath = Objects.requireNonNull(relativePath, "relativePath must not be null");
        this.sizeBytes = sizeBytes;
        this.contentHash = contentHash;
        this.lastModifiedAt = lastModifiedAt;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.lastScanSessionId = lastScanSessionId;
        this.lastSeenAt = lastSeenAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getScanLocationId() {
        return scanLocationId;
    }

    public void setScanLocationId(long scanLocationId) {
        this.scanLocationId = scanLocationId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(Instant lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }

    public FileStatus getStatus() {
        return status;
    }

    public void setStatus(FileStatus status) {
        this.status = status;
    }

    public Long getLastScanSessionId() {
        return lastScanSessionId;
    }

    public void setLastScanSessionId(Long lastScanSessionId) {
        this.lastScanSessionId = lastScanSessionId;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    /** Whether this record is currently eligible for duplicate matching (FR-003, FR-012). */
    public boolean isEligibleForDuplicateMatching() {
        return status == FileStatus.PRESENT && contentHash != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileRecord that)) return false;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "FileRecord{id=%d, scanLocationId=%d, relativePath='%s', sizeBytes=%d, status=%s}"
                .formatted(id, scanLocationId, relativePath, sizeBytes, status);
    }
}
