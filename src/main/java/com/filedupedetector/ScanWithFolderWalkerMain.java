package com.filedupedetector;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.persistence.DBManager;
import com.filedupedetector.scan.FolderVisitor;
import com.filedupedetector.scan.FolderWalker;
import com.filedupedetector.scan.HashService;
import com.filedupedetector.scan.Sha1HashService;
import com.filedupedetector.utils.AppUtils;

public final class ScanWithFolderWalkerMain {

    DBManager dbMgr;
    HashService hashService;
    JSONExporter jsonExporter;

    public ScanWithFolderWalkerMain() {
        this.dbMgr = new DBManager(false);
        this.hashService = new Sha1HashService();
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

    
    class MyFolderVisitor implements FolderVisitor {
        ScanLocation location;
        private Stack<FolderRecord> dirStack;
        private FolderRecord currentFolder;
        private long fileCount;
        private long folderCount;
        private long expectedFileCount;
        private long nextProgressOutput;
		private long calculatedHashCount;
		private long calculatedHashBytes;

        public MyFolderVisitor(ScanLocation location, long expectedFileCount) {
            this.location = location;
            this.expectedFileCount = expectedFileCount;
            this.nextProgressOutput = System.nanoTime();
            FolderRecord rootFolder = null;
            if (location.getRootFolderId() != null) {
                rootFolder = dbMgr.getFolderRecordRepository().findById(location.getRootFolderId());
                if (rootFolder == null) {
                    throw new IllegalStateException("ScanLocation has rootFolderId=" + location.getRootFolderId()
                            + " but no such folder exists in the database");
                }
            }
            if (location.getRootFolderId() == null) {
                rootFolder = dbMgr.getFolderRecordRepository().findByParentFolderAndName(null, location.getRootPath());
                if (rootFolder == null) {
                    rootFolder = new FolderRecord();
                    rootFolder.setName(location.getRootPath());
                    rootFolder.setParentFolderId(null);
                    rootFolder = dbMgr.getFolderRecordRepository().upsert(rootFolder);
                    rootFolder.setNew(true);
                }
                rootFolder.setFullPath(location.getRootPath());
                location.setRootFolderId(rootFolder.getId());
                dbMgr.getScanLocationRepository().save(location);
            }
            currentFolder = new FolderRecord();
            currentFolder.setROOT(true);
            dirStack = new Stack<>();
            fileCount = 0;
            folderCount = 0;
        }

        
        
        @Override
        public void visitFolder(Path folder, List<FileEntry> files, List<Path> subFolders) {
            Map<String, FileRecord> dbFiles = new HashMap<>();
            Map<String, FolderRecord> dbFolders = new HashMap<>();
            if (!currentFolder.isNew()) {
                for (FileRecord fileRecord : dbMgr.getFileRecordRepository().findByParentFolder(currentFolder.getId())) {
                    dbFiles.put(fileRecord.getName(), fileRecord);
                }
                for (FolderRecord folderRecord : dbMgr.getFolderRecordRepository().findByParentFolder(currentFolder.getId())) {
                    dbFolders.put(folderRecord.getName(), folderRecord);
                }
            }

            for (FileEntry fileEntry : files) {
                if (System.nanoTime() >= nextProgressOutput) {
                    double progress = (expectedFileCount > 0) ? (100.0 * fileCount / expectedFileCount) : -1;
                    System.out.printf("Progress: %d/%d files, %d folders scanned (%.2f%%)%n", 
                        fileCount, expectedFileCount, folderCount, progress);
                    nextProgressOutput = System.nanoTime() + 15_000_000_000L; // alle 15 Sekunden
                }
                fileCount++;
                String fileName = fileEntry.path().getFileName().toString();
                FileRecord fileRecord = null;
                if (!currentFolder.isNew()) {
                    FileRecord existingFile = dbFiles.remove(fileName);
                    if (existingFile != null) {
                        fileRecord = existingFile;
                    }
                }
                if (fileRecord == null) {
                	String sha1hash = null;
                	try {
                		sha1hash = hashService.computeHash(fileEntry.path());
                		calculatedHashCount++;
                		calculatedHashBytes += fileEntry.size();
                	}
                	catch (IOException e) {}
                    fileRecord = new FileRecord();
                    fileRecord.setName(fileName);
                    fileRecord.setParentFolderId(currentFolder.getId());
                    fileRecord.setSizeBytes(fileEntry.size());
                    fileRecord.setLastModifiedAt(fileEntry.lastModified().toInstant());
                    fileRecord.setSha1hash(sha1hash);
                    fileRecord = dbMgr.getFileRecordRepository().upsert(fileRecord);
                }
            }

            List<Long> fileIDsToDelete = dbFiles.values().stream().map(fileRecord -> fileRecord.getId()).toList(); 
            dbMgr.getFileRecordRepository().deleteByID(fileIDsToDelete);
            
            subFolders.stream().map(subFolder -> subFolder.getFileName().toString()).forEach(subFolderName -> dbFolders.remove(subFolderName));
            dbFolders.values().forEach((folderToDelete) -> dbMgr.recursiveDeleteFolder(folderToDelete.getId()));
                                
        }

        @Override
        public void preVisitDirectory(Path folder, BasicFileAttributes attrs) {
            folderCount++;
            FolderRecord oldCurrentFolder = currentFolder;
            dirStack.push(currentFolder);
            String folderName = currentFolder.isROOT() ? AppUtils.normFilename(folder) : folder.getFileName().toString();
            currentFolder = null;
            if (oldCurrentFolder.isROOT() || !oldCurrentFolder.isNew()) {
                FolderRecord existingFolder = dbMgr.getFolderRecordRepository().findByParentFolderAndName(oldCurrentFolder.getIdOrNull(), folderName);
                if (existingFolder != null) {
                    currentFolder = existingFolder;
                }
            }
            if (currentFolder == null) {
                currentFolder = new FolderRecord();
                currentFolder.setName(folderName);
                currentFolder.setParentFolderId(oldCurrentFolder.getIdOrNull());
                currentFolder = dbMgr.getFolderRecordRepository().upsert(currentFolder);
                currentFolder.setNew(true);
            }
            if (oldCurrentFolder.isROOT()) {
                currentFolder.setFullPath(folderName);
            } else {
                currentFolder.setFullPath(oldCurrentFolder.getFullPath() + "/" + folderName);
            }

        }



        @Override
        public void postVisitDirectory(Path folder, IOException exc) {
            if (exc != null) {
                System.err.println("Error accessing directory: " + folder + " error=" + exc.toString());
            }
//            Long parentFolderID = (oldCurrentFolder != null) ? oldCurrentFolder.getId() : null;
            currentFolder = dirStack.pop();
//            System.out.println("<-- leaving DIR["+parentFolderID+"] back to DIR["+currentFolder.getId()+"]");
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
        location.setRootPathWithVolInfo(AppUtils.normFilename(rootPath));

        var existing = dbMgr.getScanLocationRepository().findByDisplayName(name);
        if (existing != null) {
            // The location already exists: it must still describe the same folder on the
            // same volume.
            verifySameLocation(existing, location);
            return existing;
        }
        return dbMgr.getScanLocationRepository().save(location);
    }

    record WalkResult(long folderCount, long fileCount) {
    }


    
    private WalkResult walkFolders(String folder, long countFiles) {
    	folder = AppUtils.normAbsFilename(folder);;
        FolderWalker walker = new FolderWalker();
        ScanLocation location = retrieveScanLocation(folder, folder);
        MyFolderVisitor visitor = new MyFolderVisitor(location, countFiles);
        walker.walk(Path.of(folder), visitor);
        System.out.printf("Progress: %d/%d files, %d folders scanned (%.2f%%)%n"
        		+ "  calculatedHashCount=%d, calculatedHashBytes=%d%n", 
                visitor.fileCount, visitor.fileCount, visitor.folderCount, 100.0, 
                visitor.calculatedHashCount, visitor.calculatedHashBytes);

        return new WalkResult(visitor.folderCount, visitor.fileCount);
    }


    private void scanFileFolder(String folder) {


      long start = System.nanoTime();
      long countFiles = countfiles(folder);
      long stop = System.nanoTime();
      double seconds = (stop - start) / 1_000_000_000.0;
      System.out.printf("%ncountfiles: %d took %.3f sec%n", countFiles, seconds);

      start = System.nanoTime();
      WalkResult wr = walkFolders(folder, countFiles);
      stop = System.nanoTime();
      seconds = (stop - start) / 1_000_000_000.0;

      System.out.printf(
              "%n### walkFolders took %.3f s ###%n  Folders: %d%n  Files: %d%n  avg. per file: %.3f ms%n",
              seconds, wr.folderCount(), wr.fileCount(), 1000.0 * seconds / wr.fileCount());
        
    }

    
    public static void main(String[] args) {
        ScanWithFolderWalkerMain scanApp = new ScanWithFolderWalkerMain();
        
        scanApp.dbMgr.showDBFiles(2);
        scanApp.scanFileFolder("./testinput/test1");
        scanApp.scanFileFolder("./testinput/test2");
        scanApp.scanFileFolder("./testinput/test3");
        scanApp.scanFileFolder("./testinput/test1/sub1b");
        
        scanApp.dbMgr.showDBFiles(3);
    }


}
