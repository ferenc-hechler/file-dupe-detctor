package com.filedupedetector.persistence;

import java.nio.file.Path;
import java.util.List;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;

public final class SimpleDBFileWalker extends DBFileWalker<Object> {
    
    public abstract static class SimpleDBFileVisitor implements DBFileVisitor<Object> {
        public Object visitFolder(ScanLocation location, FolderRecord folderRecord, Path path, List<Object> childVisits) {
        	folderVisit(location, folderRecord, path);
        	return null;
        }
        public abstract void folderVisit(ScanLocation location, FolderRecord folderRecord, Path path);
        public Object visitFile(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord, Path path) {
            fileVisit(location, folderRecord, fileRecord, path);
        	return null;
        }
        public abstract void fileVisit(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord, Path path);
    }
    
	
    public SimpleDBFileWalker() {
        super();
    }
    
    public SimpleDBFileWalker(DBManager dbMgr) {
    	super(dbMgr);
    }

}
