package com.filedupedetector.persistence;

import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public final class JdbcScanLocationRepository implements ScanLocationRepository {

    private final JdbcConnectionManager connectionManager;

    public JdbcScanLocationRepository(JdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public ScanLocation save(ScanLocation location) {
        try (Connection conn = connectionManager.openConnection()) {
            Long existingId = findIdByRootPath(conn, location.getRootPath());
            if (existingId != null) {
                location.setId(existingId);
                updateExisting(conn, location);
            } else {
                insertNew(conn, location);
            }
            return location;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save ScanLocation: " + location, e);
        }
    }

    private Long findIdByRootPath(Connection conn, String rootPath) throws SQLException {
        String sql = "SELECT id FROM scan_location WHERE root_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rootPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void insertNew(Connection conn, ScanLocation location) throws SQLException {
        String sql = "INSERT INTO scan_location "
                + "(display_name, root_path, vol_name, vol_type, vol_size, vol_vsn, type, last_scanned_at, available) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, location.getDisplayName());
            ps.setString(2, location.getRootPath());
            ps.setString(3, location.getVolName());
            ps.setString(4, location.getVolType());
            ps.setLong(5, location.getVolSize());
            ps.setString(6, location.getVolVSN());
            ps.setString(7, location.getType().name());
            ps.setTimestamp(8, location.getLastScannedAt() == null ? null
                    : Timestamp.from(location.getLastScannedAt()));
            ps.setBoolean(9, location.isAvailable());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    location.setId(keys.getLong(1));
                }
            }
        }
    }

    private void updateExisting(Connection conn, ScanLocation location) throws SQLException {
        String sql = "UPDATE scan_location SET display_name = ?, vol_name = ?, vol_type = ?, vol_size = ?, vol_vsn = ?, "
                + "type = ?, last_scanned_at = ?, available = ? "
                + "WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, location.getDisplayName());
            ps.setString(2, location.getVolName());
            ps.setString(3, location.getVolType());
            ps.setLong(4, location.getVolSize());
            ps.setString(5, location.getVolVSN());
            ps.setString(6, location.getType().name());
            ps.setTimestamp(7, location.getLastScannedAt() == null ? null
                    : Timestamp.from(location.getLastScannedAt()));
            ps.setBoolean(8, location.isAvailable());
            ps.setLong(9, location.getId());
            ps.executeUpdate();
        }
    }

    @Override
    public List<ScanLocation> findAll() {
        String sql = "SELECT id, display_name, root_path, vol_name, vol_type, vol_size, vol_vsn, type, last_scanned_at, available "
                + "FROM scan_location ORDER BY id";
        List<ScanLocation> result = new ArrayList<>();
        try (Connection conn = connectionManager.openConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load ScanLocations", e);
        }
    }

    @Override
    public void markAvailability(long scanLocationId, boolean available) {
        String sql = "UPDATE scan_location SET available = ? WHERE id = ?";
        try (Connection conn = connectionManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, available);
            ps.setLong(2, scanLocationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update availability for ScanLocation id=" + scanLocationId, e);
        }
    }

    private ScanLocation mapRow(ResultSet rs) throws SQLException {
        ScanLocation location = new ScanLocation();
        location.setId(rs.getLong("id"));
        location.setDisplayName(rs.getString("display_name"));
        location.setRootPathX(rs.getString("root_path"));
        location.setVolName(rs.getString("vol_name"));
        location.setVolType(rs.getString("vol_type"));
        location.setVolSize(rs.getLong("vol_size"));
        location.setVolVSN(rs.getString("vol_vsn"));
        location.setType(LocationType.valueOf(rs.getString("type")));
        Timestamp lastScanned = rs.getTimestamp("last_scanned_at");
        location.setLastScannedAt(lastScanned == null ? null : lastScanned.toInstant());
        location.setAvailable(rs.getBoolean("available"));
        return location;
    }
}
