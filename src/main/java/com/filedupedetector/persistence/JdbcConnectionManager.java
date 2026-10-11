package com.filedupedetector.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Opens/initializes the embedded H2 file database used for persistence (FR-005).
 * Running {@code schema.sql} on startup is idempotent ({@code CREATE TABLE IF NOT EXISTS}).
 */
public final class JdbcConnectionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcConnectionManager.class);

    private final String jdbcUrl;
    private Connection keepAliveConnection;

    public JdbcConnectionManager() {
        this(defaultDatabasePath());
    }

    public JdbcConnectionManager(Path databasePath) {
        try {
            Files.createDirectories(databasePath.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create database directory: " + databasePath.getParent(), e);
        }
        // H2 appends .mv.db automatically; AUTO_SERVER allows a second local process to inspect the DB file.
        this.jdbcUrl = "jdbc:h2:file:" + databasePath.toAbsolutePath()
                + ";DB_CLOSE_DELAY=-1;MODE=Regular";
        initSchema();
    }

    public static Path defaultDatabasePath() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".file-dupe-detector", "store");
    }

    public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initSchema() {
        try {
            // Keep one connection open for the lifetime of the app so H2 (DB_CLOSE_DELAY=-1)
            // keeps the in-memory state warm between short-lived connections.
            keepAliveConnection = DriverManager.getConnection(jdbcUrl);
            String ddl = readSchemaSql();
            try (Statement statement = keepAliveConnection.createStatement()) {
                for (String rawStatement : ddl.split(";")) {
                    String sql = rawStatement.strip();
                    if (!sql.isEmpty()) {
                        statement.execute(sql);
                    }
                }
            }
            log.info("Database schema initialized at {}", jdbcUrl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize embedded database schema", e);
        }
    }

    private String readSchemaSql() {
        try (InputStream in = JdbcConnectionManager.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read schema.sql", e);
        }
    }

    @Override
    public void close() {
        if (keepAliveConnection != null) {
            try {
                keepAliveConnection.close();
            } catch (SQLException e) {
                log.warn("Error closing keep-alive database connection", e);
            }
        }
    }
}
