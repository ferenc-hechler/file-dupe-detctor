package com.filedupedetector.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a root path (local drive, external volume, or cloud storage folder) designated
 * by the user for scanning.
 */
public final class ScanLocation {

    public enum LocationType {
        LOCAL,
        REMOVABLE,
        CLOUD
    }

    private long id;
    private String displayName;
    private String rootPath;
    private LocationType type;
    private Instant lastScannedAt;
    private boolean available;

    public ScanLocation() {
        this.available = true;
    }

    public ScanLocation(long id, String displayName, String rootPath, LocationType type,
                         Instant lastScannedAt, boolean available) {
        this.id = id;
        this.displayName = Objects.requireNonNull(displayName, "displayName must not be null");
        this.rootPath = requireNonBlank(rootPath, "rootPath");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.lastScannedAt = lastScannedAt;
        this.available = available;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
        return value;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = requireNonBlank(rootPath, "rootPath");
    }

    public LocationType getType() {
        return type;
    }

    public void setType(LocationType type) {
        this.type = type;
    }

    public Instant getLastScannedAt() {
        return lastScannedAt;
    }

    public void setLastScannedAt(Instant lastScannedAt) {
        this.lastScannedAt = lastScannedAt;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScanLocation that)) return false;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ScanLocation{id=%d, displayName='%s', rootPath='%s', type=%s, available=%s}"
                .formatted(id, displayName, rootPath, type, available);
    }
}
