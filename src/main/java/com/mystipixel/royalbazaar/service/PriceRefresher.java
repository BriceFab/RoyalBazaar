package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.StateRow;
import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalcommon.storage.VersionPoller;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps the cached prices in step with the database: rows this server writes are applied at once, and
 * rows another server changed are picked up by a version poll.
 */
public final class PriceRefresher {

    private final MarketManager market;
    private final BazaarDatabase database;
    private final DbExecutor db;
    private final VersionPoller<String> poller;

    public PriceRefresher(MarketManager market, BazaarDatabase database, DbExecutor db) {
        this.market = market;
        this.database = database;
        this.db = db;
        this.poller = new VersionPoller<>(database.dataSource(), db, BazaarDatabase.versionQuery(), this::changed);
    }

    /** A row this server just wrote or read. Main thread. */
    public void accept(StateRow row) {
        if (row == null) {
            return;
        }
        MarketItem item = market.get(row.id());
        if (item != null) {
            item.apply(row);
        }
        poller.seen(row.id(), row.version());
    }

    public void acceptAll(Collection<StateRow> rows) {
        for (StateRow row : rows) {
            accept(row);
        }
    }

    /** Creates missing rows for the listed items and reads every row into the cache. */
    public CompletableFuture<Void> reloadAll() {
        var curves = market.curves();
        List<String> ids = market.ids();
        return db.submit("load prices", () -> {
                    database.seed(curves);
                    return database.rows(ids);
                })
                .thenAcceptAsync(this::acceptAll, db.mainThread());
    }

    /** 24h volume over every server, and its baseline. Main thread to start; applied on the main thread. */
    public CompletableFuture<Void> refreshVolume() {
        long since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        return db.submit("read volume", () -> database.volumeSince(since))
                .thenAcceptAsync(this::applyVolume, db.mainThread());
    }

    private void applyVolume(Map<String, long[]> totals) {
        for (MarketItem item : market.all()) {
            long[] row = totals.get(item.id());
            item.volume().reset(row == null ? 0 : row[0], row == null ? 0 : row[1]);
        }
    }

    public void start(Plugin plugin, long periodTicks) {
        poller.start(plugin, periodTicks, market::ids);
    }

    public void stop() {
        poller.stop();
    }

    private void changed(Set<String> ids) {
        db.submit("reload changed prices", () -> database.rows(ids))
                .thenAcceptAsync(this::acceptAll, db.mainThread());
    }
}
