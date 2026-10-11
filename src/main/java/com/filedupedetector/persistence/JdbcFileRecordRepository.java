package com.filedupedetector.persistence;

import com.filedupedetector.model.DuplicateGroup;
import com.filedupedetector.model.FileRecord;
import com.filedupedetector.model.FileRecord.FileStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class JdbcFileRecordRepository implements FileRecordRepository {

    private final JdbcConnectionManager connectionManager;

    public JdbcFileRecordRepository(JdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public Optional<FileRecord> findByLocationAndPath(long scanLocationId, String relativePath) {
        String sql = "SELECT id, scan_location_id, relative_path, size_bytes, content_hash, "
                + "last_modified_at, status, last_scan_session_id, last_seen_at "
                + "FROM file_record WHERE scan_location_id = ? AND relative_path = ?";
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
                + "last_modified_at, status, last_scan_session_id, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
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
                + "status = ?, last_scan_session_id = ?, last_seen_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, record.getSizeBytes());
            ps.setString(2, record.getContentHash());
            ps.setTimestamp(3, Timestamp.from(record.getLastModifiedAt()));
            ps.setString(4, record.getStatus().name());
            if (record.getLastScanSessionId() != null) {
                ps.setLong(5, record.getLastScanSessionId());
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            ps.setTimestamp(6, Timestamp.from(record.getLastSeenAt()));
            ps.setLong(7, record.getId());
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
        ps.setString(i++, record.getStatus().name());
        if (record.getLastScanSessionId() != null) {
            ps.setLong(i++, record.getLastScanSessionId());
        } else {
            ps.setNull(i++, java.sql.Types.BIGINT);
        }
        ps.setTimestamp(i, Timestamp.from(record.getLastSeenAt()));
    }

    @Override
    public int markMissing(long scanLocationId, Set<String> relativePathsSeen, long scanSessionId) {
        String selectSql = "SELECT id, relative_path FROM file_record WHERE scan_location_id = ? AND status = ?";
        String updateSql = "UPDATE file_record SET status = ?, last_scan_session_id = ? WHERE id = ?";
        try (Connection conn = connectionManager.openConnection()) {
            List<Long> idsToMarkMissing = new ArrayList<>();
            try (PreparedStatement select = conn.prepareStatement(selectSql)) {
                select.setLong(1, scanLocationId);
                select.setString(2, FileStatus.PRESENT.name());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        String relativePath = rs.getString("relative_path");
                        if (!relativePathsSeen.contains(relativePath)) {
                            idsToMarkMissing.add(rs.getLong("id"));
                        }
                    }
                }
            }
            try (PreparedStatement update = conn.prepareStatement(updateSql)) {
                for (Long id : idsToMarkMissing) {
                    update.setString(1, FileStatus.MISSING.name());
                    update.setLong(2, scanSessionId);
                    update.setLong(3, id);
                    update.addBatch();
                }
                if (!idsToMarkMissing.isEmpty()) {
                    update.executeBatch();
                }
            }
            return idsToMarkMissing.size();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark missing FileRecords for location=" + scanLocationId, e);
        }
    }

    @Override
    public List<DuplicateGroup> findDuplicateGroups() {
        String groupSql = "SELECT size_bytes, content_hash FROM file_record "
                + "WHERE status = ? AND content_hash IS NOT NULL "
                + "GROUP BY size_bytes, content_hash HAVING COUNT(*) > 1";
        String membersSql = "SELECT id, scan_location_id, relative_path, size_bytes, content_hash, "
                + "last_modified_at, status, last_scan_session_id, last_seen_at "
                + "FROM file_record WHERE status = ? AND size_bytes = ? AND content_hash = ?";
        List<DuplicateGroup> groups = new ArrayList<>();
        try (Connection conn = connectionManager.openConnection()) {
            Map<Long, String> groupKeys = new LinkedHashMap<>();
            List<long[]> sizeHashPairs = new ArrayList<>();
            List<String> hashes = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(groupSql)) {
                ps.setString(1, FileStatus.PRESENT.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sizeHashPairs.add(new long[]{rs.getLong("size_bytes")});
                        hashes.add(rs.getString("content_hash"));
                    }
                }
            }
            for (int i = 0; i < sizeHashPairs.size(); i++) {
                long size = sizeHashPairs.get(i)[0];
                String hash = hashes.get(i);
                List<FileRecord> members = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(membersSql)) {
                    ps.setString(1, FileStatus.PRESENT.name());
                    ps.setLong(2, size);
                    ps.setString(3, hash);
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
    public List<FileRecord> findErroredBySession(long scanSessionId) {
        String sql = "SELECT id, scan_location_id, relative_path, size_bytes, content_hash, "
                + "last_modified_at, status, last_scan_session_id, last_seen_at "
                + "FROM file_record WHERE last_scan_session_id = ? AND status = ?";
        List<FileRecord> result = new ArrayList<>();
        try (Connection conn = connectionManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, scanSessionId);
            ps.setString(2, FileStatus.ERRORED.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load errored FileRecords for session=" + scanSessionId, e);
        }
    }

    @Override
    public List<FileRecord> findByLocation(long scanLocationId) {
        String sql = "SELECT id, scan_location_id, relative_path, size_bytes, content_hash, "
                + "last_modified_at, status, last_scan_session_id, last_seen_at "
                + "FROM file_record WHERE scan_location_id = ?";
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
        record.setStatus(FileStatus.valueOf(rs.getString("status")));
        long sessionId = rs.getLong("last_scan_session_id");
        record.setLastScanSessionId(rs.wasNull() ? null : sessionId);
        Timestamp lastSeen = rs.getTimestamp("last_seen_at");
        record.setLastSeenAt(lastSeen == null ? null : lastSeen.toInstant());
        return record;
    }
}
