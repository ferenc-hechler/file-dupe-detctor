package com.filedupedetector.service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Stack;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.persistence.FileRecordRepository;
import com.filedupedetector.persistence.FolderRecordRepository;
import com.filedupedetector.persistence.JdbcConnectionManager;
import com.filedupedetector.persistence.JdbcFileRecordRepository;
import com.filedupedetector.persistence.JdbcFolderRecordRepository;
import com.filedupedetector.persistence.JdbcScanLocationRepository;
import com.filedupedetector.persistence.ScanLocationRepository;
import com.filedupedetector.utils.AppUtils;

public final class FileWalkerPerfTestMain {

    ScanLocationRepository scanLocationRepository;
    FileRecordRepository fileRecordRepository;
    FolderRecordRepository folderRecordRepository;

    public FileWalkerPerfTestMain() {
        JdbcConnectionManager connectionManager = new JdbcConnectionManager(false);
        this.scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
        this.fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
        this.folderRecordRepository = new JdbcFolderRecordRepository(connectionManager);
    }

    public void showDBFiles(int maxDepth) {
        scanLocationRepository.findAll().forEach(location -> {
            System.out.println("ScanLocation: " + location);
            FolderRecord folderRecord = folderRecordRepository.findById(location.getRootFolderId());
            recursiveShowDBFiles(folderRecord, maxDepth);
        });
    }

    private void recursiveShowDBFiles(FolderRecord folderRecord, int maxDepth) {
        if (maxDepth < 0) {
            return;
        }
        System.out.println("FolderRecord: " + folderRecord);
        fileRecordRepository.findByParentFolder(folderRecord.getId()).forEach(file -> {
            System.out.println("  FileRecord: " + file);
        });
        folderRecordRepository.findByParentFolder(folderRecord.getId()).forEach(folder -> {
            recursiveShowDBFiles(folder, maxDepth - 1);
        });
    }

    private long countfiles(String folder) {
        try {
            Path root = Path.of(folder);
            final long[] count = { 0 };
            final long[] nextProgress = { System.nanoTime() }; 
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (System.nanoTime() >= nextProgress[0]) {
                        System.out.printf("countfiles: %d files counted%n", count[0]);
                        nextProgress[0] = System.nanoTime() + 15_000_000_000L; // alle 15 Sekunden
                    }
                    if (attrs.isRegularFile())
                        count[0]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // z. B. fehlende Berechtigungen ignorieren
                }
            });
            System.out.printf("countfiles: %d files counted%n", count[0]);
            return count[0];
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    class MyFileVisitor extends SimpleFileVisitor<Path> {
        private ScanLocation location;
        private Stack<FolderRecord> dirStack;
        private FolderRecord currentFolder;
        private long fileCount;
        private long folderCount;
        private long expectedFileCount;
        private long nextProgressOutput;

        public MyFileVisitor(ScanLocation location, long expectedFileCount) {
            this.location = location;
            this.expectedFileCount = expectedFileCount;
            this.nextProgressOutput = System.nanoTime();
            FolderRecord rootFolder = null;
            if (location.getRootFolderId() != null) {
                rootFolder = folderRecordRepository.findById(location.getRootFolderId());
                if (rootFolder == null) {
                    throw new IllegalStateException("ScanLocation has rootFolderId=" + location.getRootFolderId()
                            + " but no such folder exists in the database");
                }
            }
            if (location.getRootFolderId() == null) {
                rootFolder = folderRecordRepository.findByParentFolderAndName(null, location.getRootPath());
                if (rootFolder == null) {
                    rootFolder = new FolderRecord();
                    rootFolder.setName(location.getRootPath());
                    rootFolder.setParentFolderId(null);
                    rootFolder = folderRecordRepository.upsert(rootFolder);
                    rootFolder.setNew(false);
                }
                rootFolder.setFullPath(location.getRootPath());
                location.setRootFolderId(rootFolder.getId());
                scanLocationRepository.save(location);
            }
            currentFolder = rootFolder;
            dirStack = new Stack<>();
            fileCount = 0;
            folderCount = 0;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            folderCount++;
            FolderRecord oldCurrentFolder = currentFolder;
            dirStack.push(currentFolder);
            String folderName = dir.getFileName().toString();
            currentFolder = null;
            if (!oldCurrentFolder.isNew()) {
                FolderRecord existingFolder = folderRecordRepository.findByParentFolderAndName(oldCurrentFolder.getId(),
                        folderName);
                if (existingFolder != null) {
                    currentFolder = existingFolder;
                }
            }
            if (currentFolder == null) {
                currentFolder = new FolderRecord();
                currentFolder.setName(folderName);
                currentFolder.setParentFolderId(oldCurrentFolder.getId());
                currentFolder = folderRecordRepository.upsert(currentFolder);
                currentFolder.setNew(false);
            }
            currentFolder.setFullPath(oldCurrentFolder.getFullPath() + "/" + folderName);
//            System.out.println("P["+oldCurrentFolder.getId()+"]/DIR["+currentFolder.getId()+"]: " + dir);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (System.nanoTime() >= nextProgressOutput) {
                double progress = (expectedFileCount > 0) ? (100.0 * fileCount / expectedFileCount) : -1;
                System.out.printf("Progress: %d/%d files, %d folders scanned (%.2f%%)%n", 
                    fileCount, expectedFileCount, folderCount, progress);
                nextProgressOutput = System.nanoTime() + 5_000_000_000L; // alle 15 Sekunden
            }
            fileCount++;
            String fileName = file.getFileName().toString();
            FileRecord fileRecord = null;
            if (!currentFolder.isNew()) {
                FileRecord existingFile = fileRecordRepository.findByParentFolderAndName(currentFolder.getId(),
                        fileName);
                if (existingFile != null) {
                    fileRecord = existingFile;
                }
            }
            if (fileRecord == null) {
                fileRecord = new FileRecord();
                fileRecord.setName(fileName);
                fileRecord.setParentFolderId(currentFolder.getId());
                fileRecord.setSizeBytes(attrs.size());
                fileRecord.setLastModifiedAt(attrs.lastModifiedTime().toInstant());
                fileRecord = fileRecordRepository.upsert(fileRecord);
            }
//            System.out.println("  DIR["+currentFolder.getId()+"]/FILE: " + file + " size=" + attrs.size() + " lastModified=" + attrs.lastModifiedTime());
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            System.err.println("  Error accessing path: " + file + " error=" + exc.toString());
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) {
                System.err.println("Error accessing directory: " + dir + " error=" + exc.toString());
            }
