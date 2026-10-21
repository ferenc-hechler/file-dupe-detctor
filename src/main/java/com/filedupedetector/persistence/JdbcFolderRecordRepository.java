package com.filedupedetector.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.filedupedetector.model.FolderRecord;

public final class JdbcFolderRecordRepository implements FolderRecordRepository {

    private static final String SELECT_COLUMNS = "SELECT id, name, parent_folder_id FROM folder_record ";

    private final JdbcConnectionManager connectionManager;

    public JdbcFolderRecordRepository(JdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public FolderRecord findById(long folderId) {
    	if (folderId <= 0) {
    		return null;
    	}
        try (Connection conn = connectionManager.openConnection()) {
        	return internFindById(conn, folderId);
		} catch (SQLException e) {
		    throw new IllegalStateException(
		            "Failed to look up FolderRecords for folderId=" + folderId, e);
		}
    }
    
    private FolderRecord internFindById(Connection conn, long folderId) throws SQLException {
        String sql = SELECT_COLUMNS + " WHERE id = ?";
    	if (folderId <= 0) {
    		return null;
    	}
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
            	if (rs.next()) {
            		return mapRow(rs);
				}
				else {
					return null;
            	}
            }
		}
    }

    
	public String getFullPath(FolderRecord folderRecord) {
		StringBuilder fullPath = new StringBuilder();
		FolderRecord currentFolder = folderRecord;
		while (currentFolder != null) {
			if (fullPath.length() > 0) {
				fullPath.insert(0, "/");
			}
			fullPath.insert(0, currentFolder.getName());
			currentFolder = findById(currentFolder.getParentFolderId());
		}
		return fullPath.toString();
	}


    
    
    
    @Override
    public List<FolderRecord> findByParentFolder(long parentFolderId) {
        String sql = SELECT_COLUMNS + "WHERE parent_folder_id = ? ";
        try (Connection conn = connectionManager.openConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            List<FolderRecord> result = new ArrayList<>();
            ps.setLong(1, parentFolderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to look up FolderRecords for parentFolderId=" + parentFolderId, e);
        }
    }


    @Override
    public FolderRecord findByParentFolderAndName(long parentFolderId, String name) {
    	String sql = SELECT_COLUMNS + "WHERE parent_folder_id = ? AND name = ?";
        try (Connection conn = connectionManager.openConnection()) {
	        try (PreparedStatement ps = conn.prepareStatement(sql)) {
	            ps.setLong(1, parentFolderId);
	            ps.setString(2, name);
	            try (ResultSet rs = ps.executeQuery()) {
	            	FolderRecord result = null;
	                if (rs.next()) {
	                    result = mapRow(rs);
	                }
	                return result;
	            }
	        }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to look up FileRecord by folderId="+parentFolderId+" and name=" + name, e);
        }
    }

    @Override
    public void deleteByID(List<Long> idsToDelete) {
        String deleteSql = "DELETE FROM folder_record WHERE id = ?";
        if (!idsToDelete.isEmpty()) {
	        try (Connection conn = connectionManager.openConnection();
        		PreparedStatement delete = conn.prepareStatement(deleteSql)) {
                for (Long id : idsToDelete) {
                    delete.setLong(1, id);
                    delete.addBatch();
                }
                delete.executeBatch();
	        } catch (SQLException e) {
	            throw new IllegalStateException("Failed to delete FolderRecords by id", e);
	        }
        }
    }

    private FolderRecord mapRow(ResultSet rs) throws SQLException {
        FolderRecord record = new FolderRecord();
        record.setId(rs.getLong("id"));
        record.setName(rs.getString("name"));
        record.setParentFolderId(rs.getLong("parent_folder_id"));
        return record;
    }

}