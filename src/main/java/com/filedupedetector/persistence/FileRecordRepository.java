package com.filedupedetector.persistence;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.FileRecord;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for {@link FileRecord} persistence and duplicate-group derivation
 * (contracts/internal-module-contracts.md).
 */
public interface FileRecordRepository {

    Optional<FileRecord> findByLocationAndPath(long scanLocationId, String relativePath);

    /** Insert or update, keyed by the unique {@code (scanLocationId, relativePath)} pair. */
    FileRecord upsert(FileRecord record);

    /**
     * Marks any previously {@code PRESENT} record for the given location whose relativePath
     * is not in {@code relativePathsSeen} as {@code MISSING} (FR-008).
     *
     * @return the number of records newly marked as {@code MISSING}
     */
    int markMissing(long scanLocationId, Set<String> relativePathsSeen, long scanSessionId);

    /** PRESENT + non-null hash, grouped by (size, hash), having count > 1 (FR-003/FR-004). */
    List<DuplicateGroup> findDuplicateGroups();

    List<FileRecord> findErroredBySession(long scanSessionId);

    List<FileRecord> findByLocation(long scanLocationId);
}
