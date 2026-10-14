package com.mystipixel.royalbazaar.data;

import com.mystipixel.royalbazaar.data.BazaarDatabase.TradeOutcome;
import com.mystipixel.royalbazaar.market.Curve;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.market.StateRow;
import com.mystipixel.royalbazaar.market.TradeSide;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Two servers sharing one database, each with its own pool, as two Paper servers would be. */
abstract class DatabaseScenarios {

    static final Logger LOG = Logger.getLogger("DatabaseScenarios");
    static final Curve WHEAT = new Curve("minecraft:wheat", 10, 0.05, 1000, 0.02, 1, 100);
    // spread so wide no quote in the concurrency test goes stale
    static final Curve LOOSE = new Curve("minecraft:carrot", 10, 1.0, 1000, 0.02, 1, 100);
    static final UUID PLAYER = UUID.randomUUID();

    @TempDir
    Path dataFolder;
    BazaarDatabase a;
    BazaarDatabase b;

    abstract void configureStorage(YamlConfiguration storage) throws Exception;

    BazaarDatabase open() throws Exception {
        YamlConfiguration storage = new YamlConfiguration();
        configureStorage(storage);
        BazaarDatabase database = new BazaarDatabase(dataFolder.toFile(), storage, LOG);
        database.init();
        return database;
    }

    @BeforeEach
    void start() throws Exception {
        a = open();
        b = open();
        a.seed(List.of(WHEAT, LOOSE));
        b.seed(List.of(WHEAT, LOOSE));
    }

    @AfterEach
    void stop() {
        a.close();
        b.close();
    }

    static StateRow row(BazaarDatabase database, Curve curve) throws SQLException {
        return database.rows(List.of(curve.id())).getFirst();
    }

