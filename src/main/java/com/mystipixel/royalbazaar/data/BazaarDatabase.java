package com.mystipixel.royalbazaar.data;

import com.mystipixel.royalbazaar.market.Curve;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.market.StateRow;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalcommon.storage.Dialect;
import com.mystipixel.royalcommon.storage.Lease;
import com.mystipixel.royalcommon.storage.SchemaVersion;
import com.mystipixel.royalcommon.storage.Tx;
import com.mystipixel.royalcommon.storage.TxRunner;
import com.mystipixel.royalcommon.storage.VersionPoller;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Prices, history and the transaction log, in SQLite or MySQL. The {@code rb_state} row is the price of
 * record: every change is one transaction that locks the row and works from what it holds, so servers
 * sharing a database never overwrite each other. Every method blocks: call it on the database thread.
 */
public final class BazaarDatabase {

    public enum Type { SQLITE, MYSQL }

    static final int SCHEMA_VERSION = 2;
    static final int TRANSACTION_ATTEMPTS = 3;

    private static final String SELECT_ROW = "SELECT item_id, mid_price, mid_yesterday, frozen, version, updated_at"
            + " FROM rb_state";
    private static final int CHUNK = 500;

    /** What a trade transaction did. {@code row} is the item's row after it, or as found when refused. */
    public record TradeOutcome(Status status, StateRow row, long logId) {

        public enum Status { OK, FROZEN, PRICE_MOVED, MISSING }

        public boolean ok() {
            return status == Status.OK;
        }
    }

    private final File dataFolder;
    private final ConfigurationSection config;
    private final Logger logger;

    private Type type;
    private HikariDataSource pool;
    private TxRunner db;

    public BazaarDatabase(File dataFolder, ConfigurationSection storageConfig, Logger logger) {
        this.dataFolder = dataFolder;
        this.config = storageConfig;
        this.logger = logger;
    }

    public void init() throws SQLException {
        String rawType = config.getString("type", "SQLITE").toUpperCase();
        this.type = "MYSQL".equals(rawType) ? Type.MYSQL : Type.SQLITE;

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("RoyalBazaar");

        if (type == Type.MYSQL) {
            ConfigurationSection my = config.getConfigurationSection("mysql");
            String host = my.getString("host", "localhost");
            int port = my.getInt("port", 3306);
            String database = my.getString("database", "royalbazaar");
            String props = my.getString("properties", "useSSL=false");
            registerDriver("com.mysql.cj.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + props);
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setUsername(my.getString("username", "root"));
            hikari.setPassword(my.getString("password", ""));
            hikari.setMaximumPoolSize(Math.max(1, my.getInt("pool-size", 10)));
        } else {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.warning("Could not create plugin data folder: " + dataFolder);
            }
            File file = new File(dataFolder, config.getString("sqlite-file", "bazaar.db"));
            registerDriver("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
            // driver properties, not connectionInitSql (see SqliteSettings)
            hikari.setDataSourceProperties(SqliteSettings.properties());
        }

        this.pool = new HikariDataSource(hikari);
        try {
            this.db = new TxRunner(pool, TRANSACTION_ATTEMPTS);
            migrate();
        } catch (SQLException | RuntimeException e) {
            pool.close();
            throw e;
        }
        logger.info("Connected to " + type + " storage.");
    }

    private void registerDriver(String driverClass) {
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.log(Level.WARNING, "JDBC driver not found on classpath: " + driverClass, e);
        }
    }

    public Type type() {
        return type;
    }

    public DataSource dataSource() {
        return pool;
    }

    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    /** A lease on this database, for the job only one server may run (reversion and snapshots). */
    public Lease lease(String serverId) {
        return new Lease(db, "rb", serverId);
    }

    public static VersionPoller.Query<String> versionQuery() {
        return VersionPoller.byColumn("rb_state", "item_id", "version", id -> id);
    }

