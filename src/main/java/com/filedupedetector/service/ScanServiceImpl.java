package com.filedupedetector.service;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.FolderRecordRepository;
import com.filedupedetector.persistence.ScanLocationRepository;
import com.filedupedetector.scan.FileVisitCallback;
import com.filedupedetector.scan.FileWalker;
import com.filedupedetector.scan.HashService;
import com.filedupedetector.scan.InaccessibleReason;

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
    private final FileRecordRepository fileRecordRepository;
    private final ScanLocationRepository scanLocationRepository;
    private final FolderRecordRepository folderRecordRepository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scan-worker");
        t.setDaemon(true);
        return t;
    });


    public ScanServiceImpl(FileWalker fileWalker,
                            HashService hashService,
                            FileRecordRepository fileRecordRepository,
                            ScanLocationRepository scanLocationRepository,
                            FolderRecordRepository folderRecordRepository) {
        this.fileWalker = fileWalker;
        this.hashService = hashService;
        this.fileRecordRepository = fileRecordRepository;
        this.scanLocationRepository = scanLocationRepository;
        this.folderRecordRepository = folderRecordRepository;
    }

    @Override
    public void scanAsync(ScanLocation location, ScanProgressListener listener) {
        executor.submit(() -> {
            try {
                scanBlocking(location, listener);
            } catch (RuntimeException e) {
                // FR-014/resilience: never let an unexpected error silently hang the caller
                // (e.g. the GUI's "Scanning..." status) without any completion signal.
                log.error("Scan failed unexpectedly", e);
                listener.onLocationUnavailable(e.getMessage());
            }
        });
    }

    /** Synchronous scan, usable directly by the Phase 3 console harness (T024) and tests. */
    public void scanBlocking(ScanLocation location, ScanProgressListener listener) {

        // Phase 1: walk every location first, so the size pre-filter (DuplicateMatcher) can see
        // ALL candidate files across ALL locations at once - a file that is "unique" within its
        // own folder may still duplicate a same-size file in a different scan location.
        WalkedLocation walked = walkOneLocation(location);
        if (walked.unavailable()) {
            listener.onLocationUnavailable("could not access scan location " + location.getRootPath());
        }

        // Phase 2: hash candidates and persist, per location.
        int totalProcessed = 0;
        int totalErrored = 0;
        int totalMissing = 0;
        try {
            LocationScanResult result = persistOneLocation(walked, listener);
            totalProcessed += result.processed();
            totalErrored += result.errored();
            totalMissing += result.missing();
        } catch (RuntimeException e) {
            // A catastrophic failure persisting one location (e.g. DB unavailable mid-scan)
            // must not prevent other locations' already-gathered results from being reported
            // (FR-014). Surface it the same way an unavailable location would be.
            log.error("Failed to persist results for location {}: {}", walked.location().getRootPath(), e.getMessage(), e);
            listener.onLocationUnavailable(e.getMessage());
        }

        listener.onSessionComplete(totalProcessed, totalErrored, totalMissing);
    }

    private record WalkedLocation(ScanLocation location, Path root, boolean unavailable,
                                   List<Path> erroredPaths, List<Path> inaccessiblePaths) {
    }

    private record LocationScanResult(int processed, int errored, int missing) {
    }

    private WalkedLocation walkOneLocation(ScanLocation location) {
        Path root = Path.of(location.getRootPath());
        if (!java.nio.file.Files.exists(root)) {
            log.warn("Scan location unavailable: {}", location.getRootPath());
            return new WalkedLocation(location, root, true, List.of(), List.of());
        }

        List<Path> erroredPaths = new ArrayList<>();
        List<Path> inaccessiblePaths = new ArrayList<>();

        fileWalker.walk(root, new FileVisitCallback() {
            @Override
            public void onFileFound(Path absolutePath, long sizeBytes, FileTime lastModified) {
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

        return new WalkedLocation(location, root, false, erroredPaths, inaccessiblePaths);
    }

    private LocationScanResult persistOneLocation(WalkedLocation w, ScanProgressListener listener) {
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
            record.setLastSeenAt(Instant.now());

            if (hashCandidates.contains(sized)) {
                String reusableHash = findReusableHash(location.getId(), relativePath, record.getSizeBytes(),
                        record.getLastModifiedAt());
                if (reusableHash != null) {
                    // FR-006: unchanged size/mtime since last scan -> reuse persisted hash, skip re-hashing.
                    record.setContentHash(reusableHash);
                } else {
                    try {
                        // FR-007: new file, or size/mtime changed since last scan -> (re)compute hash.
                        record.setContentHash(hashService.computeHash(sized.path()));
                    } catch (IOException e) {
                        // Unreadable files are not persisted; dropping the path from the "seen" set
                        // also removes a stale record from an earlier scan.
                        log.debug("Failed to hash {}: {}", sized.path(), e.getMessage());
                        relativePathsSeen.remove(relativePath);
                        errored++;
                        processed++;
                        listener.onProgress(processed, w.files().size());
                        continue;
                    }
                }
            } else {
                // Unique size among this scan: no need to hash (research.md decision 4).
                record.setContentHash(null);
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

        // FR-008: any previously stored record for this location not seen in this walk (deleted,
        // unreadable or inaccessible files) is removed.
        int missing = fileRecordRepository.deleteMissing(location.getId(), relativePathsSeen);

        location.setLastScannedAt(Instant.now());
        scanLocationRepository.save(location);

        return new LocationScanResult(processed, errored, missing);
    }

    /**
     * Returns the previously persisted content hash if an existing record for this path has an
     * unchanged size and last-modified timestamp (FR-006); otherwise {@code null} to signal that
     * the hash must be (re)computed (FR-007).
     */
    private String findReusableHash(long scanLocationId, String relativePath, long currentSize,
                                     Instant currentLastModified) {
        return fileRecordRepository.findByLocationAndPath(scanLocationId, relativePath)
                .filter(existing -> existing.getContentHash() != null)
                .filter(existing -> existing.getSizeBytes() == currentSize)
                .filter(existing -> existing.getLastModifiedAt() != null
                        && existing.getLastModifiedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .equals(currentLastModified.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
                .map(FileRecord::getContentHash)
                .orElse(null);
    }
}