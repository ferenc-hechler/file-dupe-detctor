package com.filedupedetector.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted information about a single scanned file (FR-005).
 */
public final class FileRecord {

    private long id;
    private String name;
    private long parentFolderId;
    private long sizeBytes;
    private String sha1hash;
    private Instant lastModifiedAt;
 
    public FileRecord() {
    }

    public FileRecord(long id, long parentFolderId, String name, long sizeBytes,
                       String sha1hash, Instant lastModifiedAt, Instant lastSeenAt) {
        this.id = id;
        this.parentFolderId = parentFolderId;
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.sizeBytes = sizeBytes;
        this.sha1hash = sha1hash;
        this.lastModifiedAt = lastModifiedAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
		return name;
	}
    
    public void setName(String name) {
    	this.name = name;
    }
    
    public long getParentFolderId() {
		return parentFolderId;
	}
    
    public void setParentFolderId(long parentFolderId) {
    	this.parentFolderId = parentFolderId;
    }
    
    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha1hash() {
        return sha1hash;
    }

    public void setSha1hash(String sha1hash) {
        this.sha1hash = sha1hash;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(Instant lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
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
        return "FileRecord{id=%d, parentFolderId=%d, name='%s', sizeBytes=%d}"
                .formatted(id, parentFolderId, name, sizeBytes);
    }
}