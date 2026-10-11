package com.filedupedetector.service;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.ScanLocationRepository;

import java.util.List;

/**
 * Loads all persisted {@link ScanLocation}s and existing duplicate groups on application
 * startup (FR-005, FR-013, SC-004), so results are available without requiring a new scan.
 */
public final class AppBootstrap {

    private final ScanLocationRepository scanLocationRepository;
    private final DuplicateQueryService duplicateQueryService;

    public AppBootstrap(ScanLocationRepository scanLocationRepository, FileRecordRepository fileRecordRepository) {
        this.scanLocationRepository = scanLocationRepository;
        this.duplicateQueryService = new DuplicateQueryServiceImpl(fileRecordRepository);
    }

    public record StartupState(List<ScanLocation> locations, List<DuplicateGroup> duplicateGroups) {
    }

    /** Loads persisted state synchronously; intended to be fast enough for startup (SC-004: &lt;5s). */
    public StartupState loadStartupState() {
        List<ScanLocation> locations = scanLocationRepository.findAll();
        List<DuplicateGroup> duplicateGroups = duplicateQueryService.getAllDuplicateGroups();
        return new StartupState(locations, duplicateGroups);
    }
}
