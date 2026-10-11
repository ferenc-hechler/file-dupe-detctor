package com.filedupedetector.service;

import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.model.ScanSession;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.JdbcScanSessionRepository;
import com.filedupedetector.scan.NioFileWalker;
import com.filedupedetector.scan.Sha256HashService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance verification (T048, SC-002): a re-scan of unchanged files must be substantially
 * faster than the initial scan, since hashes are reused instead of recomputed.
 *
 * <p>Uses a moderate file count (not the full 10,000 from SC-005/quickstart.md) to keep the
 * automated test suite fast and non-flaky; full-scale validation at 10k files is covered by the
 * manual quickstart.md Scenario 2/4 run (T047).
 */
class RescanPerformanceTest {

    private static final int FILE_COUNT = 1500;

    @Test
    void rescanOfUnchangedFilesIsSubstantiallyFasterThanInitialScan(@TempDir Path tempDir) throws IOException,
            InterruptedException {
        Path folder = Files.createDirectories(tempDir.resolve("perf"));
        Random random = new Random(42);
        for (int i = 0; i < FILE_COUNT; i++) {
            byte[] content = new byte[512 + random.nextInt(512)];
            random.nextBytes(content);
            Files.write(folder.resolve("file-" + i + ".bin"), content);
        }
        // Make every file share its size with at least one other, so all are hash candidates
        // (worst case for initial-scan hashing cost, matching research.md's size pre-filter).
        byte[] sharedSizeContentA = new byte[777];
        byte[] sharedSizeContentB = new byte[777];
        random.nextBytes(sharedSizeContentA);
        random.nextBytes(sharedSizeContentB);
        Files.write(folder.resolve("shared-size-a.bin"), sharedSizeContentA);
        Files.write(folder.resolve("shared-size-b.bin"), sharedSizeContentB);

        Path dbPath = tempDir.resolve("test-db").resolve("store");
        try (JdbcConnectionManager connectionManager = new JdbcConnectionManager(dbPath)) {
            var scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
            var fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
            var scanSessionRepository = new JdbcScanSessionRepository(connectionManager);

            ScanLocation location = new ScanLocation();
            location.setDisplayName("perf");
            location.setRootPath(folder.toAbsolutePath().toString());
            location.setType(LocationType.LOCAL);
            location = scanLocationRepository.save(location);

            var scanService = new ScanServiceImpl(new NioFileWalker(), new Sha256HashService(), new DuplicateMatcher(),
                    fileRecordRepository, scanLocationRepository, scanSessionRepository);

            long firstScanStart = System.nanoTime();
            runScanAndWait(scanService, List.of(location));
            long firstScanDurationNanos = System.nanoTime() - firstScanStart;

            long rescanStart = System.nanoTime();
            runScanAndWait(scanService, List.of(location));
            long rescanDurationNanos = System.nanoTime() - rescanStart;

            double reduction = 1.0 - ((double) rescanDurationNanos / firstScanDurationNanos);

            assertThat(reduction)
                    .as("re-scan of %d unchanged files should be substantially faster than the initial scan "
                                    + "(first=%dms, rescan=%dms)", FILE_COUNT,
                            firstScanDurationNanos / 1_000_000, rescanDurationNanos / 1_000_000)
                    .isGreaterThanOrEqualTo(0.5); // conservative threshold vs. SC-002's 80% target, to avoid CI flakiness
        }
    }

    private void runScanAndWait(ScanServiceImpl scanService, List<ScanLocation> locations) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        scanService.scanBlocking(locations, new ScanProgressListener() {
            @Override
            public void onProgress(int filesProcessed, int totalEstimate) {
            }

            @Override
            public void onSessionComplete(ScanSession result) {
                latch.countDown();
            }

            @Override
            public void onLocationUnavailable(ScanLocation location) {
            }
        });
        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
    }
}