    // each step checks before it changes anything: on MySQL a server that dies half way runs it again
    private void migrate() throws SQLException {
        int before = new SchemaVersion(db, "rb").ensure(SCHEMA_VERSION, (c, dialect, from, to) -> {
            if (from < 1) {
                createTables(c, dialect);
                addColumn(c, dialect, "frozen", dialect == Dialect.MYSQL ? "TINYINT NOT NULL DEFAULT 0" : "INTEGER NOT NULL DEFAULT 0");
                addColumn(c, dialect, "version", dialect == Dialect.MYSQL ? "BIGINT NOT NULL DEFAULT 0" : "INTEGER NOT NULL DEFAULT 0");
                createIndexIfMissing(c, dialect, "idx_rb_tx_ts", "rb_transactions", "ts");
            }
            if (from < 2) {
                // covering, so the snapshot prune and the stats queries read only the index
                createIndexIfMissing(c, dialect, "idx_rb_history_ts", "rb_history", "ts, item_id, mid_price");
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS rb_stats ("
                            + "item_id VARCHAR(96) PRIMARY KEY,"
                            + "week_then DOUBLE PRECISION NOT NULL,"
                            + "week_low DOUBLE PRECISION NOT NULL,"
                            + "week_high DOUBLE PRECISION NOT NULL,"
                            + "day_then DOUBLE PRECISION NOT NULL)");
                }
            }
        });
        if (before < SCHEMA_VERSION) {
            logger.info("Database schema migrated from version " + before + " to " + SCHEMA_VERSION + ".");
        }
        lease("").createTable();
    }

    private void createTables(Connection c, Dialect dialect) throws SQLException {
        String autoIncrement = dialect == Dialect.MYSQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rb_state ("
                    + "item_id VARCHAR(96) PRIMARY KEY,"
                    + "mid_price DOUBLE PRECISION NOT NULL,"
                    + "mid_yesterday DOUBLE PRECISION NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rb_history ("
                    + "item_id VARCHAR(96) NOT NULL,"
                    + "ts BIGINT NOT NULL,"
                    + "mid_price DOUBLE PRECISION NOT NULL,"
                    + "PRIMARY KEY (item_id, ts))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rb_transactions ("
                    + "id INTEGER PRIMARY KEY " + autoIncrement + ","
                    + "ts BIGINT NOT NULL,"
                    + "player CHAR(36) NOT NULL,"
                    + "item_id VARCHAR(96) NOT NULL,"
                    + "side TINYINT NOT NULL,"
                    + "quantity INT NOT NULL,"
                    + "unit_mid DOUBLE PRECISION NOT NULL,"
                    + "total DOUBLE PRECISION NOT NULL)");
        }
        createIndexIfMissing(c, dialect, "idx_rb_tx_player", "rb_transactions", "player, ts");
        createIndexIfMissing(c, dialect, "idx_rb_tx_item", "rb_transactions", "item_id, ts");
    }

    private void addColumn(Connection c, Dialect dialect, String column, String definition) throws SQLException {
        String sql = dialect == Dialect.MYSQL
                ? "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()"
                        + " AND table_name = 'rb_state' AND column_name = ?"
                : "SELECT 1 FROM pragma_table_info('rb_state') WHERE name = ? COLLATE NOCASE";
        if (exists(c, sql, column)) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE rb_state ADD COLUMN " + column + " " + definition);
        }
    }

    // MySQL has no CREATE INDEX IF NOT EXISTS, so look first. Only in the current database: with
    // databaseTerm=SCHEMA the JDBC metadata lookup spans every database on the server
    private static void createIndexIfMissing(Connection c, Dialect dialect, String name, String table, String columns)
            throws SQLException {
        String sql = dialect == Dialect.MYSQL
                ? "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " AND index_name = ? AND table_name = ?"
                : "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ? COLLATE NOCASE AND tbl_name = ?";
        if (exists(c, sql, name, table)) {
            return;
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        }
    }

    private static boolean exists(Connection c, String sql, String... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setString(i + 1, values[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Creates a row at the item's starting price for every item that has none yet. */
    public void seed(Collection<Curve> curves) throws SQLException {
        String sql = db.dialect().insertIgnore() + " INTO rb_state (item_id, mid_price, mid_yesterday, updated_at,"
                + " frozen, version) VALUES (?,?,?,?,0,0)";
        long now = System.currentTimeMillis();
        db.inTransaction(tx -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
                for (Curve curve : curves) {
                    ps.setString(1, curve.id());
                    ps.setDouble(2, curve.startingMid());
                    ps.setDouble(3, curve.startingMid());
                    ps.setLong(4, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** The rows of the given items; items without a row are left out. */
    public List<StateRow> rows(Collection<String> ids) throws SQLException {
        List<StateRow> out = new ArrayList<>();
        List<String> all = new ArrayList<>(ids);
        try (Connection c = pool.getConnection()) {
            for (int from = 0; from < all.size(); from += CHUNK) {
                List<String> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
                String sql = SELECT_ROW + " WHERE item_id IN ("
                        + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < chunk.size(); i++) {
                        ps.setString(i + 1, chunk.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(readRow(rs));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static StateRow readRow(ResultSet rs) throws SQLException {
        return new StateRow(rs.getString("item_id"), rs.getDouble("mid_price"), rs.getDouble("mid_yesterday"),
                rs.getInt("frozen") != 0, rs.getLong("version"), rs.getLong("updated_at"));
    }

    private static StateRow lockedRow(Tx tx, String id) throws SQLException {
        if (!tx.lock("rb_state", "item_id", id)) {
            return null;
        }
        try (PreparedStatement ps = tx.connection().prepareStatement(SELECT_ROW + " WHERE item_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    private static StateRow writeMid(Tx tx, StateRow row, double mid, long now) throws SQLException {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "UPDATE rb_state SET mid_price = ?, updated_at = ?, version = version + 1 WHERE item_id = ?")) {
            ps.setDouble(1, mid);
            ps.setLong(2, now);
            ps.setString(3, row.id());
            ps.executeUpdate();
        }
        return new StateRow(row.id(), mid, row.midYesterday(), row.frozen(), row.version() + 1, now);
    }

    /**
     * Applies a trade's market impact and logs it, priced from the row as it is now. The caller has already
     * moved the money or items at {@code quotedMid}; the trade is refused when the price has since moved by
     * more than half the spread, which keeps a stale quote from ever beating the spread.
     */
    public TradeOutcome trade(Curve curve, TradeSide side, long qty, double quotedMid, UUID player, double total)
            throws SQLException {
        long now = System.currentTimeMillis();
        return db.inTransaction(tx -> {
            StateRow row = lockedRow(tx, curve.id());
            if (row == null) {
                return new TradeOutcome(TradeOutcome.Status.MISSING, null, 0);
            }
            if (row.frozen()) {
                return new TradeOutcome(TradeOutcome.Status.FROZEN, row, 0);
            }
            double fresh = curve.clamp(row.mid());
            if (Math.abs(fresh - quotedMid) > tolerance(curve, quotedMid)) {
                return new TradeOutcome(TradeOutcome.Status.PRICE_MOVED, row, 0);
            }
            double after = side == TradeSide.BUY
                    ? PricingEngine.midAfterBuy(curve, fresh, qty)
                    : PricingEngine.midAfterSell(curve, fresh, qty);
            StateRow written = writeMid(tx, row, after, now);
            long logId = log(tx, player, curve.id(), side, qty, after, total, now);
            return new TradeOutcome(TradeOutcome.Status.OK, written, logId);
        });
    }

    static double tolerance(Curve curve, double quotedMid) {
        return Math.max(quotedMid * curve.spread() / 2.0, quotedMid * 1e-9);
    }

    /**
     * Takes back a trade whose money step failed afterwards: moves the price the other way by the same
     * quantity and deletes its log row.
     */
    public StateRow undo(Curve curve, TradeSide side, long qty, long logId) throws SQLException {
        long now = System.currentTimeMillis();
        return db.inTransaction(tx -> {
            StateRow row = lockedRow(tx, curve.id());
            if (row == null) {
                return null;
            }
            double fresh = curve.clamp(row.mid());
            double back = side == TradeSide.BUY
                    ? PricingEngine.midAfterSell(curve, fresh, qty)
                    : PricingEngine.midAfterBuy(curve, fresh, qty);
            StateRow written = writeMid(tx, row, back, now);
            try (PreparedStatement ps = tx.connection().prepareStatement("DELETE FROM rb_transactions WHERE id = ?")) {
                ps.setLong(1, logId);
                ps.executeUpdate();
            }
            return written;
        });
    }

    private static long log(Tx tx, UUID player, String itemId, TradeSide side, long qty, double unitMid,
                            double total, long ts) throws SQLException {
        String sql = "INSERT INTO rb_transactions (ts, player, item_id, side, quantity, unit_mid, total)"
                + " VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = tx.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ts);
            ps.setString(2, player.toString());
            ps.setString(3, itemId);
            ps.setInt(4, side == TradeSide.BUY ? 0 : 1);
            ps.setLong(5, qty);
            ps.setDouble(6, unitMid);
            ps.setDouble(7, total);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    /** Admin set or reset. {@code mid} must already be clamped. */
    public StateRow setMid(String id, double mid) throws SQLException {
        long now = System.currentTimeMillis();
        return db.inTransaction(tx -> {
            StateRow row = lockedRow(tx, id);
            return row == null ? null : writeMid(tx, row, mid, now);
        });
    }

    public StateRow setFrozen(String id, boolean frozen) throws SQLException {
        long now = System.currentTimeMillis();
        return db.inTransaction(tx -> {
            StateRow row = lockedRow(tx, id);
            if (row == null) {
                return null;
            }
            try (PreparedStatement ps = tx.connection().prepareStatement(
                    "UPDATE rb_state SET frozen = ?, updated_at = ?, version = version + 1 WHERE item_id = ?")) {
                ps.setInt(1, frozen ? 1 : 0);
                ps.setLong(2, now);
                ps.setString(3, id);
                ps.executeUpdate();
            }
            return new StateRow(id, row.mid(), row.midYesterday(), frozen, row.version() + 1, now);
        });
    }

    /**
     * One reversion step for every unfrozen item. A row a trade changed since it was read is skipped
     * (compare-and-set on its version); the next step reverts it. Returns the rows written.
     */
    public List<StateRow> revert(Collection<Curve> curves) throws SQLException {
        Map<String, Curve> byId = new HashMap<>();
        for (Curve curve : curves) {
            byId.put(curve.id(), curve);
        }
        List<StateRow> current = rows(byId.keySet());
        long now = System.currentTimeMillis();
        List<StateRow> planned = new ArrayList<>();
        for (StateRow row : current) {
            Curve curve = byId.get(row.id());
            if (row.frozen()) {
                continue;
            }
            double next = PricingEngine.revert(curve, curve.clamp(row.mid()));
            if (next != row.mid()) {
                planned.add(new StateRow(row.id(), next, row.midYesterday(), false, row.version() + 1, now));
            }
        }
        if (planned.isEmpty()) {
            return List.of();
        }
        return db.inTransaction(tx -> {
            List<StateRow> written = new ArrayList<>();
            try (PreparedStatement ps = tx.connection().prepareStatement("UPDATE rb_state SET mid_price = ?,"
                    + " updated_at = ?, version = version + 1 WHERE item_id = ? AND version = ? AND frozen = 0")) {
                // one at a time, not batched: a batch can report SUCCESS_NO_INFO (rewriteBatchedStatements),
                // which can't tell a row a trade changed in the meantime from one this step wrote
                for (StateRow row : planned) {
                    ps.setDouble(1, row.mid());
                    ps.setLong(2, now);
                    ps.setString(3, row.id());
                    ps.setLong(4, row.version() - 1);
                    if (ps.executeUpdate() > 0) {
                        written.add(row);
                    }
                }
            }
            return written;
        });
    }

    /**
     * Writes a price snapshot of every listed item when the newest one is at least {@code intervalMs} old,
     * then prunes history and the trade log older than {@code pruneBefore} (0 keeps everything). Returns
     * whether it wrote one.
     */
    public boolean snapshotIfDue(Collection<String> ids, long now, long intervalMs, long pruneBefore)
            throws SQLException {
        if (ids.isEmpty()) {
            return false;
        }
        // every snapshot covers every item, so one item's newest is the snapshot's; this reads the key
        // instead of scanning the whole table
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT MAX(ts) FROM rb_history WHERE item_id = ?")) {
            ps.setString(1, ids.iterator().next());
            try (ResultSet rs = ps.executeQuery()) {
                long newest = rs.next() ? rs.getLong(1) : 0;
                if (newest > 0 && now - newest < intervalMs) {
                    return false;
                }
            }
        }
        List<StateRow> current = rows(ids);
        String sql = db.dialect().insertIgnore() + " INTO rb_history (item_id, ts, mid_price) VALUES (?,?,?)";
        db.inTransaction(tx -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
                for (StateRow row : current) {
                    ps.setString(1, row.id());
                    ps.setLong(2, now);
                    ps.setDouble(3, row.mid());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            if (pruneBefore > 0) {
                try (PreparedStatement ps = tx.connection().prepareStatement("DELETE FROM rb_history WHERE ts < ?")) {
                    ps.setLong(1, pruneBefore);
                    ps.executeUpdate();
                }
                // the trade log only feeds the 24h volume; idx_rb_tx_ts keeps this a range delete
                try (PreparedStatement ps = tx.connection().prepareStatement("DELETE FROM rb_transactions WHERE ts < ?")) {
                    ps.setLong(1, pruneBefore);
                    ps.executeUpdate();
                }
            }
            return null;
        });
        return true;
    }

    /**
     * {@code {midThen, low, high}} per item with a snapshot since {@code sinceTs}. With retention shorter
     * than the window, midThen is the oldest snapshot kept.
     */
    public Map<String, double[]> weekStats(long sinceTs) throws SQLException {
        Map<String, double[]> out = new HashMap<>();
        String range = "SELECT item_id, MIN(mid_price) lo, MAX(mid_price) hi "
                + "FROM rb_history" + byTimestamp() + " WHERE ts >= ? GROUP BY item_id";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(range)) {
            ps.setLong(1, sinceTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("item_id"),
                            new double[]{0.0, rs.getDouble("lo"), rs.getDouble("hi")});
                }
            }
        }
        for (Map.Entry<String, Double> then : earliestMidSince(sinceTs).entrySet()) {
            double[] row = out.get(then.getKey());
            if (row != null) {
                row[0] = then.getValue();
            }
        }
        return out;
    }

    // for GROUP BY item_id the planner may walk the primary key in item order and read the whole table
    // (SQLite does without ANALYZE stats); the timestamp index reads only the window
    private String byTimestamp() {
        return db.dialect() == Dialect.MYSQL ? " FORCE INDEX (idx_rb_history_ts)" : " INDEXED BY idx_rb_history_ts";
    }

    /** Each item's mid at its earliest snapshot at or after {@code sinceTs}; items without one are absent. */
    public Map<String, Double> earliestMidSince(long sinceTs) throws SQLException {
        Map<String, Double> out = new HashMap<>();
        // PK (item_id, ts) makes the join unambiguous
        String sql = "SELECT h.item_id, h.mid_price FROM rb_history h "
                + "JOIN (SELECT item_id, MIN(ts) mts FROM rb_history" + byTimestamp()
                + " WHERE ts >= ? GROUP BY item_id) x "
                + "ON x.item_id = h.item_id AND x.mts = h.ts";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sinceTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("item_id"), rs.getDouble("mid_price"));
                }
            }
        }
        return out;
    }

    /**
     * Works out every item's 7-day and 24-hour figures from rb_history and stores them for {@link #stats},
     * so only one server has to read the history. Returns what it stored.
     */
    public Map<String, double[]> writeStats(long weekAgo, long dayAgo) throws SQLException {
        Map<String, double[]> week = weekStats(weekAgo);
        Map<String, Double> day = earliestMidSince(dayAgo);
        Map<String, double[]> out = new HashMap<>();
        week.forEach((id, w) -> out.put(id, new double[]{w[0], w[1], w[2], day.getOrDefault(id, 0.0)}));
        db.inTransaction(tx -> {
            try (Statement st = tx.connection().createStatement()) {
                st.executeUpdate("DELETE FROM rb_stats");
            }
            try (PreparedStatement ps = tx.connection().prepareStatement("INSERT INTO rb_stats"
                    + " (item_id, week_then, week_low, week_high, day_then) VALUES (?,?,?,?,?)")) {
                for (Map.Entry<String, double[]> row : out.entrySet()) {
                    ps.setString(1, row.getKey());
                    for (int i = 0; i < 4; i++) {
                        ps.setDouble(i + 2, row.getValue()[i]);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
        return out;
    }

    /** {@code {midThen, low, high, midDayAgo}} per item as {@link #writeStats} last stored them; 0 when unknown. */
    public Map<String, double[]> stats() throws SQLException {
        Map<String, double[]> out = new HashMap<>();
        String sql = "SELECT item_id, week_then, week_low, week_high, day_then FROM rb_stats";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1),
                        new double[]{rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)});
            }
        }
        return out;
    }

    /** {@code {bought, sold}} per item over every server since {@code sinceTs}. */
    public Map<String, long[]> volumeSince(long sinceTs) throws SQLException {
        Map<String, long[]> out = new HashMap<>();
        String sql = "SELECT item_id, side, SUM(quantity) FROM rb_transactions WHERE ts >= ? GROUP BY item_id, side";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sinceTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long[] totals = out.computeIfAbsent(rs.getString(1), k -> new long[2]);
                    totals[rs.getInt(2) == 0 ? 0 : 1] += rs.getLong(3);
                }
            }
        }
        return out;
    }
}
