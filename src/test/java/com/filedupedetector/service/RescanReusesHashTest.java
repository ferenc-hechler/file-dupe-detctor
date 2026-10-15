package com.filedupedetector.service;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.model.ScanSession;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.JdbcScanSessionRepository;
import com.filedupedetector.scan.HashService;
import com.filedupedetector.scan.NioFileWalker;
import com.filedupedetector.scan.Sha1HashService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers User Story 2 acceptance scenario 1: re-scanning an unmodified folder reuses persisted
 * hashes instead of recomputing them (FR-006, SC-002).
 */
class RescanReusesHashTest {

    @Test
    void rescanWithoutChangesDoesNotRecomputeHashes(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path folderA = Files.createDirectories(tempDir.resolve("folderA"));
        Path folderB = Files.createDirectories(tempDir.resolve("folderB"));
        byte[] sharedContent = "duplicate content for rescan test".getBytes(StandardCharsets.UTF_8);
        Files.write(folderA.resolve("one.dat"), sharedContent);
        Files.write(folderB.resolve("two.dat"), sharedContent);

        Path dbPath = tempDir.resolve("test-db").resolve("store");
        AtomicInteger hashCallCount = new AtomicInteger();
        HashService countingHashService = new CountingHashService(new Sha1HashService(), hashCallCount);

        try (JdbcConnectionManager connectionManager = new JdbcConnectionManager(dbPath)) {
            var scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
            var fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
            var scanSessionRepository = new JdbcScanSessionRepository(connectionManager);

            ScanLocation locationA = scanLocationRepository.save(newLocation("A", folderA));
            ScanLocation locationB = scanLocationRepository.save(newLocation("B", folderB));

            var scanService = new ScanServiceImpl(new NioFileWalker(), countingHashService, new DuplicateMatcher(),
                    fileRecordRepository, scanLocationRepository, scanSessionRepository);

            runScanAndWait(scanService, List.of(locationA, locationB));
            int hashCallsAfterFirstScan = hashCallCount.get();
            assertThat(hashCallsAfterFirstScan).isEqualTo(2); // both files share a size -> both hashed once

            var duplicateQueryService = new DuplicateQueryServiceImpl(fileRecordRepository);
            List<DuplicateGroup> groupsAfterFirst = duplicateQueryService.getAllDuplicateGroups();
            assertThat(groupsAfterFirst).hasSize(1);
            String hashAfterFirst = groupsAfterFirst.get(0).getContentHash();

            // Re-scan without modifying any files.
            runScanAndWait(scanService, List.of(locationA, locationB));

            assertThat(hashCallCount.get())
                    .as("hash service should not be invoked again for unchanged files")
                    .isEqualTo(hashCallsAfterFirstScan);

            List<DuplicateGroup> groupsAfterRescan = duplicateQueryService.getAllDuplicateGroups();
            assertThat(groupsAfterRescan).hasSize(1);
            assertThat(groupsAfterRescan.get(0).getContentHash()).isEqualTo(hashAfterFirst);
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
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private ScanLocation newLocation(String name, Path path) {
        ScanLocation location = new ScanLocation();
        location.setDisplayName(name);
        location.setRootPathWithVolInfo(path.toAbsolutePath().toString());
        location.setType(LocationType.LOCAL);
        return location;
    }

    /** Wraps a real HashService but counts invocations, to assert reuse (no re-hash) on re-scan. */
    private record CountingHashService(HashService delegate, AtomicInteger counter) implements HashService {
        @Override
        public String computeHash(Path absolutePath) throws IOException {
            counter.incrementAndGet();
            return delegate.computeHash(absolutePath);
        }
    }
}