    static long count(BazaarDatabase database, String table) throws SQLException {
        try (Connection c = database.dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void seedingTwiceKeepsTheFirstRow() throws SQLException {
        a.setMid(WHEAT.id(), 20);
        b.seed(List.of(WHEAT));
        assertEquals(20, row(b, WHEAT).mid(), 1e-9);
    }

    @Test
    void aTradeOnOneServerIsTheStartingPointForTheNext() throws SQLException {
        TradeOutcome first = a.trade(WHEAT, TradeSide.BUY, 64, 10, PLAYER, 700);
        assertTrue(first.ok());
        assertEquals(PricingEngine.midAfterBuy(WHEAT, 10, 64), first.row().mid(), 1e-9);
        assertEquals(1, first.row().version());
        TradeOutcome second = b.trade(WHEAT, TradeSide.SELL, 64, first.row().mid(), PLAYER, 600);
        assertTrue(second.ok());
        assertEquals(10, second.row().mid(), 1e-9, "buying then selling the same amount lands back at the start");
        assertEquals(2, row(a, WHEAT).version());
        assertEquals(2, count(a, "rb_transactions"));
        long[] volume = b.volumeSince(0).get(WHEAT.id());
        assertArrayEquals(new long[]{64, 64}, volume);
    }

    @Test
    void aQuoteThePriceHasMovedPastIsRefusedAndNothingIsWritten() throws SQLException {
        a.setMid(WHEAT.id(), 12);
        TradeOutcome refused = b.trade(WHEAT, TradeSide.BUY, 1, 10, PLAYER, 10);
        assertEquals(TradeOutcome.Status.PRICE_MOVED, refused.status());
        assertEquals(12, refused.row().mid(), 1e-9, "the refusal carries the fresh price");
        assertEquals(0, count(a, "rb_transactions"));
        assertEquals(12, row(a, WHEAT).mid(), 1e-9);
    }

    @Test
    void aQuoteWithinHalfTheSpreadStillTrades() throws SQLException {
        a.setMid(WHEAT.id(), 10.2);           // 2% away, half the spread is 2.5%
        assertTrue(b.trade(WHEAT, TradeSide.BUY, 1, 10, PLAYER, 10).ok());
    }

    @Test
    void aFreezeOnOneServerStopsTradesOnTheOther() throws SQLException {
        StateRow frozen = a.setFrozen(WHEAT.id(), true);
        assertTrue(frozen.frozen());
        TradeOutcome refused = b.trade(WHEAT, TradeSide.SELL, 1, 10, PLAYER, 10);
        assertEquals(TradeOutcome.Status.FROZEN, refused.status());
        assertTrue(row(b, WHEAT).frozen());
        a.setMid(WHEAT.id(), 40);
        assertTrue(a.revert(List.of(WHEAT)).isEmpty(), "a frozen price holds still");
        b.setFrozen(WHEAT.id(), false);
        assertTrue(a.trade(WHEAT, TradeSide.SELL, 1, 40, PLAYER, 40).ok());
    }

    @Test
    void anUnknownItemIsReportedMissing() throws SQLException {
        Curve unknown = new Curve("minecraft:dirt", 1, 0.05, 1000, 0.02, 0.1, 10);
        assertEquals(TradeOutcome.Status.MISSING, a.trade(unknown, TradeSide.BUY, 1, 1, PLAYER, 1).status());
    }

    @Test
    void undoPutsThePriceBackAndDropsTheLogRow() throws SQLException {
        TradeOutcome trade = a.trade(WHEAT, TradeSide.SELL, 500, 10, PLAYER, 3000);
        StateRow back = b.undo(WHEAT, TradeSide.SELL, 500, trade.logId());
        assertEquals(10, back.mid(), 1e-9);
        assertEquals(2, back.version());
        assertEquals(0, count(a, "rb_transactions"));
    }

    @Test
    void reversionMovesTowardBaseAndBumpsTheVersion() throws SQLException {
        a.setMid(WHEAT.id(), 20);
        List<StateRow> written = b.revert(List.of(WHEAT));
        assertEquals(1, written.size());
        StateRow reverted = written.getFirst();
        assertEquals(PricingEngine.revert(WHEAT, 20), reverted.mid(), 1e-9);
        assertTrue(reverted.mid() < 20);
        assertEquals(reverted.version(), row(a, WHEAT).version());
        assertEquals(reverted.mid(), row(a, WHEAT).mid(), 1e-9);
    }

    @Test
    void pricesAtBaseAreNotRewrittenEveryStep() throws SQLException {
        assertTrue(a.revert(List.of(WHEAT, LOOSE)).isEmpty(), "nothing to revert at the starting price");
        a.setMid(WHEAT.id(), 10.0005);           // a hair above base
        List<StateRow> written = a.revert(List.of(WHEAT, LOOSE));
        assertEquals(1, written.size());
        assertEquals(10, written.getFirst().mid(), "lands on base instead of creeping toward it");
        assertTrue(a.revert(List.of(WHEAT, LOOSE)).isEmpty());
    }

    @Test
    void aSnapshotIsWrittenOncePerIntervalWhicheverServerAsks() throws SQLException {
        List<String> ids = List.of(WHEAT.id(), LOOSE.id());
        assertTrue(a.snapshotIfDue(ids, 1_000_000, 60_000, 0));
        assertFalse(b.snapshotIfDue(ids, 1_030_000, 60_000, 0));
        assertEquals(2, count(a, "rb_history"));
        assertTrue(b.snapshotIfDue(ids, 1_060_000, 60_000, 1_000_001));
        assertEquals(2, count(a, "rb_history"), "the older snapshot is pruned");
        assertEquals(10, b.earliestMidSince(0).get(WHEAT.id()), 1e-9);
    }

    @Test
    void historyStatsWrittenByOneServerAreReadByTheOther() throws SQLException {
        List<String> ids = List.of(WHEAT.id(), LOOSE.id());
        a.snapshotIfDue(ids, 1_000_000, 60_000, 0);
        a.setMid(WHEAT.id(), 14);
        a.snapshotIfDue(ids, 1_060_000, 60_000, 0);
        a.setMid(WHEAT.id(), 12);
        a.snapshotIfDue(ids, 1_120_000, 60_000, 0);
        Map<String, double[]> written = a.writeStats(1_000_000, 1_060_000);
        assertArrayEquals(new double[]{10, 10, 14, 14}, written.get(WHEAT.id()), 1e-9);
        assertArrayEquals(written.get(WHEAT.id()), b.stats().get(WHEAT.id()), 1e-9);
        a.writeStats(1_060_000, 1_120_000);
        assertArrayEquals(new double[]{14, 12, 14, 12}, b.stats().get(WHEAT.id()), 1e-9, "replaced, not added to");
        assertEquals(2, b.stats().size());
    }

    @Test
    void historyIsIndexedByTimestamp() throws SQLException {
        assertTrue(hasIndex(a, "rb_history", "idx_rb_history_ts"));
    }

    static boolean hasIndex(BazaarDatabase database, String table, String index) throws SQLException {
        try (Connection c = database.dataSource().getConnection();
             ResultSet rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, true)) {
            while (rs.next()) {
                if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    void tradesFromBothServersAtOnceAreAllApplied() throws Exception {
        int perServer = 40;
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> done = new ArrayList<>();
            for (BazaarDatabase server : List.of(a, b)) {
                done.add(threads.submit(() -> {
                    go.await();
                    int ok = 0;
                    for (int i = 0; i < perServer; i++) {
                        double quoted = row(server, LOOSE).mid();
                        if (server.trade(LOOSE, TradeSide.BUY, 1, quoted, PLAYER, quoted).ok()) {
                            ok++;
                        }
                    }
                    return ok;
                }));
            }
            go.countDown();
            for (Future<Integer> result : done) {
                assertEquals(perServer, result.get(120, TimeUnit.SECONDS));
            }
        } finally {
            threads.shutdownNow();
        }
        StateRow end = row(a, LOOSE);
        assertEquals(2L * perServer, end.version(), "no trade overwrote another");
        assertEquals(10 * Math.exp(2.0 * perServer / LOOSE.elasticity()), end.mid(), 1e-6);
        assertEquals(2L * perServer, count(a, "rb_transactions"));
    }
}
