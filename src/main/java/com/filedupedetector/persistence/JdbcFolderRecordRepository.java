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

    public FolderRecord findById(Long folderId) {
        	if (folderId == null) {
        		return null;
        	}
        try (Connection conn = connectionManager.openConnection()) {
        	return internFindById(conn, folderId);
		} catch (SQLException e) {
		    throw new IllegalStateException(
		            "Failed to look up FolderRecords for folderId=" + folderId, e);
		}
    }
    
    private FolderRecord internFindById(Connection conn, Long folderId) throws SQLException {
        String sql = SELECT_COLUMNS + " WHERE id = ?";
        	if (folderId == null) {
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
    public List<Long> findIDsByParentFolder(Long parentFolderId) {
        String sql = "SELECT id from folder_record WHERE parent_folder_id = ? ";
        try (Connection conn = connectionManager.openConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            List<Long> result = new ArrayList<>();
            ps.setObject(1, parentFolderId, java.sql.Types.BIGINT);  // allow null for parent_folder_id
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("id"));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to look up FolderRecordIDs for parentFolderId=" + parentFolderId, e);
        }
    }


    
    
    @Override
    public List<FolderRecord> findByParentFolder(Long parentFolderId) {
        String sql = SELECT_COLUMNS + "WHERE parent_folder_id = ? ";
        try (Connection conn = connectionManager.openConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            List<FolderRecord> result = new ArrayList<>();
            ps.setObject(1, parentFolderId, java.sql.Types.BIGINT);  // allow null for parent_folder_id
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
    public FolderRecord findByParentFolderAndName(Long parentFolderId, String name) {
    	String sql = SELECT_COLUMNS + "WHERE parent_folder_id = ? AND name = ?";
        try (Connection conn = connectionManager.openConnection()) {
	        try (PreparedStatement ps = conn.prepareStatement(sql)) {
	            ps.setObject(1, parentFolderId, java.sql.Types.BIGINT);  // allow null for parent_folder_id
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
    public void deleteByID(Long idToDelete) {
        deleteByID(List.of(idToDelete));
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

    @Override
    public FolderRecord upsert(FolderRecord record) {
        try (Connection conn = connectionManager.openConnection()) {
            if (record.getId() > 0) {
                // nothing to update for a folder
//        		// Update existing record
//        		String updateSql = "UPDATE folder_record SET name = ?, parent_folder_id = ? WHERE id = ?";
//        		try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
//        			ps.setString(1, record.getName());
//        			ps.setLong(2, record.getParentFolderId());
//        			ps.setLong(3, record.getId());
//        			ps.executeUpdate();
//        		}
            } else {
                // Insert new record
                String insertSql = "INSERT INTO folder_record (name, parent_folder_id) VALUES (?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql, PreparedStatement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, record.getName());
                    ps.setObject(2, record.getParentFolderId(), java.sql.Types.BIGINT);  // allow null for parent_folder_id
                    ps.executeUpdate();
                    try (ResultSet generatedKeys = ps.getGeneratedKeys()) {
                        if (generatedKeys.next()) {
                            record.setId(generatedKeys.getLong(1));
                        } else {
                            throw new SQLException("Creating folder_record failed, no ID obtained.");
                        }
                    }
                }
            }
            return record;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert FolderRecord: " + record, e);
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