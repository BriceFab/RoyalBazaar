package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.market.Curve;
import com.mystipixel.royalbazaar.market.StateRow;
import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalcommon.storage.Lease;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Price reversion and history snapshots. With several servers on one database only the server holding the
 * {@code engine} lease runs them, so a price is never reverted twice in one step.
 */
public final class MarketEngine {

    /** What the engine needs from the plugin each step; read on the main thread, used on the database thread. */
    public record Settings(List<Curve> curves, long stepMs, long snapshotIntervalMs, int retentionDays) {
    }

    private final BazaarDatabase database;
    private final Logger logger;
    private final Consumer<List<StateRow>> onReverted;
    private volatile Settings settings;
    private Lease.Keeper keeper;
    // database thread only
    private long lastStep;

    /** @param onReverted gets the rows a step wrote, on the database thread */
    public MarketEngine(BazaarDatabase database, Settings settings, Logger logger,
                        Consumer<List<StateRow>> onReverted) {
        this.database = database;
        this.settings = settings;
        this.logger = logger;
        this.onReverted = onReverted;
    }

    public void update(Settings settings) {
        this.settings = settings;
    }

    /** One step: revert prices, then write a snapshot if one is due. Database thread. */
    public void step() {
        Settings now = settings;
        try {
            List<StateRow> written = database.revert(now.curves());
            if (!written.isEmpty()) {
                onReverted.accept(written);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Price reversion failed", e);
        }
        long ts = System.currentTimeMillis();
        long pruneBefore = now.retentionDays() > 0 ? ts - now.retentionDays() * 24L * 60L * 60L * 1000L : 0;
        try {
            List<String> ids = now.curves().stream().map(Curve::id).toList();
            // half a step of slack, so a snapshot due a moment after this step isn't put off a whole step
            long due = Math.max(0, now.snapshotIntervalMs() - now.stepMs() / 2);
            database.snapshotIfDue(ids, ts, due, pruneBefore);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "History snapshot failed", e);
        }
    }

    /**
     * Runs a step every settings step on whichever server holds the lease. The lease outlives three missed
     * steps, so a stopped server's job moves to another within that time.
     */
    public void start(Plugin plugin, DbExecutor db, String serverId) {
        stop();
        long stepMs = settings.stepMs();
        keeper = database.lease(serverId).keep("engine", Duration.ofMillis(stepMs * 3), this::stepIfDue, logger);
        keeper.start(plugin, db);
    }

    // the keeper renews once per step; this keeps a renewal that came early from adding a step
    private void stepIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastStep < settings.stepMs() * 9 / 10) {
            return;
        }
        lastStep = now;
        step();
    }

    public void stop() {
        if (keeper != null) {
            keeper.stop();
            keeper = null;
        }
    }
}
