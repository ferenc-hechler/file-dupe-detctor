package com.filedupedetector.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

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
public final class DBFolderWalker<T> {

    private final DBManager dbMgr;
    
    interface DBFileVisitor<T> {
    	T visitFolder(FolderRecord folderRecord, Path path, List<T> childVisits);
    	T visitFile(FileRecord fileRecord, Path path, FolderRecord folderRecord);
    }
    
    
    public DBFolderWalker() {
        this.dbMgr = new DBManager();
    }

    private record FolderAction(FolderRecord folderRecord, Path folderPath, boolean leave) {}

    /** Walks the complete DB tree starting at the given ScanLocations. */
    public T  walk(DBFileVisitor<T> visitor) {
    	List<ScanLocation> locations = dbMgr.getScanLocationRepository().findAll();
    	return walk(locations, visitor);
    }

    public T walk(List<ScanLocation> locations, DBFileVisitor<T> visitor) {
    	List<T> resultList = new ArrayList<>();
    	for (ScanLocation location : locations) {
    		FolderRecord rootFolder = dbMgr.getFolderRecordRepository().findById(location.getRootFolderId());
            T locResult = walk(rootFolder, Paths.get(location.getRootPath()), visitor);
            resultList.add(locResult);
    	}
    	T result = visitor.visitFolder(null, null, resultList);
    	return result;
    }


    
    /**
     * Walks the tree starting at the given folder.
     *
     * @param rootFolderId pCloud folder id
     * @param rootPath     logical path used for the root folder in visitor callbacks
     * @param visitor      callback
     */
    public T walk(FolderRecord rootFolder, Path rootPath, DBFileVisitor<T> visitor) {
    	return recursiveWalk(rootFolder, rootPath, visitor);
    }
    
    public T recursiveWalk(FolderRecord folderRecord, Path folderPath, DBFileVisitor<T> visitor) {

    	List<T> childResults = new ArrayList<>();

    	List<FileRecord> childFiles = dbMgr.getFileRecordRepository().findByParentFolder(folderRecord.getId());
        childFiles.sort((a, b) -> a.getName().compareTo(b.getName()));
    	for (FileRecord childFile : childFiles) {
			T fileResult = visitor.visitFile(childFile, folderPath.resolve(childFile.getName()), folderRecord);
			childResults.add(fileResult);
		}

    	List<FolderRecord> childFolders = dbMgr.getFolderRecordRepository().findByParentFolder(folderRecord.getId());
        childFolders.sort((a, b) -> a.getName().compareTo(b.getName()));
    	for (FolderRecord childFolder : childFolders) {
    		T childResult = recursiveWalk(childFolder, folderPath.resolve(childFolder.getName()), visitor);
			childResults.add(childResult);
    	}

    	T result = visitor.visitFolder(folderRecord, childResults);
    	return result;
    }

}
