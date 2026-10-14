package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalbazaar.config.CategoryConfig;
import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.data.BazaarDatabase.TradeOutcome;
import com.mystipixel.royalbazaar.hooks.EconGuardHook;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.hooks.VaultHook;
import com.mystipixel.royalbazaar.market.Curve;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.market.StateRow;
import com.mystipixel.royalbazaar.market.TradeMode;
import com.mystipixel.royalbazaar.market.TradeResult;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalcommon.storage.BusyGuard;
import com.mystipixel.royalcommon.storage.DbExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The buy/sell flow. Starts on the main thread and completes there a tick or two later. Order: resolve,
 * quote, EconGuard veto, funds/items check, take the money (buy) or the items (sell), apply the market
 * impact in one database transaction, then deliver the items (buy) or the money (sell). When a later step
 * fails, the earlier ones are reversed: a refused buy is refunded, a refused sale gets its items back, and
 * a sale whose payment fails is taken back out of the market.
 */
public final class BazaarService {

    private final MarketManager market;
    private final BazaarDatabase database;
    private final DbExecutor db;
    private final PriceRefresher prices;
    private final VaultHook vault;
    private final EcoHook eco;
    private final EconGuardHook guard;
    private final PluginConfig config;
    private final MessageManager messages;
    private final Logger logger;
    // one trade (or sell-all run) per player at a time, so a double click can't spend the same quote twice
    private final BusyGuard<UUID> busy = new BusyGuard<>();
    private volatile boolean closed;

    public BazaarService(MarketManager market, BazaarDatabase database, DbExecutor db, PriceRefresher prices,
                         VaultHook vault, EcoHook eco, EconGuardHook guard, PluginConfig config,
                         MessageManager messages, Logger logger) {
        this.market = market;
        this.database = database;
        this.db = db;
        this.prices = prices;
        this.vault = vault;
        this.eco = eco;
        this.guard = guard;
        this.config = config;
        this.messages = messages;
        this.logger = logger;
    }

    public PluginConfig config() {
        return config;
    }

    /** Refuses new trades; trades already started still finish. For onDisable. */
    public void close() {
        closed = true;
    }

    /**
     * The refusal for trading {@code itemId} in a direction its {@code trade_mode} turns off, or null
     * when that direction is open or the item isn't listed. {@link #buy} and {@link #sell} check this
     * themselves; menus call it to refuse before opening a prompt.
     */
    public TradeResult tradeModeRefusal(String itemId, TradeSide side) {
        MarketItem item = itemId == null ? null : market.get(itemId);
        return item == null ? null : tradeModeRefusal(item, side);
    }

    private TradeResult tradeModeRefusal(MarketItem item, TradeSide side) {
        if (item.tradeMode().allows(side)) {
            return null;
        }
        String reason = side == TradeSide.BUY
                ? messages.get("trade.reasons.buy-disabled", "This item can't be bought here, only sold.")
                : messages.get("trade.reasons.sell-disabled", "This item can't be sold here, only bought.");
        return TradeResult.fail(TradeResult.Status.DISABLED, side, item.id(), reason);
    }

    /** Shown in place of a price for a direction the item can't be traded in. */
    public String unavailablePrice() {
        return messages.get("price-unavailable", "-");
    }

    /** Completes on the main thread. */
    public CompletableFuture<TradeResult> buy(Player player, String itemId, long amount) {
        return guarded(player, TradeSide.BUY, itemId, () -> startBuy(player, itemId, amount));
    }

    /** Completes on the main thread. */
    public CompletableFuture<TradeResult> sell(Player player, String itemId, long amount) {
        return guarded(player, TradeSide.SELL, itemId, () -> startSell(player, itemId, amount));
    }

    private CompletableFuture<TradeResult> guarded(Player player, TradeSide side, String itemId,
                                                   Supplier<CompletableFuture<TradeResult>> start) {
        TradeResult refused = refuseStart(player, side, itemId);
        if (refused != null) {
            return CompletableFuture.completedFuture(refused);
        }
        UUID id = player.getUniqueId();
        CompletableFuture<TradeResult> trade;
        try {
            trade = start.get();
        } catch (RuntimeException e) {
            busy.exit(id);
            throw e;
        }
        return trade.whenComplete((result, failure) -> busy.exit(id));
    }

