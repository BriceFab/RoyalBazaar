package com.mystipixel.royalbazaar;

import com.mystipixel.royalbazaar.api.BazaarApi;
import com.mystipixel.royalbazaar.command.BazaarCommand;
import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.gui.AmountPrompt;
import com.mystipixel.royalbazaar.gui.BazaarGuiListener;
import com.mystipixel.royalbazaar.gui.EffectDispatcher;
import com.mystipixel.royalbazaar.gui.GuiManager;
import com.mystipixel.royalbazaar.gui.TextInput;
import com.mystipixel.royalbazaar.gui.menu.MenuManager;
import com.mystipixel.royalbazaar.gui.menu.MenuTemplate;
import com.mystipixel.royalbazaar.hooks.BazaarPlaceholderExpansion;
import com.mystipixel.royalbazaar.hooks.EconGuardHook;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.hooks.EcoShopHook;
import com.mystipixel.royalbazaar.hooks.VaultHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import com.mystipixel.royalbazaar.service.MainQueue;
import com.mystipixel.royalbazaar.service.MarketEngine;
import com.mystipixel.royalbazaar.service.PriceRefresher;
import com.mystipixel.royalbazaar.util.ItemNames;
import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalcommon.storage.ServerId;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.ServicePriority;
import java.io.File;
import java.util.Locale;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.mystipixel.royalbazaar.gui.BazaarMenuHolder;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;

public final class RoyalBazaarPlugin extends JavaPlugin {

    // identifies the plugin, not the server, so it isn't configurable
    private static final int BSTATS_PLUGIN_ID = 32734;

    private PluginConfig config;
    private com.mystipixel.royalbazaar.message.MessageManager messages;
    private VaultHook vault;
    private EcoHook eco;
    private EcoShopHook ecoShop;
    private EconGuardHook guard;
    private MarketManager market;
    private BazaarDatabase database;
    private BazaarService service;
    private MenuManager menus;
    private GuiManager gui;
    private final ItemNames itemNames = new ItemNames();
    private TextInput textInput;

    private BukkitTask tickTask;
    private BukkitTask historyTask;
    private BazaarPlaceholderExpansion placeholderExpansion;
    private boolean fullyEnabled;

    private MainQueue mainQueue;
    private DbExecutor db;
    private PriceRefresher prices;
    private MarketEngine engine;
    private String serverId;

    @Override
    public void onEnable() {
        this.config = new PluginConfig(this);
        new com.mystipixel.royalbazaar.config.ConfigValidator(this, config).validate();
        this.messages = new com.mystipixel.royalbazaar.message.MessageManager(this);
        this.vault = new VaultHook();

        this.eco = new EcoHook();
        MenuTemplate.EcoHookHolder.set(eco);
        if (eco.isPresent()) {
            getLogger().info("eco detected: custom item ids (ecoitem:...) enabled.");
        }
        this.guard = new EconGuardHook();
        if (guard.isPresent()) {
            getLogger().info("EconGuard detected: trades are reported to it"
                    + (guard.hasVeto()
                    ? "; flagged players are refused when EconGuard's enforcement.block-flagged-trades is on."
                    : "; this EconGuard predates the pre-trade veto, so nothing is blocked."));
        }

        this.ecoShop = new EcoShopHook(getDataFolder().getParentFile(), getLogger());
        this.market = new MarketManager(getLogger());
        market.load(config.loadCategories(), config.emaAlpha(), ecoShop);

        this.database = new BazaarDatabase(getDataFolder(), config.storageSection(), getLogger());
        try {
            this.serverId = ServerId.resolve(config.serverId(), getDataFolder().toPath());
            database.init();
            // before anyone can trade, so the first quotes come from the database; blocking is fine at startup
            database.seed(market.curves());
            market.apply(database.rows(market.ids()));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise storage, disabling RoyalBazaar.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // the economy provider is a separate plugin and may register after we enable, so wait for it
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().warning("No Vault economy provider found yet. RoyalBazaar is waiting for one to"
                    + " register (install an economy plugin, e.g. EssentialsX). /bazaar is unavailable until then.");
            getServer().getPluginManager().registerEvents(new EconomyWaiter(), this);
            // in case the provider registered before our listener was active
            getServer().getScheduler().runTaskLater(this, this::tryLateEnable, 100L);
        }
    }

    // everything that needs a working economy; runs once, whenever the provider shows up
    private void finishEnable() {
        if (fullyEnabled) {
            return;
        }
        fullyEnabled = true;

        this.mainQueue = MainQueue.bukkit(this);
        this.db = new DbExecutor(getName(), mainQueue, getLogger());
        this.prices = new PriceRefresher(market, database, db);
        this.engine = new MarketEngine(database, engineSettings(), getLogger(),
                rows -> mainQueue.execute(() -> prices.acceptAll(rows)));
        this.service = new BazaarService(market, database, db, prices, vault, eco, guard, config, messages, getLogger());
        this.menus = new MenuManager(this);
        itemNames.reload(new File(getDataFolder(), "lang"), getLogger());
        this.gui = new GuiManager(menus, market, service, eco, itemNames, messages);

        this.textInput = new TextInput(this, messages);
        getServer().getPluginManager().registerEvents(textInput, this);
        AmountPrompt prompt = new AmountPrompt(service, gui, messages, textInput);
        EffectDispatcher dispatcher = new EffectDispatcher(gui, service, prompt, messages, market, textInput);
        getServer().getPluginManager().registerEvents(new BazaarGuiListener(gui, dispatcher), this);

        BazaarCommand command = new BazaarCommand(this, gui, market, service);
        if (getCommand("bazaar") != null) {
            getCommand("bazaar").setExecutor(command);
            getCommand("bazaar").setTabCompleter(command);
        }

        scheduleTasks();
        // registered last, once trading works; Bukkit drops it again when the plugin disables
        getServer().getServicesManager().register(BazaarApi.class, new BazaarApi(service), this, ServicePriority.Normal);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.placeholderExpansion = new BazaarPlaceholderExpansion(market, vault, getPluginMeta().getVersion(), service);
            placeholderExpansion.register();
            getLogger().info("Registered PlaceholderAPI expansion.");
        }

