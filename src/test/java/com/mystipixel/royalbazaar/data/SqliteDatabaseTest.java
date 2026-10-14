package com.mystipixel.royalbazaar.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteDatabaseTest extends DatabaseScenarios {

    @Override
    void configureStorage(YamlConfiguration storage) {
        storage.set("type", "SQLITE");
        storage.set("sqlite-file", "shared.db");
    }

    @Test
    void anExistingDatabaseFromBeforeSharingKeepsItsPrices() throws Exception {
        String url = "jdbc:sqlite:" + dataFolder.resolve("old.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE rb_state (item_id VARCHAR(96) PRIMARY KEY, mid_price DOUBLE PRECISION"
                    + " NOT NULL, mid_yesterday DOUBLE PRECISION NOT NULL, updated_at BIGINT NOT NULL)");
            st.executeUpdate("INSERT INTO rb_state VALUES ('minecraft:wheat', 17.5, 16, 123)");
        }
        YamlConfiguration storage = new YamlConfiguration();
        storage.set("type", "SQLITE");
        storage.set("sqlite-file", "old.db");
        BazaarDatabase upgraded = new BazaarDatabase(dataFolder.toFile(), storage, LOG);
        upgraded.init();
        try {
            upgraded.seed(List.of(WHEAT));
            var row = row(upgraded, WHEAT);
            assertEquals(17.5, row.mid(), 1e-9);
            assertEquals(16, row.midYesterday(), 1e-9);
            assertFalse(row.frozen());
            assertEquals(0, row.version());
            assertTrue(upgraded.trade(WHEAT, com.mystipixel.royalbazaar.market.TradeSide.BUY, 1, 17.5, PLAYER, 18).ok());
        } finally {
            upgraded.close();
        }
    }

    @Test
    void aVersionOneDatabaseGetsTheHistoryIndexAndStatsTable() throws Exception {
        a.close();
        b.close();
        String url = "jdbc:sqlite:" + dataFolder.resolve("shared.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.executeUpdate("DROP INDEX idx_rb_history_ts");
            st.executeUpdate("DROP TABLE rb_stats");
            st.executeUpdate("UPDATE rb_schema_version SET version = 1");
        }
        a = open();
        b = open();
        assertTrue(hasIndex(a, "rb_history", "idx_rb_history_ts"));
        assertTrue(a.stats().isEmpty());
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT version FROM rb_schema_version")) {
            assertTrue(rs.next());
            assertEquals(BazaarDatabase.SCHEMA_VERSION, rs.getInt(1));
        }
    }
}
