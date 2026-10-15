package com.filedupedetector.persistence;

import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.utils.VolInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcScanLocationRepositoryTest {

    @Test
    void setRootPathPopulatesVolumeFields(@TempDir Path tempDir) throws IOException {
        Path folder = Files.createDirectories(tempDir.resolve("sample"));
        String rootPath = folder.toAbsolutePath().toString();

        ScanLocation location = new ScanLocation();
        location.setRootPathWithVolInfo(rootPath);

        VolInfo expected = new VolInfo(folder.toAbsolutePath());
        assertThat(location.getVolName()).isEqualTo(expected.getVolName());
        assertThat(location.getVolType()).isEqualTo(expected.getVolType());
        assertThat(location.getVolSize()).isEqualTo(expected.getVolSize());
        assertThat(location.getVolVSN()).isEqualTo(expected.getVolVSN());
    }

    @Test
    void saveAndLoadRoundTripsVolumeFields(@TempDir Path tempDir) throws IOException {
        Path folder = Files.createDirectories(tempDir.resolve("location"));
        Path dbPath = tempDir.resolve("test-db").resolve("store");

        try (JdbcConnectionManager connectionManager = new JdbcConnectionManager(dbPath)) {
            JdbcScanLocationRepository repository = new JdbcScanLocationRepository(connectionManager);

            ScanLocation location = new ScanLocation();
            location.setDisplayName("My Location");
            location.setRootPathWithVolInfo(folder.toAbsolutePath().toString());
            location.setType(LocationType.LOCAL);

            ScanLocation saved = repository.save(location);
            List<ScanLocation> all = repository.findAll();

            assertThat(saved.getId()).isPositive();
            assertThat(all).hasSize(1);

            ScanLocation loaded = all.get(0);
            assertThat(loaded.getVolName()).isEqualTo(saved.getVolName());
            assertThat(loaded.getVolType()).isEqualTo(saved.getVolType());
            assertThat(loaded.getVolSize()).isEqualTo(saved.getVolSize());
            assertThat(loaded.getVolVSN()).isEqualTo(saved.getVolVSN());
        }
    }
}