        refreshStats();
        setupMetrics();
        getLogger().info("RoyalBazaar enabled" + (database.type() == BazaarDatabase.Type.MYSQL
                ? " as server '" + serverId + "'." : "."));
    }

    private MarketEngine.Settings engineSettings() {
        return new MarketEngine.Settings(market.curves(), config.tickIntervalMs(), config.historyIntervalMs(),
                config.historyRetentionDays());
    }

    // 7-day stats, the 24h baseline and 24h volume: queried on the database thread, applied on the main thread
    private void refreshStats() {
        long now = System.currentTimeMillis();
        long weekAgo = now - 7L * 24L * 60L * 60L * 1000L;
        long dayAgo = now - 24L * 60L * 60L * 1000L;
        db.submit("read history stats", () -> {
            Map<String, double[]> stats = database.weekStats(weekAgo);
            Map<String, Double> day = database.earliestMidSince(dayAgo);
            return Map.entry(stats, day);
        }).thenAcceptAsync(result -> {
            for (MarketItem item : market.all()) {
                double[] row = result.getKey().get(item.id());
                if (row != null) {
                    item.setWeekStats(row[0], row[1], row[2]);
                }
                Double baseline = result.getValue().get(item.id());
                if (baseline != null) {
                    item.setMidYesterday(baseline);
                }
            }
        }, mainQueue);
        prices.refreshVolume();
    }

    private void tryLateEnable() {
        if (fullyEnabled) {
            return;
        }
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().severe("Still no Vault economy provider after waiting. Install an economy plugin"
                    + " (e.g. EssentialsX) and restart. RoyalBazaar is loaded but inactive.");
        }
    }

    private final class EconomyWaiter implements Listener {
        @EventHandler
        public void onServiceRegister(ServiceRegisterEvent event) {
            if (!fullyEnabled && event.getProvider().getService() == Economy.class && vault.setup()) {
                getLogger().info("Vault economy provider detected. Finishing RoyalBazaar startup.");
                finishEnable();
            }
        }
    }

    @Override
    public void onDisable() {
        if (textInput != null) {
            textInput.shutdown();
        }
        cancelTasks();
        // menu icons are real item stacks; without our click listener an open menu is a free chest
        for (Player player : getServer().getOnlinePlayers()) {
            if (BazaarMenuHolder.isMenu(player.getOpenInventory().getTopInventory())) {
                player.closeInventory();
            }
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        if (service != null) {
            service.close();
        }
        if (prices != null) {
            prices.stop();
        }
        if (engine != null) {
            engine.stop();
        }
        // trades already started finish: refunds, payouts and their database steps
        if (db != null) {
            mainQueue.finish(db, Duration.ofSeconds(10));
        }
        if (database != null) {
            database.close();
        }
    }

    private void scheduleTasks() {
        cancelTasks();
        long tick = config.tickIntervalTicks();
        this.tickTask = getServer().getScheduler().runTaskTimer(this, () -> market.tick(), tick, tick);

        long history = config.historyIntervalTicks();
        this.historyTask = getServer().getScheduler().runTaskTimer(this, this::refreshStats, history, history);

        engine.update(engineSettings());
        engine.start(this, db, serverId);
        prices.stop();
        long refresh = config.refreshTicks();
        if (database.type() == BazaarDatabase.Type.MYSQL && refresh > 0) {
            prices.start(this, refresh);
        }
    }

    private void cancelTasks() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (historyTask != null) {
            historyTask.cancel();
        }
    }

    public com.mystipixel.royalbazaar.message.MessageManager messages() {
        return messages;
    }

    /** Reload config, categories and menus. Storage-backend changes still need a restart. */
    public void reloadEverything() {
        config.reload();
        messages.reload();
        this.ecoShop = new EcoShopHook(getDataFolder().getParentFile(), getLogger());
        // surviving items keep their cached state until their rows are read again just below
        market.load(config.loadCategories(), config.emaAlpha(), ecoShop);
        prices.reloadAll();
        menus.reload();
        itemNames.reload(new File(getDataFolder(), "lang"), getLogger());
        scheduleTasks();
        refreshStats();
    }
    // opt out globally in plugins/bStats/config.yml
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("storage_backend",
                () -> getConfig().getString("storage.type", "SQLITE").toUpperCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("categories_configured",
                () -> String.valueOf(market == null ? 0 : market.categories().size())));
        metrics.addCustomChart(new SimplePie("default_category_set",
                () -> String.valueOf(!getConfig().getString("default-category", "").isBlank())));
    }

}
