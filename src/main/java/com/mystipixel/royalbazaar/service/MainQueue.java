package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalcommon.storage.MainThread;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Where database results come back to: a queue the next server tick runs. Unlike a plain scheduler task,
 * nothing is dropped once the plugin is disabling; {@link #finish} runs what is left on the calling thread,
 * so a refund or payout still reaches the purse during a shutdown.
 */
public final class MainQueue implements MainThread {

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Consumer<Runnable> nextTick;

    /** nextTick schedules a runnable on the main thread; it may do nothing, then {@link #drain} runs the queue. */
    public MainQueue(Consumer<Runnable> nextTick) {
        this.nextTick = nextTick;
    }

    public static MainQueue bukkit(Plugin plugin) {
        return new MainQueue(task -> {
            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, task);
            }
        });
    }

    @Override
    public void execute(Runnable task) {
        tasks.add(task);
        if (scheduled.compareAndSet(false, true)) {
            nextTick.accept(() -> {
                scheduled.set(false);
                drain();
            });
        }
    }

    /** Runs everything queued, including what those tasks queue. True when anything ran. Main thread only. */
    public boolean drain() {
        boolean ran = false;
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
            ran = true;
        }
        return ran;
    }

    /**
     * For onDisable, after new trades are refused: lets queued database work and the main-thread steps
     * that follow it (a refund, a sale payout, the transaction that takes a trade back) run to the end,
     * then shuts the database thread down. Gives up waiting after timeout; the report lists what was left.
     */
    public DbExecutor.ShutdownReport finish(DbExecutor db, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !waitForDb(db, left)) {
                break;
            }
        } while (drain());
        DbExecutor.ShutdownReport report = db.shutdown(Duration.ofNanos(Math.max(0L, deadline - System.nanoTime())));
        drain();
        return report;
    }

    // the database thread runs in order, so once this marker has run everything submitted before it has too
    private static boolean waitForDb(DbExecutor db, long nanos) {
        try {
            db.submit("finish queued bazaar work", () -> null).get(nanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
