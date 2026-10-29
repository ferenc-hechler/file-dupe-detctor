package com.filedupedetector;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;
import com.filedupedetector.pcloud.PCloudApiClient;
import com.filedupedetector.pcloud.PCloudFolderWalker;
import com.filedupedetector.persistence.DBManager;
import com.filedupedetector.scan.FolderVisitor;
import com.filedupedetector.scan.HashService;
import com.filedupedetector.scan.Sha1HashService;
import com.filedupedetector.utils.AppUtils;
import com.pcloud.sdk.ApiClient;
import com.pcloud.sdk.ApiError;
import com.pcloud.sdk.Checksums;

public final class ScanWithPCloudFolderWalkerMain {

    DBManager dbMgr;
    HashService hashService;
    ApiClient apiClient;

    public ScanWithPCloudFolderWalkerMain() {
        this.dbMgr = new DBManager(false);
        this.hashService = new Sha1HashService();
        // this.apiClient = PCloudApiClient.getApiClient("~/.env-pcloud-ro-dev");
        this.apiClient = PCloudApiClient.getApiClient("~/.env-pcloud-ro");
    }


    static class CountFilesVisitor implements FolderVisitor {
    	public long fileCount = 0;
    	public long getFileCount() {
			return fileCount;
		}
    	public void visitFolder(Path folder, List<FolderVisitor.FileEntry> files, List<Path> subFolders) {
			fileCount += files.size();
		}
		@Override
		public void preVisitDirectory(Path folder, BasicFileAttributes attrs) {
		}
		@Override
		public void postVisitDirectory(Path folder, IOException exc) {
		}
    }
    
    private long countfiles(String folder) {
    	if (1==1) {
			return 0;
		}
    	PCloudFolderWalker walker = new PCloudFolderWalker(apiClient);
		CountFilesVisitor visitor = new CountFilesVisitor();
		walker.walk(Path.of(folder), visitor);
		return visitor.getFileCount();
    }

    private String getSha1ForFile(long fileId) {
    	Checksums result;
		try {
			result = apiClient.getChecksums(fileId).execute();
	    	String sha1 = result.getSha1().hex();
			return sha1;
		} catch (IOException | ApiError e) {
			return null;
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
                rootFolder.setFullPathstr(location.getRootPath());
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
                	String sha1hash = getSha1ForFile(fileEntry.remoteId());
            		calculatedHashCount++;
            		calculatedHashBytes += fileEntry.size();
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
                currentFolder.setFullPathstr(folderName);
            } else {
                currentFolder.setFullPathstr(oldCurrentFolder.getFullPathstr() + "/" + folderName);
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
        Path rootPath = AppUtils.normPath(folderPath);
        location.setType(LocationType.PCLOUD);
        location.setRootPathWithPCloudInfo(AppUtils.normFilename(rootPath));

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
    	folder = AppUtils.normFilename(Paths.get(folder));;
        PCloudFolderWalker walker = new PCloudFolderWalker(apiClient);
        ScanLocation location = retrieveScanLocation(folder, folder);
        MyFolderVisitor visitor = new MyFolderVisitor(location, countFiles);
        walker.walk(Path.of(folder), visitor);
        System.out.printf("Progress: %d/%d files, %d folders scanned (%.2f%%)%n"
        		+ "  calculatedHashCount=%d, calculatedHashBytes=%d%n", 
                visitor.fileCount, visitor.fileCount, visitor.folderCount, 100.0, 
                visitor.calculatedHashCount, visitor.calculatedHashBytes);

        return new WalkResult(visitor.folderCount, visitor.fileCount);
    }


    private void scanPCloudFolder(String folder) {


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
        ScanWithPCloudFolderWalkerMain scanApp = new ScanWithPCloudFolderWalkerMain();
        
        scanApp.dbMgr.showDBFiles(1);
        
        String folder = "/DEV";
//        String folder = "/EBOOKS";
//        String folder = "/BEWERBUNG";
//        String folder = "/My Pictures/2006";
//        String folder = "/My Pictures/2018";
        
        if (args.length > 0) {
			folder = args[0];
		}
        scanApp.scanPCloudFolder(folder);
        scanApp.dbMgr.showDBFiles(1);
        System.out.printf("%n### DONE ###%n");
        
    }


}
