package com.mystipixel.royalbazaar.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

// Runs only when ROYALBAZAAR_TEST_MYSQL_URL is set, e.g. jdbc:mysql://127.0.0.1:3306/ (database part ignored),
// with ROYALBAZAAR_TEST_MYSQL_USER and ROYALBAZAAR_TEST_MYSQL_PASSWORD. Each test gets its own database, dropped after.
@Tag("mysql")
@EnabledIfEnvironmentVariable(named = "ROYALBAZAAR_TEST_MYSQL_URL", matches = ".+")
class MySqlDatabaseTest extends DatabaseScenarios {

    private final String database = "rbztest_" + UUID.randomUUID().toString().substring(0, 8);
    private URI server;
    private String user;
    private String password;

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
        storage.set("mysql.properties", "useSSL=false&allowPublicKeyRetrieval=true");
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

    @AfterEach
    void drop() throws SQLException {
        if (server != null) {
            a.close();
            b.close();
            onServer("DROP DATABASE IF EXISTS " + database);
        }
    }
}
