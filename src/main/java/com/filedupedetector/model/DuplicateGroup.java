package com.filedupedetector.model;

import java.util.List;
import java.util.Objects;

/**
 * A set of two or more {@link FileRecord}s that share identical size and content hash
 * (FR-003, FR-004). This is a derived/computed view, not a persisted table (see data-model.md).
 */
public final class DuplicateGroup {

    private final long sizeBytes;
    private final String contentHash;
    private final List<FileRecord> members;

    public DuplicateGroup(long sizeBytes, String contentHash, List<FileRecord> members) {
        this.sizeBytes = sizeBytes;
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
        this.members = List.copyOf(members);
        if (this.members.size() < 2) {
            throw new IllegalArgumentException("A DuplicateGroup must have at least 2 members");
        }
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public List<FileRecord> getMembers() {
        return members;
    }

    public DuplicateGroupKey key() {
        return new DuplicateGroupKey(sizeBytes, contentHash);
    }

    /** Identity key for a duplicate group, usable for lookups (e.g. GUI selection -> members). */
    public record DuplicateGroupKey(long sizeBytes, String contentHash) {
    }

    @Override
    public String toString() {
        return "DuplicateGroup{sizeBytes=%d, contentHash='%s', members=%d}"
                .formatted(sizeBytes, contentHash, members.size());
    }
}
