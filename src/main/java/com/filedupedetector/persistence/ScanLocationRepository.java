package com.filedupedetector.persistence;

import com.filedupedetector.model.ScanLocation;

import java.util.List;

/**
 * Repository for {@link ScanLocation} records (contracts/internal-module-contracts.md).
 */
public interface ScanLocationRepository {

    ScanLocation save(ScanLocation location);

    List<ScanLocation> findAll();

    void markAvailability(long scanLocationId, boolean available);
}
