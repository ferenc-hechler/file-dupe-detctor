package com.filedupedetector.persistence;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.FileRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class JdbcFileRecordRepository implements FileRecordRepository {

    private static final String SELECT_COLUMNS = "SELECT id, scan_location_id, relative_path, size_bytes, "
            + "content_hash, last_modified_at, last_seen_at FROM file_record ";

    private final JdbcConnectionManager connectionManager;

    public JdbcFileRecordRepository(JdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public Optional<FileRecord> findByLocationAndPath(long scanLocationId, String relativePath) {
        String sql = SELECT_COLUMNS + "WHERE scan_location_id = ? AND relative_path = ?";
        try (Connection conn = connectionManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, scanLocationId);
            ps.setString(2, relativePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to look up FileRecord for location=" + scanLocationId + " path=" + relativePath, e);
        }
    }

    @Override
    public FileRecord upsert(FileRecord record) {
        try (Connection conn = connectionManager.openConnection()) {
            Long existingId = findIdByLocationAndPath(conn, record.getScanLocationId(), record.getRelativePath());
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

    private Long findIdByLocationAndPath(Connection conn, long scanLocationId, String relativePath) throws SQLException {
        String sql = "SELECT id FROM file_record WHERE scan_location_id = ? AND relative_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, scanLocationId);
            ps.setString(2, relativePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void insertNew(Connection conn, FileRecord record) throws SQLException {
        String sql = "INSERT INTO file_record (scan_location_id, relative_path, size_bytes, content_hash, "
                + "last_modified_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindCommonFields(ps, record, 1);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    record.setId(keys.getLong(1));
                }
            }
        }
    }

    private void updateExisting(Connection conn, FileRecord record) throws SQLException {
        String sql = "UPDATE file_record SET size_bytes = ?, content_hash = ?, last_modified_at = ?, "
                + "last_seen_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, record.getSizeBytes());
            ps.setString(2, record.getContentHash());
            ps.setTimestamp(3, Timestamp.from(record.getLastModifiedAt()));
            ps.setTimestamp(4, Timestamp.from(record.getLastSeenAt()));
            ps.setLong(5, record.getId());
            ps.executeUpdate();
        }
    }

    private void bindCommonFields(PreparedStatement ps, FileRecord record, int startIndex) throws SQLException {
        int i = startIndex;
        ps.setLong(i++, record.getScanLocationId());
        ps.setString(i++, record.getRelativePath());
        ps.setLong(i++, record.getSizeBytes());
        ps.setString(i++, record.getContentHash());
        ps.setTimestamp(i++, Timestamp.from(record.getLastModifiedAt()));
        ps.setTimestamp(i, Timestamp.from(record.getLastSeenAt()));
    }

    @Override
    public int deleteMissing(long scanLocationId, Set<String> relativePathsSeen) {
        String selectSql = "SELECT id, relative_path FROM file_record WHERE scan_location_id = ?";
        String deleteSql = "DELETE FROM file_record WHERE id = ?";
        try (Connection conn = connectionManager.openConnection()) {
            List<Long> idsToDelete = new ArrayList<>();
            try (PreparedStatement select = conn.prepareStatement(selectSql)) {
                select.setLong(1, scanLocationId);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        String relativePath = rs.getString("relative_path");
                        if (!relativePathsSeen.contains(relativePath)) {
                            idsToDelete.add(rs.getLong("id"));
                        }
                    }
                }
            }
            if (!idsToDelete.isEmpty()) {
                try (PreparedStatement delete = conn.prepareStatement(deleteSql)) {
                    for (Long id : idsToDelete) {
                        delete.setLong(1, id);
                        delete.addBatch();
                    }
                    delete.executeBatch();
                }
            }
            return idsToDelete.size();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete missing FileRecords for location=" + scanLocationId, e);
        }
    }

    @Override
    public List<DuplicateGroup> findDuplicateGroups() {
        String groupSql = "SELECT size_bytes, content_hash FROM file_record "
                + "WHERE content_hash IS NOT NULL "
                + "GROUP BY size_bytes, content_hash HAVING COUNT(*) > 1";
        String membersSql = SELECT_COLUMNS + "WHERE size_bytes = ? AND content_hash = ?";
        List<DuplicateGroup> groups = new ArrayList<>();
        try (Connection conn = connectionManager.openConnection()) {
            List<long[]> sizeHashPairs = new ArrayList<>();
            List<String> hashes = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(groupSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sizeHashPairs.add(new long[]{rs.getLong("size_bytes")});
                    hashes.add(rs.getString("content_hash"));
                }
            }
            for (int i = 0; i < sizeHashPairs.size(); i++) {
                long size = sizeHashPairs.get(i)[0];
                String hash = hashes.get(i);
                List<FileRecord> members = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(membersSql)) {
                    ps.setLong(1, size);
                    ps.setString(2, hash);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            members.add(mapRow(rs));
                        }
                    }
                }
                if (members.size() >= 2) {
                    groups.add(new DuplicateGroup(size, hash, members));
                }
            }
            return groups;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to compute duplicate groups", e);
        }
    }

    @Override
    public List<FileRecord> findByLocation(long scanLocationId) {
        String sql = SELECT_COLUMNS + "WHERE scan_location_id = ?";
        List<FileRecord> result = new ArrayList<>();
        try (Connection conn = connectionManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, scanLocationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load FileRecords for location=" + scanLocationId, e);
        }
    }

    private FileRecord mapRow(ResultSet rs) throws SQLException {
        FileRecord record = new FileRecord();
        record.setId(rs.getLong("id"));
        record.setScanLocationId(rs.getLong("scan_location_id"));
        record.setRelativePath(rs.getString("relative_path"));
        record.setSizeBytes(rs.getLong("size_bytes"));
        record.setContentHash(rs.getString("content_hash"));
        Timestamp lastModified = rs.getTimestamp("last_modified_at");
        record.setLastModifiedAt(lastModified == null ? null : lastModified.toInstant());
        Timestamp lastSeen = rs.getTimestamp("last_seen_at");
        record.setLastSeenAt(lastSeen == null ? null : lastSeen.toInstant());
        return record;
    }
}