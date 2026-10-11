package com.filedupedetector.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A record of a single scan run (FR-012 skipped/errored reporting, FR-014 resilience).
 */
public final class ScanSession {

    public enum ScanOutcome {
        COMPLETED,
        INTERRUPTED
    }

    private long id;
    private Instant startedAt;
    private Instant endedAt; // nullable while in progress
    private final List<Long> scanLocationIds = new ArrayList<>();
    private int filesProcessed;
    private int filesSkippedErrored;
    private int filesMissing;
    private ScanOutcome outcome;

    public ScanSession() {
    }

    public ScanSession(long id, Instant startedAt, Instant endedAt, List<Long> scanLocationIds,
                        int filesProcessed, int filesSkippedErrored, int filesMissing,
                        ScanOutcome outcome) {
        this.id = id;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.endedAt = endedAt;
        if (endedAt != null && endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must be >= startedAt");
        }
        this.scanLocationIds.addAll(scanLocationIds);
        this.filesProcessed = filesProcessed;
        this.filesSkippedErrored = filesSkippedErrored;
        this.filesMissing = filesMissing;
        this.outcome = outcome;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public List<Long> getScanLocationIds() {
        return Collections.unmodifiableList(scanLocationIds);
    }

    public void setScanLocationIds(List<Long> ids) {
        scanLocationIds.clear();
        scanLocationIds.addAll(ids);
    }

    public int getFilesProcessed() {
        return filesProcessed;
    }

    public void setFilesProcessed(int filesProcessed) {
        this.filesProcessed = filesProcessed;
    }

    public int getFilesSkippedErrored() {
        return filesSkippedErrored;
    }

    public void setFilesSkippedErrored(int filesSkippedErrored) {
        this.filesSkippedErrored = filesSkippedErrored;
    }

    public int getFilesMissing() {
        return filesMissing;
    }

    public void setFilesMissing(int filesMissing) {
        this.filesMissing = filesMissing;
    }

    public ScanOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(ScanOutcome outcome) {
        this.outcome = outcome;
    }

    @Override
    public String toString() {
        return "ScanSession{id=%d, startedAt=%s, endedAt=%s, outcome=%s, processed=%d, skippedErrored=%d, missing=%d}"
                .formatted(id, startedAt, endedAt, outcome, filesProcessed, filesSkippedErrored, filesMissing);
    }
}
