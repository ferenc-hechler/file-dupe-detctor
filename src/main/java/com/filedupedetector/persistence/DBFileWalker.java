package com.filedupedetector.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.scan.FolderVisitor;

/**
 * Traverses a pCloud folder tree depth-first (pre-order), analogous to
 * {@link com.filedupedetector.scan.FolderWalker}. Each folder is reported once to the
 * {@link FolderVisitor}. Paths passed to the visitor are logical pCloud paths
 * (e.g. {@code /Documents/Sub}). Errors are reported and the walk continues.
 */
public final class DBFileWalker<T> {

    private final DBManager dbMgr;
    
    public interface DBFileVisitor<T> {
        void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path);
        	T visitFolder(ScanLocation location, FolderRecord folderRecord, Path path, List<T> childVisits);
        	T visitFile(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord, Path path);
    }
    
    
    public DBFileWalker() {
        this.dbMgr = new DBManager();
    }

    /** Walks the complete DB tree starting at the given ScanLocations. */
    public T  walk(DBFileVisitor<T> visitor) {
        	List<ScanLocation> locations = dbMgr.getScanLocationRepository().findAll();
        	return walk(locations, visitor);
    }

    public T walk(List<ScanLocation> locations, DBFileVisitor<T> visitor) {
        	List<T> resultList = new ArrayList<>();
        	for (ScanLocation location : locations) {
        		FolderRecord rootFolder = dbMgr.getFolderRecordRepository().findById(location.getRootFolderId());
                T locResult = walk(location, rootFolder, Paths.get(location.getRootPath()), visitor);
                resultList.add(locResult);
        	}
        	T result = visitor.visitFolder(null, null, null, resultList);
        	return result;
    }


    public T walk(ScanLocation scanLocation, FolderRecord rootFolder, Path rootPath, DBFileVisitor<T> visitor) {
        return recursiveWalk(scanLocation, rootFolder, rootPath, visitor);
    }
    
    public T recursiveWalk(ScanLocation location, FolderRecord folderRecord, Path folderPath, DBFileVisitor<T> visitor) {
        
        visitor.preVisitFolder(location, folderRecord, folderPath);
        
        	List<T> childResults = new ArrayList<>();
    
        	List<FileRecord> childFiles = dbMgr.getFileRecordRepository().findByParentFolder(folderRecord.getId());
        childFiles.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (FileRecord childFile : childFiles) {
        		T fileResult = visitor.visitFile(location, folderRecord, childFile, folderPath.resolve(childFile.getName()));
        		childResults.add(fileResult);
        	}
    
        	List<FolderRecord> childFolders = dbMgr.getFolderRecordRepository().findByParentFolder(folderRecord.getId());
        childFolders.sort((a, b) -> a.getName().compareTo(b.getName()));
        	for (FolderRecord childFolder : childFolders) {
        		T childResult = recursiveWalk(location, childFolder, folderPath.resolve(childFolder.getName()), visitor);
        		childResults.add(childResult);
        	}
    
        	T result = visitor.visitFolder(location, folderRecord, folderPath, childResults);
        	return result;
    }

}
