package com.filedupedetector.persistence;

import java.util.List;

import com.filedupedetector.model.FileRecord;

public interface FileRecordRepository {

	FileRecord findById(long fileId);

    List<FileRecord> findByParentFolder(long parentFolderId);
    
    List<Long> findIDsByParentFolder(long parentFolderId);
    
    FileRecord findByParentFolderAndName(long parentFolderId, String name);
    
	List<FileRecord> findBySHA1(String sha1hash);

    FileRecord upsert(FileRecord record);
    
    void changeParentFolder(long fileRecordId, long newParentFolderId);

    void deleteByID(List<Long> idsToDelete);

}