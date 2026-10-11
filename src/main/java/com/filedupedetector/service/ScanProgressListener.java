package com.filedupedetector.service;

import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanSession;

/** Callback interface for {@link ScanService#scanAsync} progress/completion/error reporting. */
public interface ScanProgressListener {

    void onProgress(int filesProcessed, int totalEstimate);

    void onSessionComplete(ScanSession result);

    /** Edge case: a scanned location becomes unavailable/disconnected mid-scan. */
    void onLocationUnavailable(ScanLocation location);
}
