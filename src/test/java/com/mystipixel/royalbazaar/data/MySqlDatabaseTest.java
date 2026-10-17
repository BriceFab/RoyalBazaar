package com.mystipixel.royalbazaar.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs only when ROYALBAZAAR_TEST_MYSQL_URL is set, e.g. jdbc:mysql://127.0.0.1:3306/ (database part ignored),
// with ROYALBAZAAR_TEST_MYSQL_USER and ROYALBAZAAR_TEST_MYSQL_PASSWORD. Each test gets its own database, dropped after.
@Tag("mysql")
@EnabledIfEnvironmentVariable(named = "ROYALBAZAAR_TEST_MYSQL_URL", matches = ".+")
class MySqlDatabaseTest extends DatabaseScenarios {

    private final String database = "rbztest_" + UUID.randomUUID().toString().substring(0, 8);
    private URI server;
    private String user;
    private String password;
    private String extraProperties = "";

    @Override
    void configureStorage(YamlConfiguration storage) throws Exception {
        if (server == null) {
            server = URI.create(System.getenv("ROYALBAZAAR_TEST_MYSQL_URL").substring("jdbc:".length()));
            user = System.getenv().getOrDefault("ROYALBAZAAR_TEST_MYSQL_USER", "root");
            password = System.getenv().getOrDefault("ROYALBAZAAR_TEST_MYSQL_PASSWORD", "");
            onServer("CREATE DATABASE " + database);
        }
        storage.set("type", "MYSQL");
        storage.set("mysql.host", server.getHost());
        storage.set("mysql.port", port());
        storage.set("mysql.database", database);
        storage.set("mysql.username", user);
        storage.set("mysql.password", password);
        storage.set("mysql.properties", "useSSL=false&allowPublicKeyRetrieval=true" + extraProperties);
        storage.set("mysql.pool-size", 4);
    }

    private int port() {
        return server.getPort() < 0 ? 3306 : server.getPort();
    }

    private void onServer(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:mysql://" + server.getHost() + ":" + port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true", user, password);
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    @Test
    void anIndexOfTheSameNameInAnotherDatabaseDoesNotStopTheMigration() throws Exception {
        a.close();
        b.close();
        String other = database + "_other";
        try {
            onServer("CREATE DATABASE " + other);
            onServer("CREATE TABLE " + other + ".rb_history (item_id VARCHAR(96), ts BIGINT, mid_price DOUBLE)");
            onServer("CREATE INDEX idx_rb_history_ts ON " + other + ".rb_history (ts)");
            onServer("DROP INDEX idx_rb_history_ts ON " + database + ".rb_history");
            onServer("DROP TABLE " + database + ".rb_stats");
            onServer("UPDATE " + database + ".rb_schema_version SET version = 1");
            // the driver then reports databases as schemas, and a catalog-only lookup sees every one
            extraProperties = "&databaseTerm=SCHEMA";
            a = open();
            b = open();
            assertTrue(hasIndexInOwnDatabase(a, "rb_history", "idx_rb_history_ts"));
            assertTrue(a.stats().isEmpty());
        } finally {
            onServer("DROP DATABASE IF EXISTS " + other);
        }
    }

    private static boolean hasIndexInOwnDatabase(BazaarDatabase database, String table, String index) throws SQLException {
        try (Connection c = database.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM information_schema.statistics"
                     + " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @AfterEach
    void drop() throws SQLException {
        if (server != null) {
            a.close();
            b.close();
            onServer("DROP DATABASE IF EXISTS " + database);
        }
    }
}
