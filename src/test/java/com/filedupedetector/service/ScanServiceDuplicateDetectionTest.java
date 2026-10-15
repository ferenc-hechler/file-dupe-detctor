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
 * Covers User Story 1 acceptance scenarios 1-3: scanning two folders with one identical-content
 * pair (different names) plus unique files yields exactly one correct duplicate group.
 */
class ScanServiceDuplicateDetectionTest {

    @Test
    void findsExactlyOneDuplicateGroupAcrossTwoFolders(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path folderA = Files.createDirectories(tempDir.resolve("folderA"));
        Path folderB = Files.createDirectories(tempDir.resolve("folderB"));

        byte[] sharedContent = "duplicate content shared across folders".getBytes(StandardCharsets.UTF_8);
        Files.write(folderA.resolve("photo1.jpg"), sharedContent);
        Files.write(folderB.resolve("photo-copy.jpg"), sharedContent);
        Files.write(folderA.resolve("unique-a.txt"), "unique content A".getBytes(StandardCharsets.UTF_8));
        Files.write(folderB.resolve("unique-b.txt"), "unique content B, different length!".getBytes(StandardCharsets.UTF_8));

        Path dbPath = tempDir.resolve("test-db").resolve("store");
        try (JdbcConnectionManager connectionManager = new JdbcConnectionManager(dbPath)) {
            var scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
            var fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
            var scanSessionRepository = new JdbcScanSessionRepository(connectionManager);

            ScanLocation locationA = scanLocationRepository.save(newLocation("A", folderA));
            ScanLocation locationB = scanLocationRepository.save(newLocation("B", folderB));

            var scanService = new ScanServiceImpl(
                    new NioFileWalker(),
                    new Sha1HashService(),
                    new DuplicateMatcher(),
                    fileRecordRepository,
                    scanLocationRepository,
                    scanSessionRepository);

            CountDownLatch latch = new CountDownLatch(1);
            scanService.scanBlocking(List.of(locationA, locationB), new ScanProgressListener() {
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

            var duplicateQueryService = new DuplicateQueryServiceImpl(fileRecordRepository);
            List<DuplicateGroup> groups = duplicateQueryService.getAllDuplicateGroups();

            assertThat(groups).hasSize(1);
            DuplicateGroup group = groups.get(0);
            assertThat(group.getMembers()).hasSize(2);
            assertThat(group.getMembers())
                    .extracting(m -> m.getRelativePath())
                    .containsExactlyInAnyOrder("photo1.jpg", "photo-copy.jpg");
        }
    }

    private ScanLocation newLocation(String name, Path path) {
        ScanLocation location = new ScanLocation();
        location.setDisplayName(name);
        location.setRootPathWithVolInfo(path.toAbsolutePath().toString());
        location.setType(LocationType.LOCAL);
        return location;
    }
}
