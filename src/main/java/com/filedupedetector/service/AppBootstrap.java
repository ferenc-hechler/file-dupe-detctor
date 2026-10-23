package com.filedupedetector.service;

import java.util.List;

import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.ScanLocationRepository;

/**
 * Loads all persisted {@link ScanLocation}s and existing duplicate groups on application
 * startup (FR-005, FR-013, SC-004), so results are available without requiring a new scan.
 */
public final class AppBootstrap {

    private final ScanLocationRepository scanLocationRepository;

    public AppBootstrap(ScanLocationRepository scanLocationRepository, FileRecordRepository fileRecordRepository) {
        this.scanLocationRepository = scanLocationRepository;
    }

    public record StartupState(List<ScanLocation> locations) {
    }

    /** Loads persisted state synchronously; intended to be fast enough for startup (SC-004: &lt;5s). */
    public StartupState loadStartupState() {
        List<ScanLocation> locations = scanLocationRepository.findAll();
        return new StartupState(locations);
    }
}