    // null when the player may start a trade, which then holds their busy slot
    private TradeResult refuseStart(Player player, TradeSide side, String itemId) {
        if (closed) {
            return TradeResult.fail(TradeResult.Status.ERROR, side, itemId,
                    messages.get("trade.reasons.closed", "The bazaar is closing. Please try again shortly."));
        }
        if (!busy.tryEnter(player.getUniqueId())) {
            return TradeResult.fail(TradeResult.Status.BUSY, side, itemId,
                    messages.get("trade.reasons.busy", "Your last trade is still going through."));
        }
        return null;
    }

    private CompletableFuture<TradeResult> startBuy(Player player, String itemId, long amount) {
        MarketItem item = market.get(itemId);
        if (item == null) {
            return failed(TradeResult.Status.UNKNOWN_ITEM, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.unknown-item", "Unknown item."));
        }
        TradeResult refused = tradeModeRefusal(item, TradeSide.BUY);
        if (refused != null) {
            return CompletableFuture.completedFuture(refused);
        }
        if (amount <= 0) {
            return failed(TradeResult.Status.ERROR, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.invalid-amount", "Invalid amount."));
        }
        if (item.frozen()) {
            return failed(TradeResult.Status.DISABLED, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.frozen", "This item is temporarily frozen."));
        }
        long maxOrder = config.maxOrder();
        if (maxOrder > 0 && amount > maxOrder) {
            return failed(TradeResult.Status.ERROR, TradeSide.BUY, itemId,
                    messages.format("trade.reasons.max-order", "You can buy at most {max} at once.",
                            Map.of("max", String.valueOf(maxOrder))));
        }
        // clamp before pricing so cost and the (int) delivery below describe the same quantity
        amount = Math.min(amount, Integer.MAX_VALUE);
        ItemStack prototype = eco.resolve(itemId, 1);
        if (prototype == null) {
            return failed(TradeResult.Status.DISABLED, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.unavailable", "This item is currently unavailable."));
        }

        double cost = PricingEngine.buyCost(item, amount);
        if (!guard.allow(player, TradeSide.BUY, itemId, amount, cost)) {
            return failed(TradeResult.Status.REJECTED_BY_GUARD, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.blocked", "Trade blocked."));
        }
        if (!vault.has(player, cost)) {
            return failed(TradeResult.Status.INSUFFICIENT_FUNDS, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.not-enough-money", "Not enough money."));
        }

        int maxStack = prototype.getMaxStackSize();
        int fits = spaceFor(player, itemId, maxStack, (int) amount);
        long fill = amount;
        if (fits < amount) {
            switch (config.inventoryFullPolicy()) {
                case "partial" -> fill = fits;
                case "drop" -> { /* give all; overflow dropped on delivery */ }
                default -> {
                    return failed(TradeResult.Status.INVENTORY_FULL, TradeSide.BUY, itemId,
                            messages.get("trade.reasons.not-enough-space", "Not enough inventory space."));
                }
            }
        }
        if (fill <= 0) {
            return failed(TradeResult.Status.INVENTORY_FULL, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.inventory-full", "Inventory full."));
        }

        double charge = fill == amount ? cost : PricingEngine.buyCost(item, fill);
        if (!vault.withdraw(player, charge)) {
            return failed(TradeResult.Status.INSUFFICIENT_FUNDS, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.payment-failed", "Payment failed."));
        }

        Curve curve = item.curve();
        double quoted = item.mid();
        long qty = fill;
        UUID buyer = player.getUniqueId();
        return db.submit("buy " + itemId, () -> database.trade(curve, TradeSide.BUY, qty, quoted, buyer, charge))
                .handleAsync((outcome, failure) -> outcome, db.mainThread())
                .thenCompose(outcome -> finishBuy(player, curve, maxStack, qty, charge, outcome));
    }

    // main thread; outcome is null when the transaction failed
    private CompletableFuture<TradeResult> finishBuy(Player player, Curve curve, int maxStack, long qty,
                                                     double charge, TradeOutcome outcome) {
        String itemId = curve.id();
        if (outcome == null || !outcome.ok()) {
            refund(player, charge, itemId);
            if (outcome != null) {
                prices.accept(outcome.row());
            }
            return CompletableFuture.completedFuture(refusal(TradeSide.BUY, itemId, outcome,
                    messages.get("trade.reasons.failed-refunded", "The trade didn't go through. Your money has been returned.")));
        }
        prices.accept(outcome.row());
        if (!player.isOnline()) {
            // an offline player's inventory isn't saved again, so take the trade back instead of delivering
            return undo(curve, TradeSide.BUY, qty, outcome.logId()).thenApply(ignored -> {
                refund(player, charge, itemId);
                return TradeResult.fail(TradeResult.Status.ERROR, TradeSide.BUY, itemId,
                        messages.get("trade.reasons.failed-refunded", "The trade didn't go through. Your money has been returned."));
            });
        }
        giveItems(player, itemId, maxStack, (int) qty);
        recordLocally(player, itemId, TradeSide.BUY, qty, charge);
        return CompletableFuture.completedFuture(TradeResult.ok(TradeSide.BUY, itemId, qty, charge));
    }

    private void refund(Player player, double amount, String itemId) {
        if (!vault.deposit(player, amount)) {
            logger.severe("Could not refund " + amount + " to " + player.getName() + " (" + player.getUniqueId()
                    + ") for a bazaar buy of " + itemId + " that did not go through. Pay it back by hand.");
        }
    }

    private TradeResult refusal(TradeSide side, String itemId, TradeOutcome outcome, String otherwise) {
        if (outcome != null && outcome.status() == TradeOutcome.Status.FROZEN) {
            return TradeResult.fail(TradeResult.Status.DISABLED, side, itemId,
                    messages.get("trade.reasons.frozen", "This item is temporarily frozen."));
        }
        if (outcome != null && outcome.status() == TradeOutcome.Status.PRICE_MOVED) {
            return TradeResult.fail(TradeResult.Status.PRICE_MOVED, side, itemId,
                    messages.get("trade.reasons.price-moved", "The price just changed. Check it and try again."));
        }
        return TradeResult.fail(TradeResult.Status.ERROR, side, itemId, otherwise);
    }

    // completes on the main thread once the price is back, whether or not the undo itself worked
    private CompletableFuture<Void> undo(Curve curve, TradeSide side, long qty, long logId) {
        return db.submit("undo " + side + " " + curve.id(), () -> database.undo(curve, side, qty, logId))
                .handleAsync((row, failure) -> {
                    if (failure != null) {
                        logger.log(Level.SEVERE, "Could not take back a " + side + " of " + qty + " " + curve.id()
                                + "; its price stays moved by that trade.", failure);
                    } else {
                        prices.accept(row);
                    }
                    return null;
                }, db.mainThread());
    }

    /** What a {@link #sellAll} pass did, for the summary message. */
    /** {@code refusal} is set when the run could not start at all (another trade in progress, shutting down). */
    public record SellAllResult(int distinctItems, long units, double proceeds, int blocked, TradeResult refusal) {

        public SellAllResult(int distinctItems, long units, double proceeds, int blocked) {
            this(distinctItems, units, proceeds, blocked, null);
        }

        public boolean soldNothing() {
            return distinctItems == 0;
        }

        SellAllResult plus(TradeResult result) {
            if (result.ok()) {
                return new SellAllResult(distinctItems + 1, units + result.filled(), proceeds + result.total(), blocked);
            }
            // vetoed by EconGuard or frozen by an admin
            if (result.status() == TradeResult.Status.REJECTED_BY_GUARD
                    || result.status() == TradeResult.Status.DISABLED) {
                return new SellAllResult(distinctItems, units, proceeds, blocked + 1);
            }
            return this;
        }
    }

    /**
     * Sell everything in the inventory that {@code scope} covers, one item after another. Blocked or
     * frozen items are counted and skipped rather than aborting the run; buy-only items are skipped
     * without counting. Completes on the main thread.
     */
    public CompletableFuture<SellAllResult> sellAll(Player player, Collection<MarketItem> scope) {
        TradeResult refused = refuseStart(player, TradeSide.SELL, null);
        if (refused != null) {
            return CompletableFuture.completedFuture(new SellAllResult(0, 0, 0, 0, refused));
        }
        List<String> ids = new ArrayList<>();
        for (MarketItem listed : scope) {
            ids.add(listed.id());
        }
        CompletableFuture<SellAllResult> run = CompletableFuture.completedFuture(new SellAllResult(0, 0, 0, 0));
        for (String id : ids) {
            run = run.thenCompose(sofar -> {
                // looked up again: the caller's collection can predate a reload that changed the mode
                MarketItem item = market.get(id);
                if (item == null || !item.tradeMode().allows(TradeSide.SELL) || eco.countHeld(player, id) <= 0) {
                    return CompletableFuture.completedFuture(sofar);
                }
                return startSell(player, id, Long.MAX_VALUE).thenApply(sofar::plus);
            });
        }
        UUID owner = player.getUniqueId();
        return run.whenComplete((result, failure) -> busy.exit(owner));
    }

    private CompletableFuture<TradeResult> startSell(Player player, String itemId, long amount) {
        MarketItem item = market.get(itemId);
        if (item == null) {
            return failed(TradeResult.Status.UNKNOWN_ITEM, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.unknown-item", "Unknown item."));
        }
        TradeResult refused = tradeModeRefusal(item, TradeSide.SELL);
        if (refused != null) {
            return CompletableFuture.completedFuture(refused);
        }
        if (item.frozen()) {
            return failed(TradeResult.Status.DISABLED, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.frozen", "This item is temporarily frozen."));
        }
        if (amount <= 0) {
            return failed(TradeResult.Status.ERROR, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.invalid-amount", "Invalid amount."));
        }
        int held = eco.countHeld(player, itemId);
        if (held <= 0) {
            return failed(TradeResult.Status.INSUFFICIENT_ITEMS, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.none-to-sell", "You have none to sell."));
        }
        long fill = Math.min(amount, held); // amount == Long.MAX_VALUE for "sell all"
        double proceeds = PricingEngine.sellProceeds(item, fill);

        if (!guard.allow(player, TradeSide.SELL, itemId, fill, proceeds)) {
            return failed(TradeResult.Status.REJECTED_BY_GUARD, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.blocked", "Trade blocked."));
        }

        // plan on cloned stacks and hand back what was taken on refusal; never rebuild stacks from the id,
        // a custom stack may carry unique metadata
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] reserved = reserveItems(player.getInventory().getStorageContents(), itemId, (int) fill, taken);
        if (reserved == null) {
            return failed(TradeResult.Status.INSUFFICIENT_ITEMS, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.inventory-changed", "Your inventory changed. Please try again."));
        }
        player.getInventory().setStorageContents(reserved);

        Curve curve = item.curve();
        double quoted = item.mid();
        UUID seller = player.getUniqueId();
        return db.submit("sell " + itemId, () -> database.trade(curve, TradeSide.SELL, fill, quoted, seller, proceeds))
                .handleAsync((outcome, failure) -> outcome, db.mainThread())
                .thenCompose(outcome -> finishSell(player, curve, fill, proceeds, taken, outcome));
    }

    // main thread; outcome is null when the transaction failed
    private CompletableFuture<TradeResult> finishSell(Player player, Curve curve, long qty, double proceeds,
                                                      List<ItemStack> taken, TradeOutcome outcome) {
        String itemId = curve.id();
        if (outcome == null || !outcome.ok()) {
            giveBack(player, taken, itemId);
            if (outcome != null) {
                prices.accept(outcome.row());
            }
            return CompletableFuture.completedFuture(refusal(TradeSide.SELL, itemId, outcome,
                    messages.get("trade.reasons.failed-returned", "The trade didn't go through. Your items have been returned.")));
        }
        prices.accept(outcome.row());
        if (!vault.deposit(player, proceeds)) {
            return undo(curve, TradeSide.SELL, qty, outcome.logId()).thenApply(ignored -> {
                giveBack(player, taken, itemId);
                return TradeResult.fail(TradeResult.Status.ERROR, TradeSide.SELL, itemId,
                        messages.get("trade.reasons.payment-failed-returned",
                                "Payment failed. Your items have been returned."));
            });
        }
        recordLocally(player, itemId, TradeSide.SELL, qty, proceeds);
        return CompletableFuture.completedFuture(TradeResult.ok(TradeSide.SELL, itemId, qty, proceeds));
    }

    private void giveBack(Player player, List<ItemStack> taken, String itemId) {
        if (!player.isOnline()) {
            int count = taken.stream().mapToInt(ItemStack::getAmount).sum();
            logger.severe("Could not return " + count + " " + itemId + " to " + player.getName() + " ("
                    + player.getUniqueId() + "), who left while a bazaar sale was refused. Give them back by hand.");
            return;
        }
        for (ItemStack stack : taken) {
            for (ItemStack drop : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    private void recordLocally(Player player, String itemId, TradeSide side, long qty, double total) {
        MarketItem item = market.get(itemId);
        if (item != null) {
            if (side == TradeSide.BUY) {
                item.volume().recordBuy(qty);
            } else {
                item.volume().recordSell(qty);
            }
        }
        guard.observe(player, side, itemId, qty, total);
    }

    /** Admin set or reset; {@code mid} must already be clamped. Completes on the main thread with the new row. */
    public CompletableFuture<StateRow> setMid(String itemId, double mid) {
        return db.submit("set price " + itemId, () -> database.setMid(itemId, mid))
                .thenApplyAsync(this::accepted, db.mainThread());
    }

    /** Freezes or unfreezes an item on every server. Completes on the main thread with the new row. */
    public CompletableFuture<StateRow> setFrozen(String itemId, boolean frozen) {
        return db.submit((frozen ? "freeze " : "unfreeze ") + itemId, () -> database.setFrozen(itemId, frozen))
                .thenApplyAsync(this::accepted, db.mainThread());
    }

    private StateRow accepted(StateRow row) {
        prices.accept(row);
        return row;
    }

    private static CompletableFuture<TradeResult> failed(TradeResult.Status status, TradeSide side, String itemId,
                                                         String message) {
        return CompletableFuture.completedFuture(TradeResult.fail(status, side, itemId, message));
    }

    /**
     * Quantity for a "fill my inventory" buy: as many as fit, capped by what the player can afford
     * (binary search, since unit price rises with quantity). 0 when nothing fits or is affordable.
     */
    public long fillAmount(Player player, String itemId) {
        MarketItem item = market.get(itemId);
        if (item == null || !item.tradeMode().allows(TradeSide.BUY)) {
            return 0;
        }
        ItemStack prototype = eco.resolve(itemId, 1);
        if (prototype == null) {
            return 0;
        }
        long capacity = spaceFor(player, itemId, prototype.getMaxStackSize(), Integer.MAX_VALUE);
        long maxOrder = config.maxOrder();
        if (maxOrder > 0) {
            capacity = Math.min(capacity, maxOrder);
        }
        if (capacity <= 0) {
            return 0;
        }
        double balance = vault.balance(player);
        long low = 0;
        long high = capacity;
        while (low < high) {
            long mid = low + (high - low + 1) / 2;
            if (PricingEngine.buyCost(item, mid) <= balance) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    private int spaceFor(Player player, String itemId, int max, int wanted) {
        int space = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                space += max;
            } else if (eco.matches(itemId, stack)) {
                space += Math.max(0, max - stack.getAmount());
            }
            if (space >= wanted) {
                return wanted;
            }
        }
        return space;
    }

    private void giveItems(Player player, String itemId, int max, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(max, remaining);
            ItemStack stack = eco.resolve(itemId, stackSize);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            remaining -= stackSize;
        }
    }

    // works on clones, never live stacks; null means the quote is stale. taken gets a copy of each part removed
    private ItemStack[] reserveItems(ItemStack[] original, String itemId, int amount, List<ItemStack> taken) {
        int remaining = amount;
        ItemStack[] contents = new ItemStack[original.length];
        for (int i = 0; i < original.length; i++) {
            contents[i] = original[i] == null ? null : original[i].clone();
        }
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (!eco.matches(itemId, stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            ItemStack part = stack.clone();
            part.setAmount(take);
            taken.add(part);
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            if (stack.getAmount() <= 0) {
                contents[i] = null;
            }
        }
        return remaining == 0 ? contents : null;
    }

    // falls back to the category name so a breadcrumb never renders an empty gap
    private static String groupNameOf(CategoryConfig category, String groupId) {
        if (category == null) {
            return "";
        }
        if (groupId != null) {
            for (CategoryConfig.Group group : category.groups()) {
                if (group.id().equals(groupId)) {
                    return group.name();
                }
            }
        }
        return category.displayName();
    }

    public Map<String, String> placeholders(MarketItem item, Player viewer) {
        Map<String, String> p = new HashMap<>();
        p.put("rbazaar_item", item.id());
        p.put("rbazaar_item_display", displayNameFor(item));
        boolean buyable = item.tradeMode().allows(TradeSide.BUY);
        boolean sellable = item.tradeMode().allows(TradeSide.SELL);
        String unavailable = unavailablePrice();
        p.put("rbazaar_buy_price", buyable ? vault.formatPrice(PricingEngine.buyPrice(item)) : unavailable);
        p.put("rbazaar_sell_price", sellable ? vault.formatPrice(PricingEngine.sellPrice(item)) : unavailable);
        p.put("rbazaar_spread_pct", String.format("%.1f", item.spread() * 100));
        p.put("rbazaar_buy_cost_1", buyable ? vault.formatPrice(PricingEngine.buyCost(item, 1)) : unavailable);
        p.put("rbazaar_buy_cost_64", buyable ? vault.formatPrice(PricingEngine.buyCost(item, 64)) : unavailable);
        p.put("rbazaar_sell_value_1", sellable ? vault.formatPrice(PricingEngine.sellProceeds(item, 1)) : unavailable);
        p.put("rbazaar_sell_value_64", sellable ? vault.formatPrice(PricingEngine.sellProceeds(item, 64)) : unavailable);
        p.put("rbazaar_trade_mode", tradeModeLabel(item.tradeMode()));
        p.put("rbazaar_volume_24h", String.valueOf(item.volume().bought24h() + item.volume().sold24h()));
        p.put("rbazaar_change_24h", changePct(item));
        p.put("rbazaar_trend", trendArrow(item));
        p.put("rbazaar_change_7d", pctSince(item.mid(), item.midWeekAgo()));
        p.put("rbazaar_trend_7d", trendSince(item.mid(), item.midWeekAgo()));
        String noWeekRange = messages.get("format.no-week-range", "-");
        p.put("rbazaar_low_7d", item.weekLow() > 0 ? vault.formatPrice(item.weekLow()) : noWeekRange);
        p.put("rbazaar_high_7d", item.weekHigh() > 0 ? vault.formatPrice(item.weekHigh()) : noWeekRange);
        if (viewer != null) {
            int held = eco.countHeld(viewer, item.id());
            p.put("rbazaar_held_amount", String.valueOf(held));
            p.put("rbazaar_sell_value_all", sellable
                    ? vault.formatPrice(PricingEngine.sellProceeds(item, Math.max(1, held))) : unavailable);
        }

        // breadcrumb placeholders for product and buy menu titles
        CategoryConfig category = market.category(item.categoryId());
        p.put("rbazaar_category_id", item.categoryId() == null ? "" : item.categoryId());
        p.put("rbazaar_category", category == null ? "" : category.displayName());
        p.put("rbazaar_group", item.groupId() == null ? "" : item.groupId());
        p.put("rbazaar_group_name", groupNameOf(category, item.groupId()));
        return p;
    }

    /** Live summary for a group icon. {@code items} may be empty, which yields zeroes. */
    public Map<String, String> groupPlaceholders(CategoryConfig cat, CategoryConfig.Group group,
                                                 List<MarketItem> items) {
        Map<String, String> p = new HashMap<>();
        p.put("rbazaar_category", cat.displayName());
        p.put("rbazaar_category_id", cat.id());   // raw id: open_menu args need this, not the display name
        p.put("rbazaar_group", group.id());
        p.put("rbazaar_group_name", group.name());
        p.put("rbazaar_group_icon", group.icon());
        p.put("rbazaar_group_size", String.valueOf(items.size()));

        if (items.isEmpty()) {
            p.put("rbazaar_group_min_buy", vault.formatPrice(0));
            p.put("rbazaar_group_max_buy", vault.formatPrice(0));
            p.put("rbazaar_group_volume_24h", "0");
            p.put("rbazaar_group_trend", messages.get("format.trend.group-empty", "&7-"));
            p.put("rbazaar_group_change_24h", messages.get("format.change.no-history", "0.0%"));
            return p;
        }

        double min = Double.MAX_VALUE;
        double max = 0;
        long volume = 0;
        double weightedNow = 0;
        double weightedThen = 0;
        for (MarketItem item : items) {
            if (item.tradeMode().allows(TradeSide.BUY)) {
                double buy = PricingEngine.buyPrice(item);
                min = Math.min(min, buy);
                max = Math.max(max, buy);
            }
            volume += item.volume().bought24h() + item.volume().sold24h();
            weightedNow += item.mid();
            weightedThen += item.midYesterday() > 0 ? item.midYesterday() : item.mid();
        }
        boolean anyBuyable = min != Double.MAX_VALUE;
        p.put("rbazaar_group_min_buy", anyBuyable ? vault.formatPrice(min) : unavailablePrice());
        p.put("rbazaar_group_max_buy", anyBuyable ? vault.formatPrice(max) : unavailablePrice());
        p.put("rbazaar_group_volume_24h", String.valueOf(volume));

        // from summed mids, so one cheap item can't swing the arrow
        double pct = weightedThen <= 0 ? 0 : (weightedNow - weightedThen) / weightedThen * 100.0;
        p.put("rbazaar_group_change_24h", changeText(pct));
        double eps = 0.1;
        p.put("rbazaar_group_trend", pct > eps ? trendUp() : pct < -eps ? trendDown()
                : messages.get("format.trend.group-flat", "&7="));
        return p;
    }

    private String tradeModeLabel(TradeMode mode) {
        return switch (mode) {
            case BOTH -> messages.get("trade-mode.both", "&aBuy and sell");
            case BUY_ONLY -> messages.get("trade-mode.buy-only", "&eBuy only");
            case SELL_ONLY -> messages.get("trade-mode.sell-only", "&eSell only");
        };
    }

    // config display: override, else the item's own name, else a prettified id
    private String displayNameFor(MarketItem item) {
        if (item.displayName() != null && !item.displayName().isBlank()) {
            return item.displayName();
        }
        ItemStack stack = eco.resolve(item.id(), 1);
        if (stack != null && stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(stack.getItemMeta().displayName());
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        String raw = item.id().contains(":") ? item.id().substring(item.id().indexOf(':') + 1) : item.id();
        String[] words = raw.replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private String changePct(MarketItem item) {
        if (item.midYesterday() <= 0) {
            return messages.get("format.change.no-history", "0.0%");
        }
        return changeText((item.mid() - item.midYesterday()) / item.midYesterday() * 100.0);
    }

    private String pctSince(double now, double then) {
        if (then <= 0) {
            return messages.get("format.change.no-history-7d", "&7-");
        }
        return changeText((now - then) / then * 100.0);
    }

    private String changeText(double pct) {
        String percent = String.format("%.1f", pct);
        return pct >= 0
                ? messages.format("format.change.up", "&a+{percent}%", Map.of("percent", percent))
                : messages.format("format.change.down", "&c{percent}%", Map.of("percent", percent));
    }

    // no baseline yet reads as flat
    private String trendSince(double now, double then) {
        if (then <= 0) {
            return trendFlat();
        }
        double eps = then * 0.001;
        if (now - then > eps) {
            return trendUp();
        }
        if (now - then < -eps) {
            return trendDown();
        }
        return trendFlat();
    }

    private String trendArrow(MarketItem item) {
        double d = item.mid() - item.emaShort();
        double eps = item.emaShort() * 0.001;
        if (d > eps) {
            return trendUp();
        }
        if (d < -eps) {
            return trendDown();
        }
        return trendFlat();
    }

    private String trendUp() {
        return messages.get("format.trend.up", "&a▲");
    }

    private String trendDown() {
        return messages.get("format.trend.down", "&c▼");
    }

    private String trendFlat() {
        return messages.get("format.trend.flat", "&7▬");
    }
}
