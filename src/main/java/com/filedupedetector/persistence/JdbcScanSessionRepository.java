package com.filedupedetector.persistence;

import com.filedupedetector.model.ScanSession;
import com.filedupedetector.model.ScanSession.ScanOutcome;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

public final class JdbcScanSessionRepository implements ScanSessionRepository {

    private final JdbcConnectionManager connectionManager;

    public JdbcScanSessionRepository(JdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public ScanSession start(List<Long> scanLocationIds) {
        String insertSession = "INSERT INTO scan_session (started_at, files_processed, files_skipped_errored, files_missing) "
                + "VALUES (?, 0, 0, 0)";
        String insertJoin = "INSERT INTO scan_session_location (scan_session_id, scan_location_id) VALUES (?, ?)";
        Instant startedAt = Instant.now();
        try (Connection conn = connectionManager.openConnection()) {
            long sessionId;
            try (PreparedStatement ps = conn.prepareStatement(insertSession, Statement.RETURN_GENERATED_KEYS)) {
                ps.setTimestamp(1, Timestamp.from(startedAt));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    sessionId = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(insertJoin)) {
                for (Long locationId : scanLocationIds) {
                    ps.setLong(1, sessionId);
                    ps.setLong(2, locationId);
                    ps.addBatch();
                }
                if (!scanLocationIds.isEmpty()) {
                    ps.executeBatch();
                }
            }

            ScanSession session = new ScanSession();
            session.setId(sessionId);
            session.setStartedAt(startedAt);
            session.setScanLocationIds(scanLocationIds);
            return session;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to start ScanSession", e);
        }
    }

    @Override
    public void complete(long sessionId, ScanOutcome outcome, int processed, int errored, int missing) {
        String sql = "UPDATE scan_session SET ended_at = ?, outcome = ?, files_processed = ?, "
                + "files_skipped_errored = ?, files_missing = ? WHERE id = ?";
        try (Connection conn = connectionManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, outcome.name());
            ps.setInt(3, processed);
            ps.setInt(4, errored);
            ps.setInt(5, missing);
            ps.setLong(6, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to complete ScanSession id=" + sessionId, e);
        }
    }
}
