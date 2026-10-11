package com.filedupedetector.service;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FileRecord.FileStatus;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers User Story 2 acceptance scenario 3: deleting a previously scanned file results in it
 * being marked MISSING and excluded from current duplicate results (FR-008).
 */
class RescanDetectsMissingFilesTest {

    @Test
    void deletedFileIsMarkedMissingAndExcludedFromDuplicates(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path folderA = Files.createDirectories(tempDir.resolve("folderA"));
        Path folderB = Files.createDirectories(tempDir.resolve("folderB"));
        byte[] sharedContent = "content to be deleted later".getBytes(StandardCharsets.UTF_8);
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

            var scanService = new ScanServiceImpl(new NioFileWalker(), new Sha256HashService(), new DuplicateMatcher(),
                    fileRecordRepository, scanLocationRepository, scanSessionRepository);

            runScanAndWait(scanService, List.of(locationA, locationB));
            assertThat(new DuplicateQueryServiceImpl(fileRecordRepository).getAllDuplicateGroups()).hasSize(1);

            Files.delete(fileB);
            runScanAndWait(scanService, List.of(locationA, locationB));

            Optional<FileRecord> deletedRecord = fileRecordRepository.findByLocationAndPath(locationB.getId(), "two.dat");
            assertThat(deletedRecord).isPresent();
            assertThat(deletedRecord.get().getStatus()).isEqualTo(FileStatus.MISSING);

            assertThat(new DuplicateQueryServiceImpl(fileRecordRepository).getAllDuplicateGroups()).isEmpty();
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
        location.setRootPath(path.toAbsolutePath().toString());
        location.setType(LocationType.LOCAL);
        return location;
    }
}
