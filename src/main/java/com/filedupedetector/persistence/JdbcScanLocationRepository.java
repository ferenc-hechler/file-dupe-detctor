package com.filedupedetector.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.filedupedetector.model.ScanLocation;
import com.filedupedetector.model.ScanLocation.LocationType;

public final class JdbcScanLocationRepository implements ScanLocationRepository {

	private static final String SELECT_COLUMNS = "SELECT "
			+ "id, display_name, root_path, vol_name, vol_type, vol_size, vol_vsn, type, root_folder_id "
			+ "FROM scan_location ";

    private final JdbcConnectionManager connectionManager;

    public JdbcScanLocationRepository(JdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public ScanLocation save(ScanLocation location) {
        try (Connection conn = connectionManager.openConnection()) {
        	Optional<ScanLocation> existingSL = findByDisplayName(conn, location.getDisplayName());
            if (existingSL.isPresent()) {
                location.setId(existingSL.get().getId());
                updateExisting(conn, location);
            } else {
                insertNew(conn, location);
            }
            return location;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save ScanLocation: " + location, e);
        }
    }

    public Optional<ScanLocation> findByDisplayName(Connection conn, String displayName) throws SQLException {
        String sql = SELECT_COLUMNS + " WHERE display_name = ? ";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, displayName);
            try (ResultSet rs = ps.executeQuery()) {
            	Optional<ScanLocation> result;
            	if (rs.next()) {
            		result = Optional.of(mapRow(rs));
            	}
            	else {
            		result = Optional.empty();
            	}
                return result;
            }
        }
    }

    private void insertNew(Connection conn, ScanLocation location) throws SQLException {
        String sql = "INSERT INTO scan_location "
                + "(display_name, root_path, vol_name, vol_type, vol_size, vol_vsn, type, rootFolderId) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, location.getDisplayName());
            ps.setString(2, location.getRootPath());
            ps.setString(3, location.getVolName());
            ps.setString(4, location.getVolType());
            ps.setLong(  5, location.getVolSize());
            ps.setString(6, location.getVolVSN());
            ps.setString(7, location.getType().name());
            ps.setLong(  8, location.getRootFolderId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    location.setId(keys.getLong(1));
                }
            }
        }
    }

    private void updateExisting(Connection conn, ScanLocation location) throws SQLException {
        String sql = "UPDATE scan_location SET display_name = ?, root_path = ?, vol_name = ?, vol_type = ?, vol_size = ?, vol_vsn = ?, "
                + "type = ?, root_folder_id = ? "
                + "WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, location.getDisplayName());
            ps.setString(2, location.getRootPath());
            ps.setString(3, location.getVolName());
            ps.setString(4, location.getVolType());
            ps.setLong(5, location.getVolSize());
            ps.setString(6, location.getVolVSN());
            ps.setString(7, location.getType().name());
            ps.setLong(8, location.getRootFolderId());
            ps.setLong(9, location.getId());
            ps.executeUpdate();
        }
    }

    @Override
    public List<ScanLocation> findAll() {
        String sql = SELECT_COLUMNS + " ORDER BY id ";
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

    private ScanLocation mapRow(ResultSet rs) throws SQLException {
        ScanLocation location = new ScanLocation();
        location.setId(rs.getLong("id"));
        location.setDisplayName(rs.getString("display_name"));
        location.setRootPath(rs.getString("root_path"));
        location.setVolName(rs.getString("vol_name"));
        location.setVolType(rs.getString("vol_type"));
        location.setVolSize(rs.getLong("vol_size"));
        location.setVolVSN(rs.getString("vol_vsn"));
        location.setType(LocationType.valueOf(rs.getString("type")));
        location.setRootFolderId(rs.getLong("root_folder_id"));
        return location;
    }
}
