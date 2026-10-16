package com.filedupedetector.persistence;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.FileRecord;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for {@link FileRecord} persistence and duplicate-group derivation
 * (contracts/internal-module-contracts.md). Only files that are present and readable
 * are persisted.
 */
public interface FileRecordRepository {

    Optional<FileRecord> findByLocationAndPath(long scanLocationId, String relativePath);

    /** Insert or update, keyed by the unique {@code (scanLocationId, relativePath)} pair. */
    FileRecord upsert(FileRecord record);

    /**
     * Deletes any record of the given location whose relativePath is not in
     * {@code relativePathsSeen}, i.e. files that no longer exist (FR-008).
     *
     * @return the number of deleted records
     */
    int deleteMissing(long scanLocationId, Set<String> relativePathsSeen);

    /** Records with a non-null hash, grouped by (size, hash), having count > 1 (FR-003/FR-004). */
    List<DuplicateGroup> findDuplicateGroups();

    List<FileRecord> findByLocation(long scanLocationId);
}