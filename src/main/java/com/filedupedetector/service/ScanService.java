package com.filedupedetector.service;

import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanSession;

import java.util.List;

/** Orchestrates scanning of one or more {@link ScanLocation}s (contracts/internal-module-contracts.md). */
public interface ScanService {

    /**
     * Runs (or re-runs) a scan over the given scan locations. MUST run off the Swing EDT when
     * invoked from the GUI; progress/completion is delivered via the listener.
     */
    void scanAsync(List<ScanLocation> locations, ScanProgressListener listener);
}
