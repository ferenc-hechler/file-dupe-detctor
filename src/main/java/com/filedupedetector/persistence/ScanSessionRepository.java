package com.filedupedetector.persistence;

import com.filedupedetector.model.ScanSession;
import com.filedupedetector.model.ScanSession.ScanOutcome;

import java.util.List;

/**
 * Repository for {@link ScanSession} records (contracts/internal-module-contracts.md).
 */
public interface ScanSessionRepository {

    ScanSession start(List<Long> scanLocationIds);

    void complete(long sessionId, ScanOutcome outcome, int processed, int errored, int missing);
}
