package com.filedupedetector.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import com.filedupedetector.model.FileRecord;

public final class JdbcFileRecordRepository implements FileRecordRepository {

    private static final String SELECT_COLUMNS = "SELECT id, name, parent_folder_id, size_bytes, "
            + "sha1_hash, last_modified_at FROM file_record ";

    private final JdbcConnectionManager connectionManager;

    public JdbcFileRecordRepository(JdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public List<FileRecord> findByParentFolder(long parentFolderId) {
        String sql = SELECT_COLUMNS + "WHERE parent_folder_id = ? ";
        try (Connection conn = connectionManager.openConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            List<FileRecord> result = new ArrayList<>();
            ps.setLong(1, parentFolderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to look up FileRecord for parentFolderId=" + parentFolderId, e);
        }
    }

    @Override
    public List<FileRecord> findBySHA1(String sha1hash) {
        String sql = SELECT_COLUMNS + "WHERE sha1hash = ? ";
        try (Connection conn = connectionManager.openConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            List<FileRecord> result = new ArrayList<>();
            ps.setString(1, sha1hash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to look up FileRecord for sha1hash=" + sha1hash, e);
        }
    }

    @Override
    public FileRecord upsert(FileRecord record) {
        try (Connection conn = connectionManager.openConnection()) {
            Long existingId = findIdByFolderAndName(conn, record.getParentFolderId(), record.getName());
            if (existingId != null) {
                record.setId(existingId);
                updateExisting(conn, record);
            } else {
                insertNew(conn, record);
            }
            return record;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert FileRecord: " + record, e);
        }
    }

    @Override
    public FileRecord findByParentFolderAndName(long parentFolderId, String name) {
    	String sql = SELECT_COLUMNS + "WHERE parent_folder_id = ? AND name = ?";
        try (Connection conn = connectionManager.openConnection()) {
	        try (PreparedStatement ps = conn.prepareStatement(sql)) {
	            ps.setLong(1, parentFolderId);
	            ps.setString(2, name);
	            try (ResultSet rs = ps.executeQuery()) {
	            	FileRecord result = null;
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

    private Long findIdByFolderAndName(Connection conn, long parentFolderId, String name) throws SQLException {
        String sql = "SELECT id FROM file_record WHERE parent_folder_id = ? AND name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, parentFolderId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void insertNew(Connection conn, FileRecord record) throws SQLException {
        String sql = "INSERT INTO file_record (name, parent_folder_id, size_bytes, sha1hash, last_modified_at) "
        		+ "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, record.getName());
            ps.setLong(2, record.getParentFolderId());
            ps.setLong(3, record.getSizeBytes());
            ps.setString(4, record.getSha1hash());
            ps.setTimestamp(5, Timestamp.from(record.getLastModifiedAt()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    record.setId(keys.getLong(1));
                }
            }
        }
    }

    private void updateExisting(Connection conn, FileRecord record) throws SQLException {
        String sql = "UPDATE file_record SET size_bytes = ?, sha1hash = ?, last_modified_at = ?, "
                + "last_seen_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, record.getSizeBytes());
            ps.setString(2, record.getSha1hash());
            ps.setTimestamp(3, Timestamp.from(record.getLastModifiedAt()));
            ps.setLong(4, record.getId());
            ps.executeUpdate();
        }
    }


    @Override
    public void deleteByID(List<Long> idsToDelete) {
        String deleteSql = "DELETE FROM file_record WHERE id = ?";
        if (!idsToDelete.isEmpty()) {
	        try (Connection conn = connectionManager.openConnection()) {
                try (PreparedStatement delete = conn.prepareStatement(deleteSql)) {
                    for (Long id : idsToDelete) {
                        delete.setLong(1, id);
                        delete.addBatch();
                    }
                    delete.executeBatch();
                }
	        } catch (SQLException e) {
	            throw new IllegalStateException("Failed to delete FileRecords by id", e);
	        }
        }
    }

    private FileRecord mapRow(ResultSet rs) throws SQLException {
        FileRecord record = new FileRecord();
        record.setId(rs.getLong("id"));
        record.setName(rs.getString("name"));
        record.setParentFolderId(rs.getLong("parent_folder_id"));
        record.setSizeBytes(rs.getLong("size_bytes"));
        record.setSha1hash(rs.getString("sha1hash"));
        Timestamp lastModified = rs.getTimestamp("last_modified_at");
        record.setLastModifiedAt(lastModified == null ? null : lastModified.toInstant());
        return record;
    }
}