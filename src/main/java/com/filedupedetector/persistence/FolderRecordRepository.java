package com.filedupedetector.persistence;

import java.util.List;

import com.filedupedetector.model.FolderRecord;

public interface FolderRecordRepository {

    FolderRecord findById(long folderId);
    
    List<FolderRecord> findByParentFolder(long parentFolderId);
    
    FolderRecord findByParentFolderAndName(long parentFolderId, String name);
    
	String getFullPath(FolderRecord fileRecord);

    void deleteByID(List<Long> idsToDelete);
    
}