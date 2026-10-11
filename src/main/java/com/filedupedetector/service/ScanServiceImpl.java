package com.filedupedetector.service;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FileRecord.FileStatus;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.ScanLocationRepository;
import com.filedupedetector.scan.FileVisitCallback;
import com.filedupedetector.scan.FileWalker;
import com.filedupedetector.scan.HashService;
import com.filedupedetector.scan.InaccessibleReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates {@link FileWalker} + {@link DuplicateMatcher} size pre-filter +
 * {@link HashService} + {@link FileRecordRepository} persistence to detect duplicates
 * (FR-001..FR-004, FR-012). Runs on a background thread so callers (e.g. the GUI) are
 * never blocked.
 *
 * <p>Phase 3 (User Story 1) scope: initial/first-run scanning and duplicate persistence.
 * Phase 4 (User Story 2) extends this class with change-detection reuse, missing-file
 * marking, and scan-session bookkeeping (see methods marked accordingly below).
 */
public final class ScanServiceImpl implements ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanServiceImpl.class);

    private final FileWalker fileWalker;
    private final HashService hashService;
    private final DuplicateMatcher duplicateMatcher;
    private final FileRecordRepository fileRecordRepository;
    private final ScanLocationRepository scanLocationRepository;
    private final com.filedupedetector.persistence.ScanSessionRepository scanSessionRepository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scan-worker");
        t.setDaemon(true);
        return t;
    });

    public ScanServiceImpl(FileWalker fileWalker,
                            HashService hashService,
                            DuplicateMatcher duplicateMatcher,
                            FileRecordRepository fileRecordRepository,
                            ScanLocationRepository scanLocationRepository,
                            com.filedupedetector.persistence.ScanSessionRepository scanSessionRepository) {
        this.fileWalker = fileWalker;
        this.hashService = hashService;
        this.duplicateMatcher = duplicateMatcher;
        this.fileRecordRepository = fileRecordRepository;
        this.scanLocationRepository = scanLocationRepository;
        this.scanSessionRepository = scanSessionRepository;
    }

    @Override
    public void scanAsync(List<ScanLocation> locations, ScanProgressListener listener) {
        executor.submit(() -> {
            try {
                scanBlocking(locations, listener);
            } catch (RuntimeException e) {
                // FR-014/resilience: never let an unexpected error silently hang the caller
                // (e.g. the GUI's "Scanning..." status) without any completion signal.
                log.error("Scan failed unexpectedly", e);
                com.filedupedetector.model.ScanSession failed = new com.filedupedetector.model.ScanSession();
                failed.setStartedAt(Instant.now());
                failed.setEndedAt(Instant.now());
                failed.setOutcome(com.filedupedetector.model.ScanSession.ScanOutcome.INTERRUPTED);
                listener.onSessionComplete(failed);
            }
        });
    }

    /** Synchronous scan, usable directly by the Phase 3 console harness (T024) and tests. */
    public void scanBlocking(List<ScanLocation> locations, ScanProgressListener listener) {
        List<Long> locationIds = locations.stream().map(ScanLocation::getId).toList();
        com.filedupedetector.model.ScanSession session = scanSessionRepository.start(locationIds);

        // Phase 1: walk every location first, so the size pre-filter (DuplicateMatcher) can see
        // ALL candidate files across ALL locations at once - a file that is "unique" within its
        // own folder may still duplicate a same-size file in a different scan location.
        List<WalkedLocation> walked = new ArrayList<>();
        boolean anyInterrupted = false;
        for (ScanLocation location : locations) {
            WalkedLocation w = walkOneLocation(location);
            walked.add(w);
            if (w.unavailable()) {
                anyInterrupted = true;
                listener.onLocationUnavailable(location);
            }
        }

        List<DuplicateMatcher.SizedPath> allFilesAcrossLocations = walked.stream()
                .flatMap(w -> w.files().stream())
                .toList();
        Set<DuplicateMatcher.SizedPath> hashCandidates =
                new HashSet<>(duplicateMatcher.filterHashCandidates(allFilesAcrossLocations));

        // Phase 2: hash candidates and persist, per location.
        int totalProcessed = 0;
        int totalErrored = 0;
        int totalMissing = 0;
        for (WalkedLocation w : walked) {
            if (w.unavailable()) {
                continue;
            }
            try {
                LocationScanResult result = persistOneLocation(w, hashCandidates, listener, session.getId());
                totalProcessed += result.processed();
                totalErrored += result.errored();
                totalMissing += result.missing();
            } catch (RuntimeException e) {
                // A catastrophic failure persisting one location (e.g. DB unavailable mid-scan)
                // must not prevent other locations' already-gathered results from being reported
                // (FR-014). Surface it the same way an unavailable location would be.
                log.error("Failed to persist results for location {}: {}", w.location().getRootPath(), e.getMessage(), e);
                anyInterrupted = true;
                listener.onLocationUnavailable(w.location());
            }
        }

        var outcome = anyInterrupted
                ? com.filedupedetector.model.ScanSession.ScanOutcome.INTERRUPTED
                : com.filedupedetector.model.ScanSession.ScanOutcome.COMPLETED;
        scanSessionRepository.complete(session.getId(), outcome, totalProcessed, totalErrored, totalMissing);

        session.setEndedAt(Instant.now());
        session.setOutcome(outcome);
        session.setFilesProcessed(totalProcessed);
        session.setFilesSkippedErrored(totalErrored);
        session.setFilesMissing(totalMissing);
        listener.onSessionComplete(session);
    }

    private record WalkedLocation(ScanLocation location, Path root, boolean unavailable,
                                   List<DuplicateMatcher.SizedPath> files,
                                   List<Path> erroredPaths, List<Path> inaccessiblePaths) {
    }

    private record LocationScanResult(int processed, int errored, int missing) {
    }

    private WalkedLocation walkOneLocation(ScanLocation location) {
        Path root = Path.of(location.getRootPath());
        if (!java.nio.file.Files.exists(root)) {
            log.warn("Scan location unavailable: {}", location.getRootPath());
            scanLocationRepository.markAvailability(location.getId(), false);
            return new WalkedLocation(location, root, true, List.of(), List.of(), List.of());
        }
        scanLocationRepository.markAvailability(location.getId(), true);

        List<DuplicateMatcher.SizedPath> allFiles = new ArrayList<>();
        List<Path> erroredPaths = new ArrayList<>();
        List<Path> inaccessiblePaths = new ArrayList<>();

        fileWalker.walk(root, new FileVisitCallback() {
            @Override
            public void onFileFound(Path absolutePath, long sizeBytes, FileTime lastModified) {
                allFiles.add(new DuplicateMatcher.SizedPath(absolutePath, sizeBytes));
            }

            @Override
            public void onError(Path absolutePath, IOException error) {
                log.debug("Error reading {}: {}", absolutePath, error.getMessage());
                erroredPaths.add(absolutePath);
            }

            @Override
            public void onInaccessible(Path absolutePath, InaccessibleReason reason) {
                log.debug("Inaccessible ({}) path: {}", reason, absolutePath);
                inaccessiblePaths.add(absolutePath);
            }
        });

        return new WalkedLocation(location, root, false, allFiles, erroredPaths, inaccessiblePaths);
    }

    private LocationScanResult persistOneLocation(WalkedLocation w, Set<DuplicateMatcher.SizedPath> hashCandidates,
                                                   ScanProgressListener listener, long sessionId) {
        ScanLocation location = w.location();
        Path root = w.root();
        Set<String> relativePathsSeen = new HashSet<>();
        int processed = 0;
        int errored = w.erroredPaths().size() + w.inaccessiblePaths().size();

        for (DuplicateMatcher.SizedPath sized : w.files()) {
            String relativePath = root.relativize(sized.path()).toString();
            relativePathsSeen.add(relativePath);

            FileRecord record = new FileRecord();
            record.setScanLocationId(location.getId());
            record.setRelativePath(relativePath);
            record.setSizeBytes(sized.sizeBytes());
            try {
                // Truncate to millisecond precision: DB TIMESTAMP round-tripping and differing
                // filesystem mtime resolutions can otherwise cause false "changed" detections
                // between an in-memory Instant and the value reloaded from persistence.
                record.setLastModifiedAt(java.nio.file.Files.getLastModifiedTime(sized.path()).toInstant()
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            } catch (IOException e) {
                record.setLastModifiedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            }
            record.setLastScanSessionId(sessionId);
            record.setLastSeenAt(Instant.now());

            if (hashCandidates.contains(sized)) {
                String reusableHash = findReusableHash(location.getId(), relativePath, record.getSizeBytes(),
                        record.getLastModifiedAt());
                if (reusableHash != null) {
                    // FR-006: unchanged size/mtime since last scan -> reuse persisted hash, skip re-hashing.
                    record.setContentHash(reusableHash);
                    record.setStatus(FileStatus.PRESENT);
                } else {
                    try {
                        // FR-007: new file, or size/mtime changed since last scan -> (re)compute hash.
                        String hash = hashService.computeHash(sized.path());
                        record.setContentHash(hash);
                        record.setStatus(FileStatus.PRESENT);
                    } catch (IOException e) {
                        log.debug("Failed to hash {}: {}", sized.path(), e.getMessage());
                        record.setContentHash(null);
                        record.setStatus(FileStatus.ERRORED);
                        errored++;
                    }
                }
            } else {
                // Unique size among this scan: no need to hash (research.md decision 4).
                record.setContentHash(null);
                record.setStatus(FileStatus.PRESENT);
            }

            try {
                fileRecordRepository.upsert(record);
            } catch (RuntimeException e) {
                // Persistence failure for a single file must not abort the rest of the scan
                // (FR-014 resilience); count it as errored and keep going.
                log.warn("Failed to persist FileRecord for {}: {}", relativePath, e.getMessage());
                errored++;
            }
            processed++;
            listener.onProgress(processed, w.files().size());
        }
        // Errored/inaccessible paths are recorded as ERRORED FileRecords too, so FR-012 reporting
        // (and GUI skipped/errored counts) can query them uniformly via the repository.
        for (Path badPath : w.erroredPaths()) {
            persistErroredPlaceholder(location, root, badPath, sessionId, relativePathsSeen);
        }
        for (Path badPath : w.inaccessiblePaths()) {
            persistErroredPlaceholder(location, root, badPath, sessionId, relativePathsSeen);
        }

        // FR-008: any previously PRESENT record for this location not seen in this walk is now MISSING.
        int missing = fileRecordRepository.markMissing(location.getId(), relativePathsSeen, sessionId);

        location.setLastScannedAt(Instant.now());
        scanLocationRepository.save(location);

        return new LocationScanResult(processed, errored, missing);
    }

    /**
     * Returns the previously persisted content hash if an existing {@code PRESENT} record for
     * this path has an unchanged size and last-modified timestamp (FR-006); otherwise {@code null}
     * to signal that the hash must be (re)computed (FR-007). A record that was previously
     * {@code MISSING} is never trusted for reuse, since its hash cannot be trusted across an
     * absence (data-model.md FileRecord state transitions).
     */
    private String findReusableHash(long scanLocationId, String relativePath, long currentSize,
                                     Instant currentLastModified) {
        return fileRecordRepository.findByLocationAndPath(scanLocationId, relativePath)
                .filter(existing -> existing.getStatus() == FileStatus.PRESENT)
                .filter(existing -> existing.getContentHash() != null)
                .filter(existing -> existing.getSizeBytes() == currentSize)
                .filter(existing -> existing.getLastModifiedAt() != null
                        && existing.getLastModifiedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .equals(currentLastModified.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
                .map(FileRecord::getContentHash)
                .orElse(null);
    }



    private void persistErroredPlaceholder(ScanLocation location, Path root, Path badPath, long sessionId,
                                            Set<String> relativePathsSeen) {
        try {
            String relativePath = root.relativize(badPath).toString();
            relativePathsSeen.add(relativePath);
            FileRecord record = fileRecordRepository
                    .findByLocationAndPath(location.getId(), relativePath)
                    .orElseGet(FileRecord::new);
            record.setScanLocationId(location.getId());
            record.setRelativePath(relativePath);
            record.setStatus(FileStatus.ERRORED);
            record.setLastScanSessionId(sessionId);
            record.setLastSeenAt(Instant.now());
            if (record.getLastModifiedAt() == null) {
                record.setLastModifiedAt(Instant.now());
            }
            fileRecordRepository.upsert(record);
        } catch (RuntimeException e) {
            log.warn("Could not persist errored placeholder for {}: {}", badPath, e.getMessage());
        }
    }
}
