package com.filedupedetector.persistence;

import static com.filedupedetector.utils.AppUtils.readable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FolderRecord;
import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.ui.DBTreeViewer.MemFile;
import com.filedupedetector.ui.DBTreeViewer.MemFolder;


public class MemFileWalker<T> {

    private final MemFolder rootMemFolder;
    
    public interface MemFileVisitor<T> {
        void preVisitFolder(MemFolder mf, Path path);
        T visitFolder(MemFolder mf, Path path, List<T> childVisits);
        T visitFile(MemFolder mf, MemFile mFil, Path path);
    }
    
    public static record MemCountResults(int folderCount, int fileCount, long byteCount) {
        public static MemCountResults count(List<MemCountResults> entries) {
            int folders = 0;
            int files = 0;
            long bytes = 0;
            for (MemCountResults entry : entries) {
                folders += entry.folderCount;
                files += entry.fileCount;
                bytes += entry.byteCount;
            }
            return new MemCountResults(folders, files, bytes);
        }
        public static MemCountResults count(MemFile mFil) {
            return new MemCountResults(0, 1, mFil.sizeBytes());
        }
        public static MemCountResults count(MemFolder mf) {
            return new MemCountResults(1, 0, 0);
        }
        public MemCountResults add(MemCountResults other) {
            return new MemCountResults(this.folderCount + other.folderCount, this.fileCount + other.fileCount,
                    this.byteCount + other.byteCount);
        }
        
        @Override
        public final String toString() {
            return folderCount + " folders, " + fileCount + " files, " + readable(byteCount) + " bytes";
        }
    }

    
    public MemFileWalker(MemFolder rootMemFolder) {
        this.rootMemFolder = rootMemFolder;
    }

    public T  walk(MemFileVisitor<T> visitor) {
    	return walkMem(rootMemFolder.childFolders, visitor);
    }

    public T walk(List<ScanLocation> locations, MemFileVisitor<T> visitor) {
    	Set<Long> startFolderIDs = locations.stream().map(ScanLocation::getRootFolderId).collect(HashSet::new, HashSet::add, HashSet::addAll);
    	List<MemFolder> selectedFolders = new ArrayList<>();
    	for (MemFolder folder : rootMemFolder.childFolders) {
			if (startFolderIDs.contains(folder.folderId)) {
				selectedFolders.add(folder);
			}
		}
		return walkMem(selectedFolders, visitor);
    }

    public T walkMem(List<MemFolder> startFolders, MemFileVisitor<T> visitor) {
        	List<T> resultList = new ArrayList<>();
        	for (MemFolder mf : startFolders) {
                T fResult = walk(mf, Paths.get(mf.folderName), visitor);
                resultList.add(fResult);
        	}
        	T result = visitor.visitFolder(null, null, resultList);
        	return result;
    }


    public T walk(MemFolder mf, Path rootPath, MemFileVisitor<T> visitor) {
        return recursiveWalk(mf, rootPath, visitor);
    }
    
    public T recursiveWalk(MemFolder mf, Path folderPath, MemFileVisitor<T> visitor) {
        
        visitor.preVisitFolder(mf, folderPath);
        
       	List<T> childResults = new ArrayList<>();
    
       	List<MemFile> childFiles = new ArrayList<>(mf.files);
        childFiles.sort((a, b) -> a.fileName().compareTo(b.fileName()));
        for (MemFile childFile : childFiles) {
    		T fileResult = visitor.visitFile(mf, childFile, folderPath.resolve(childFile.fileName()));
    		childResults.add(fileResult);
    	}
    
    	List<MemFolder> childFolders = new ArrayList<>(mf.childFolders);
        childFolders.sort((a, b) -> a.folderName.compareTo(b.folderName));
    	for (MemFolder childFolder : childFolders) {
    		T childResult = recursiveWalk(childFolder, folderPath.resolve(childFolder.folderName), visitor);
    		childResults.add(childResult);
    	}
    
    	T result = visitor.visitFolder(mf, folderPath, childResults);
    	return result;
    }

}