//            FolderRecord oldCurrentFolder = currentFolder;
            currentFolder = dirStack.pop();
//            System.out.println("<-- leaving DIR["+oldCurrentFolder.getId()+"] back to DIR["+currentFolder.getId()+"]");
            return FileVisitResult.CONTINUE;
        }

        public long getFileCount() {
            return fileCount;
        }

        public long getFolderCount() {
            return folderCount;
        }
    }

    private static void verifySameLocation(ScanLocation existing, ScanLocation requested) {
        List<String> differences = new java.util.ArrayList<>();
        addDifference(differences, "rootPath", existing.getRootPath(), requested.getRootPath());
        addDifference(differences, "volName", existing.getVolName(), requested.getVolName());
        addDifference(differences, "volType", existing.getVolType(), requested.getVolType());
        addDifference(differences, "volSize", existing.getVolSize(), requested.getVolSize());
        addDifference(differences, "volVSN", existing.getVolVSN(), requested.getVolVSN());
        if (!differences.isEmpty()) {
            throw new IllegalStateException("Scan location '" + existing.getDisplayName()
                    + "' already exists with different values: " + String.join("; ", differences));
        }
    }

    private static void addDifference(List<String> differences, String field, Object stored, Object requested) {
        if (!java.util.Objects.equals(stored, requested)) {
            differences.add(field + " (stored: " + stored + ", requested: " + requested + ")");
        }
    }

    public ScanLocation retrieveScanLocation(String name, String folderPath) {
        ScanLocation location = new ScanLocation();
        location.setDisplayName(name);
        Path rootPath = AppUtils.normAbsPath(folderPath);
        location.setType(LocationType.LOCAL);
        location.setRootPathWithVolInfo(rootPath.toString());

        var existing = scanLocationRepository.findByDisplayName(name);
        if (existing != null) {
            // The location already exists: it must still describe the same folder on the
            // same volume.
            verifySameLocation(existing, location);
            return existing;
        }
        return scanLocationRepository.save(location);
    }

    record WalkResult(long folderCount, long fileCount) {
    }

    private WalkResult testWalk(String rootPath, long expectedFileCount) {
        Path root = Path.of(rootPath);
        if (!java.nio.file.Files.exists(root) || !java.nio.file.Files.isReadable(root)
                || !java.nio.file.Files.isDirectory(root)) {
            System.err.println("directory not found: " + rootPath);
            return null;
        }
        ScanLocation scanLocation = retrieveScanLocation(rootPath, rootPath);
        try {
            MyFileVisitor visitor = new MyFileVisitor(scanLocation, expectedFileCount);
            Files.walkFileTree(root, visitor);
            System.out.printf("Progress: %d/%d files, %d folders scanned (%.2f%%)%n", 
                    visitor.getFileCount(), expectedFileCount, visitor.getFolderCount(), 100.0);

            return new WalkResult(visitor.getFolderCount(), visitor.getFileCount());
        } catch (IOException e) {
            System.err.println("Error walking file tree: " + e.toString());
            return null;
        }
    }

    public static void main(String[] args) {
        FileWalkerPerfTestMain test = new FileWalkerPerfTestMain();

//        test.showDBFiles(3);

        long start = System.nanoTime();
      long countFiles = test.countfiles("C:/DEV/ws-mlops2/JavaTest");
//      long countFiles = test.countfiles("C:/DEV/ws-mlops2");
//        long countFiles = test.countfiles("C:/DEV");
        long stop = System.nanoTime();
        double seconds = (stop - start) / 1_000_000_000.0;
        System.out.printf("%ncountfiles: %d took %.3f sec%n", countFiles, seconds);

        start = System.nanoTime();
        WalkResult wr = test.testWalk("C:/DEV/ws-mlops2/JavaTest", countFiles);
//        WalkResult wr = test.testWalk("C:/DEV/ws-mlops2", countFiles);
//        WalkResult wr = test.testWalk("C:/DEV", countFiles);
//        WalkResult wr = test.testWalk(".");
//        WalkResult wr = test.testWalk("./src");
//        test.testWalk("./specs");
        stop = System.nanoTime();
        seconds = (stop - start) / 1_000_000_000.0;

//        test.showDBFiles(3);

        System.out.printf(
                "%n### testWalk dauerte %.3f Sekunden ###%n  Folders: %d%n  Files: %d%n  avg. per file: %.3f ms%n",
                seconds, wr.folderCount(), wr.fileCount(), 1000.0 * seconds / wr.fileCount());
    }

}