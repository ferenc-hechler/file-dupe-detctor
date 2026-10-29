package com.filedupedetector.service;

import com.filedupedetector.model.ScanLocation;

/** Callback interface for {@link ScanService#scanAsync} progress/completion/error reporting. */
public interface ScanProgressListener {

    void onProgress(int filesProcessed, int totalEstimate);

    void onSessionComplete(long totalProcessed, long totalErrored, long totalMissing);

    void onLocationUnavailable(String errorMessage);
}
