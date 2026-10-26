package com.filedupedetector;

import static com.filedupedetector.utils.AppUtils.readable;

import java.nio.file.Path;
import java.util.List;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.persistence.DBFileWalker;
import com.filedupedetector.persistence.DBFileWalker.DBFileVisitor;

public final class ShowWithDBFileWalkerMain {

    record CountResults(int folderCount, int fileCount, long byteCount) {
        public static CountResults count(List<CountResults> entries) {
            int folders = 0;
            int files = 0;
            long bytes = 0;
            for (CountResults entry : entries) {
                folders += entry.folderCount;
                files += entry.fileCount;
                bytes += entry.byteCount;
            }
            return new CountResults(folders, files, bytes);
        }
        public static CountResults count(FileRecord fileRecord) {
            return new CountResults(0, 1, fileRecord.getSizeBytes());
        }
        public static CountResults count(FolderRecord folderRecord) {
            return new CountResults(1, 0, 0);
        }
        CountResults add(CountResults other) {
            return new CountResults(this.folderCount + other.folderCount, this.fileCount + other.fileCount,
                    this.byteCount + other.byteCount);
        }
        
        @Override
        public final String toString() {
            return folderCount + " folders, " + fileCount + " files, " + readable(byteCount) + " bytes";
        }
    }
    
    class ShowDBFileVisitor implements DBFileVisitor<CountResults> {

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
        ShowWithDBFileWalkerMain showDBApp = new ShowWithDBFileWalkerMain();
        showDBApp.showDBFiles();
    }

}
