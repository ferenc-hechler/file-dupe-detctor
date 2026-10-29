package com.filedupedetector.service;

import java.util.List;

import com.filedupedetector.model.ScanLocation;

/** Orchestrates scanning of one or more {@link ScanLocation}s (contracts/internal-module-contracts.md). */
public interface ScanService {

    /**
     * Runs (or re-runs) a scan over the given scan locations. MUST run off the Swing EDT when
     * invoked from the GUI; progress/completion is delivered via the listener.
     */
    void scanAsync(ScanLocation location, ScanProgressListener listener);
}
