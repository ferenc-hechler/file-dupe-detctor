package com.filedupedetector.service;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.model.ScanSession;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.JdbcScanSessionRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers User Story 2 acceptance scenario 2: modifying a file causes its hash to be
 * recomputed on re-scan, and duplicate grouping reflects the change (FR-007).
 */
class RescanDetectsChangesTest {

    @Test
    void modifiedFileIsRemovedFromDuplicateGroupAfterRescan(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path folderA = Files.createDirectories(tempDir.resolve("folderA"));
        Path folderB = Files.createDirectories(tempDir.resolve("folderB"));
        byte[] sharedContent = "original shared content".getBytes(StandardCharsets.UTF_8);
        Path fileA = folderA.resolve("one.dat");
        Path fileB = folderB.resolve("two.dat");
        Files.write(fileA, sharedContent);
        Files.write(fileB, sharedContent);

        Path dbPath = tempDir.resolve("test-db").resolve("store");
        try (JdbcConnectionManager connectionManager = new JdbcConnectionManager(dbPath)) {
            var scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
            var fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
            var scanSessionRepository = new JdbcScanSessionRepository(connectionManager);

            ScanLocation locationA = scanLocationRepository.save(newLocation("A", folderA));
            ScanLocation locationB = scanLocationRepository.save(newLocation("B", folderB));

            var scanService = new ScanServiceImpl(new NioFileWalker(), new Sha1HashService(), new DuplicateMatcher(),
                    fileRecordRepository, scanLocationRepository, scanSessionRepository);

            runScanAndWait(scanService, List.of(locationA, locationB));

            var duplicateQueryService = new DuplicateQueryServiceImpl(fileRecordRepository);
            assertThat(duplicateQueryService.getAllDuplicateGroups()).hasSize(1);

            // Ensure the filesystem mtime actually changes (some filesystems have 1s/2s resolution).
            Thread.sleep(1100);
            Files.write(fileB, "different content now, changed!!".getBytes(StandardCharsets.UTF_8));

            runScanAndWait(scanService, List.of(locationA, locationB));

            List<DuplicateGroup> groupsAfterChange = duplicateQueryService.getAllDuplicateGroups();
            assertThat(groupsAfterChange).isEmpty();
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
}
