package com.mystipixel.royalbazaar.data;

import java.util.Properties;

/**
 * Connection settings for the SQLite store, handed to the driver as connection properties so it
 * applies every one of them to every connection it opens.
 *
 * <p>They used to be a single {@code connectionInitSql} string of two {@code PRAGMA} statements.
 * HikariCP runs that string through {@code Statement#execute}, which sqlite-jdbc prepares only the
 * first statement of — so journal mode became WAL and {@code foreign_keys} silently stayed OFF.
 * Nothing ever read the pragmas back, which is why it went unnoticed; {@code SqliteSettingsTest}
 * now does.
 */
final class SqliteSettings {

    /**
     * Connections in the pool. SQLite takes one writer at a time and every bazaar write is a batched
     * async flush, so a single connection serialises them and avoids SQLITE_BUSY outright. Raise this
     * only together with an explicit {@code busy_timeout} — the driver's default is only 3000 ms.
     */
    static final int POOL_SIZE = 1;

    private SqliteSettings() {
    }

    static Properties properties() {
        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        props.setProperty("foreign_keys", "true");
        return props;
    }
}
