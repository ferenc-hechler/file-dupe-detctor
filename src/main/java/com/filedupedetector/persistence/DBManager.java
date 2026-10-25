package com.filedupedetector.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.filedupedetector.model.FolderRecord;

public class DBManager {
	
    JdbcConnectionManager connectionManager;
    ScanLocationRepository scanLocationRepository;
    FileRecordRepository fileRecordRepository;
    FolderRecordRepository folderRecordRepository;

    public DBManager() {
        this(false);
    }
    public DBManager(boolean zapDatabase) {
	    this.connectionManager = new JdbcConnectionManager(zapDatabase);
	    this.scanLocationRepository = new JdbcScanLocationRepository(connectionManager);
	    this.fileRecordRepository = new JdbcFileRecordRepository(connectionManager);
	    this.folderRecordRepository = new JdbcFolderRecordRepository(connectionManager);
	}

	public ScanLocationRepository getScanLocationRepository() {
		return scanLocationRepository;
	}
	
	public FileRecordRepository getFileRecordRepository() {
		return fileRecordRepository;
	}
	
	public FolderRecordRepository getFolderRecordRepository() {
		return folderRecordRepository;
	}
 
	
    public void showDBFiles(int maxDepth) {
        getScanLocationRepository().findAll().forEach(location -> {
            System.out.println(location.disp());
            FolderRecord folderRecord = getFolderRecordRepository().findById(location.getRootFolderId());
            recursiveShowDBFiles(folderRecord, maxDepth, "  ");
        });
    }

    private void recursiveShowDBFiles(FolderRecord folderRecord, int maxDepth, String indent) {
        if (maxDepth <= 0) {
            return;
        }
        System.out.println(folderRecord.disp(indent));
        getFileRecordRepository().findByParentFolder(folderRecord.getId()).forEach(file -> {
            System.out.println(file.disp(indent+"  "));
		});       
        getFolderRecordRepository().findByParentFolder(folderRecord.getId()).forEach(folder -> {
            recursiveShowDBFiles(folder, maxDepth - 1, indent + "  ");
        });
    }
	
    public void recursiveDeleteFolder(Long folderId) {
        List<Long> fileIDsToDelete = getFileRecordRepository().findIDsByParentFolder(folderId);
        getFileRecordRepository().deleteByID(fileIDsToDelete);
        List<Long> folderIDsToDelete = getFolderRecordRepository().findIDsByParentFolder(folderId);
        folderIDsToDelete.forEach((subFolderId) -> recursiveDeleteFolder(subFolderId));
        getFolderRecordRepository().deleteByID(folderId);
    }
	public void shutdownCompact() {
		try (Connection conn = connectionManager.openConnection();
			Statement stmt = conn.createStatement()) {
		    stmt.execute("SHUTDOWN COMPACT");	
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to compact database", e);
		}
	}

}
