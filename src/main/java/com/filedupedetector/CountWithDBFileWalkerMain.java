package com.filedupedetector;

import java.nio.file.Path;
import java.util.List;

import com.filedupedetector.ShowWithDBFileWalkerMain.CountResults;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.DBFileWalker;
import com.filedupedetector.persistence.DBFileWalker.DBFileVisitor;
import com.filedupedetector.utils.ProgressLogger;

public final class CountWithDBFileWalkerMain {

    class ShowDBFileVisitor implements DBFileVisitor<CountResults> {
    	
    	ProgressLogger progressLogger = new ProgressLogger();

        @Override
        public void preVisitFolder(ScanLocation location, FolderRecord folderRecord, Path path) {
        }

        @Override
        public CountResults visitFolder(ScanLocation location, FolderRecord folderRecord, Path path, List<CountResults> childVisits) {
            CountResults result = CountResults.count(childVisits);
            if (location == null) {
                System.out.println("TOTAL: " + result);
            }
            else if (location.getRootFolderId() == folderRecord.getId()) {
				System.out.println("Location: " + location.disp() + " - " + result);
			}
            else {
//            	if (path.getNameCount() == 2) {
//            		if (path.toString().startsWith("D:\\99")) {
//        				System.out.println("  Path: " + path + " - " + result);
//					}
//            	}
                result = result.add(CountResults.count(folderRecord));
            }
            return result;
        }

        @Override
        public CountResults visitFile(ScanLocation location, FolderRecord folderRecord, FileRecord fileRecord,
                Path path) {
            return CountResults.count(fileRecord);
        }

    }

    public void showDBFiles() {
        DBFileWalker<CountResults> dbWalker = new DBFileWalker<>();
        dbWalker.walk(new ShowDBFileVisitor());

    }

    public static void main(String[] args) {
        CountWithDBFileWalkerMain showDBApp = new CountWithDBFileWalkerMain();
        showDBApp.showDBFiles();
    }

}
