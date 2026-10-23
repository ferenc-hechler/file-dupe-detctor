package com.filedupedetector.persistence;

import java.util.List;

import com.filedupedetector.model.FolderRecord;

public interface FolderRecordRepository {

    FolderRecord findById(Long folderId);
    
    List<FolderRecord> findByParentFolder(Long parentFolderId);
    
    FolderRecord findByParentFolderAndName(Long parentFolderId, String name);
    
	String getFullPath(FolderRecord folderRecord);

	FolderRecord upsert(FolderRecord folderRecord);
	
    void deleteByID(List<Long> idsToDelete);
    
}