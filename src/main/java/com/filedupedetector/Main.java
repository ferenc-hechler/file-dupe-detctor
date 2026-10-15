package com.filedupedetector;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.model.ScanSession;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.JdbcScanSessionRepository;
import com.filedupedetector.scan.NioFileWalker;
import com.filedupedetector.scan.Sha1HashService;
import com.filedupedetector.service.DuplicateMatcher;
import com.filedupedetector.service.DuplicateQueryServiceImpl;
import com.filedupedetector.service.AppBootstrap;
import com.filedupedetector.service.ScanProgressListener;
import com.filedupedetector.service.ScanServiceImpl;
import com.filedupedetector.gui.MainWindow;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Application entry point for the Duplicate File Detector.
 *
 * <p>Default (no CLI args): launches the Swing {@link MainWindow} on the Event Dispatch Thread
 * (Phase 5, T041), loading persisted scan locations/duplicates on startup.
 *
 * <p>With one or more folder paths as CLI args: runs the Phase 3 (T024) console smoke-test
 * harness instead - registers the given folders as {@link ScanLocation}s, scans them
 * synchronously, and prints resulting duplicate groups to stdout. Useful for scripted/automated
 * testing without a display.
 */
public final class Main {

    private Main() {
        // utility/entry-point class
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            launchGui();
            return;
        }
        runConsoleHarness(args);
    }

    private static void launchGui() {
        SwingUtilities.invokeLater(() -> {
            try {
                JdbcConnectionManager connectionManager = new JdbcConnectionManager();
                var scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
                var fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
                var scanSessionRepository = new JdbcScanSessionRepository(connectionManager);

                var scanService = new ScanServiceImpl(
                        new NioFileWalker(),
                        new Sha1HashService(),
                        new DuplicateMatcher(),
                        fileRecordRepository,
                        scanLocationRepository,
                        scanSessionRepository);

                MainWindow window = new MainWindow(scanLocationRepository, fileRecordRepository, scanService);
                window.setVisible(true);
            } catch (RuntimeException e) {
                // Surface startup failures (e.g. database init errors) to the user instead of
                // the application silently failing to show any window.
                javax.swing.JOptionPane.showMessageDialog(null,
                        "Failed to start File Dupe Detector:\n" + e.getMessage(),
                        "Startup Error", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static void runConsoleHarness(String[] args) {

        try (JdbcConnectionManager connectionManager = new JdbcConnectionManager()) {
            var scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
            var fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
            var scanSessionRepository = new JdbcScanSessionRepository(connectionManager);

            // FR-005/FR-013/SC-004: load persisted state on startup so previously scanned
            // locations and duplicate results are available without a new scan.
            var bootstrap = new AppBootstrap(scanLocationRepository, fileRecordRepository);
            AppBootstrap.StartupState startupState = bootstrap.loadStartupState();
            System.out.println("Loaded " + startupState.locations().size() + " persisted location(s) and "
                    + startupState.duplicateGroups().size() + " previously known duplicate group(s).");

            List<ScanLocation> locations = new ArrayList<>();
            for (String arg : args) {
                ScanLocation location = new ScanLocation();
                location.setDisplayName(arg);
                location.setRootPathWithVolInfo(Path.of(arg).toAbsolutePath().toString());
                location.setType(LocationType.LOCAL);
                locations.add(scanLocationRepository.save(location));
            }

            var scanService = new ScanServiceImpl(
                    new NioFileWalker(),
                    new Sha1HashService(),
                    new DuplicateMatcher(),
                    fileRecordRepository,
                    scanLocationRepository,
                    scanSessionRepository);

            System.out.println("Scanning " + locations.size() + " location(s)...");
            scanService.scanBlocking(locations, new ScanProgressListener() {
                @Override
                public void onProgress(int filesProcessed, int totalEstimate) {
                    // Console harness: progress not printed per-file to keep output readable.
                }

                @Override
                public void onSessionComplete(ScanSession result) {
                    System.out.println("Scan complete: " + result);
                }

                @Override
                public void onLocationUnavailable(ScanLocation location) {
                    System.out.println("WARNING: location unavailable: " + location.getRootPath());
                }
            });

            var duplicateQueryService = new DuplicateQueryServiceImpl(fileRecordRepository);
            List<DuplicateGroup> groups = duplicateQueryService.getAllDuplicateGroups();
            System.out.println();
            System.out.println("Found " + groups.size() + " duplicate group(s):");
            for (DuplicateGroup group : groups) {
                System.out.println("- size=" + group.getSizeBytes() + " hash=" + group.getContentHash());
                for (FileRecord member : group.getMembers()) {
                    System.out.println("    " + member.getRelativePath());
                }
            }
        }
    }
}

