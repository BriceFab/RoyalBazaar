package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalbazaar.config.CategoryConfig;
import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.hooks.EconGuardHook;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.hooks.VaultHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.market.TradeMode;
import com.mystipixel.royalbazaar.market.TradeResult;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.message.MessageManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The buy/sell flow. Main thread only. Order: resolve, quote, EconGuard veto, funds/items check,
 * reserve sell items, move money, deliver buy items, apply market impact, record. Not atomic across
 * Vault: a rejected sale payment restores the reserved items, but a provider crash has an unknown outcome.
 */
public final class BazaarService {

    private final JavaPlugin plugin;
    private final MarketManager market;
    private final BazaarDatabase db;
    private final VaultHook vault;
    private final EcoHook eco;
    private final EconGuardHook guard;
    private final PluginConfig config;
    private final MessageManager messages;

    public BazaarService(JavaPlugin plugin, MarketManager market, BazaarDatabase db, VaultHook vault,
                         EcoHook eco, EconGuardHook guard, PluginConfig config, MessageManager messages) {
        this.plugin = plugin;
        this.market = market;
        this.db = db;
        this.vault = vault;
        this.eco = eco;
        this.guard = guard;
        this.config = config;
        this.messages = messages;
    }

    public PluginConfig config() {
        return config;
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

    public TradeResult buy(Player player, String itemId, long amount) {
        MarketItem item = market.get(itemId);
        if (item == null) {
            return TradeResult.fail(TradeResult.Status.UNKNOWN_ITEM, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.unknown-item", "Unknown item."));
        }
        TradeResult refused = tradeModeRefusal(item, TradeSide.BUY);
        if (refused != null) {
            return refused;
        }
        if (amount <= 0) {
            return TradeResult.fail(TradeResult.Status.ERROR, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.invalid-amount", "Invalid amount."));
        }
        if (item.frozen()) {
            return TradeResult.fail(TradeResult.Status.DISABLED, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.frozen", "This item is temporarily frozen."));
        }
        long maxOrder = config.maxOrder();
        if (maxOrder > 0 && amount > maxOrder) {
            return TradeResult.fail(TradeResult.Status.ERROR, TradeSide.BUY, itemId,
                    messages.format("trade.reasons.max-order", "You can buy at most {max} at once.",
                            Map.of("max", String.valueOf(maxOrder))));
        }
        // clamp before pricing so cost and the (int) delivery below describe the same quantity
        amount = Math.min(amount, Integer.MAX_VALUE);
        ItemStack prototype = eco.resolve(itemId, 1);
        if (prototype == null) {
            return TradeResult.fail(TradeResult.Status.DISABLED, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.unavailable", "This item is currently unavailable."));
        }

        double cost = PricingEngine.buyCost(item, amount);
        if (!guard.allow(player, TradeSide.BUY, itemId, amount, cost)) {
            return TradeResult.fail(TradeResult.Status.REJECTED_BY_GUARD, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.blocked", "Trade blocked."));
        }
        if (!vault.has(player, cost)) {
            return TradeResult.fail(TradeResult.Status.INSUFFICIENT_FUNDS, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.not-enough-money", "Not enough money."));
        }

        int fits = spaceFor(player, itemId, prototype.getMaxStackSize(), (int) amount);
        long fill = amount;
        if (fits < amount) {
            switch (config.inventoryFullPolicy()) {
                case "partial" -> fill = fits;
                case "drop" -> { /* give all; overflow dropped below */ }
                default -> {
                    return TradeResult.fail(TradeResult.Status.INVENTORY_FULL, TradeSide.BUY, itemId,
                            messages.get("trade.reasons.not-enough-space", "Not enough inventory space."));
                }
            }
        }
        if (fill <= 0) {
            return TradeResult.fail(TradeResult.Status.INVENTORY_FULL, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.inventory-full", "Inventory full."));
        }

        double finalCost = fill == amount ? cost : PricingEngine.buyCost(item, fill);
        if (!vault.withdraw(player, finalCost)) {
            return TradeResult.fail(TradeResult.Status.INSUFFICIENT_FUNDS, TradeSide.BUY, itemId,
                    messages.get("trade.reasons.payment-failed", "Payment failed."));
        }

        giveItems(player, itemId, prototype.getMaxStackSize(), (int) fill);
        item.setMid(PricingEngine.midAfterBuy(item, fill));
        item.volume().recordBuy(fill);
        record(player, itemId, TradeSide.BUY, fill, item.mid(), finalCost);
        return TradeResult.ok(TradeSide.BUY, itemId, fill, finalCost);
    }

    /** What a {@link #sellAll} pass did, for the summary message. */
    public record SellAllResult(int distinctItems, long units, double proceeds, int blocked) {
        public boolean soldNothing() {
            return distinctItems == 0;
        }
    }

    /**
     * Sell everything in the inventory that {@code scope} covers, each through {@link #sell}. Blocked
     * or frozen items are counted and skipped rather than aborting the run; buy-only items are skipped
     * without counting.
     */
    public SellAllResult sellAll(Player player, Collection<MarketItem> scope) {
        int distinct = 0;
        long units = 0;
        double proceeds = 0;
        int blocked = 0;
        for (MarketItem listed : scope) {
            // looked up again: the caller's collection can predate a reload that changed the mode
            MarketItem item = market.get(listed.id());
            if (item == null || !item.tradeMode().allows(TradeSide.SELL)) {
                continue;
            }
            if (eco.countHeld(player, item.id()) <= 0) {
                continue;
            }
            TradeResult result = sell(player, item.id(), Long.MAX_VALUE);
            if (result.ok()) {
                distinct++;
                units += result.filled();
                proceeds += result.total();
            } else if (result.status() == TradeResult.Status.REJECTED_BY_GUARD
                    || result.status() == TradeResult.Status.DISABLED) {
                blocked++;   // vetoed by EconGuard or frozen by an admin
            }
        }
        return new SellAllResult(distinct, units, proceeds, blocked);
    }

    public TradeResult sell(Player player, String itemId, long amount) {
        MarketItem item = market.get(itemId);
        if (item == null) {
            return TradeResult.fail(TradeResult.Status.UNKNOWN_ITEM, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.unknown-item", "Unknown item."));
        }
        TradeResult refused = tradeModeRefusal(item, TradeSide.SELL);
        if (refused != null) {
            return refused;
        }
        if (item.frozen()) {
            return TradeResult.fail(TradeResult.Status.DISABLED, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.frozen", "This item is temporarily frozen."));
        }
        if (amount <= 0) {
            return TradeResult.fail(TradeResult.Status.ERROR, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.invalid-amount", "Invalid amount."));
        }
        int held = eco.countHeld(player, itemId);
        if (held <= 0) {
            return TradeResult.fail(TradeResult.Status.INSUFFICIENT_ITEMS, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.none-to-sell", "You have none to sell."));
        }
        long fill = Math.min(amount, held); // amount == Long.MAX_VALUE for "sell all"
        double proceeds = PricingEngine.sellProceeds(item, fill);

        if (!guard.allow(player, TradeSide.SELL, itemId, fill, proceeds)) {
            return TradeResult.fail(TradeResult.Status.REJECTED_BY_GUARD, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.blocked", "Trade blocked."));
        }

        // plan on cloned stacks and restore the originals on rejection; never rebuild them from the id,
        // a custom stack may carry unique metadata
        ItemStack[] before = player.getInventory().getStorageContents();
        ItemStack[] reserved = reserveItems(before, itemId, (int) fill);
        if (reserved == null) {
            return TradeResult.fail(TradeResult.Status.INSUFFICIENT_ITEMS, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.inventory-changed", "Your inventory changed. Please try again."));
        }
        player.getInventory().setStorageContents(reserved);
        if (!vault.deposit(player, proceeds)) {
            player.getInventory().setStorageContents(before);
            return TradeResult.fail(TradeResult.Status.ERROR, TradeSide.SELL, itemId,
                    messages.get("trade.reasons.payment-failed-returned",
                            "Payment failed. Your items have been returned."));
        }
        item.setMid(PricingEngine.midAfterSell(item, fill));
        item.volume().recordSell(fill);
        record(player, itemId, TradeSide.SELL, fill, item.mid(), proceeds);
        return TradeResult.ok(TradeSide.SELL, itemId, fill, proceeds);
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

    // works on clones, never live stacks; null means the quote is stale
    private ItemStack[] reserveItems(ItemStack[] original, String itemId, int amount) {
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
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            if (stack.getAmount() <= 0) {
                contents[i] = null;
            }
        }
        return remaining == 0 ? contents : null;
    }

    private void record(Player player, String itemId, TradeSide side, long qty, double unitMid, double total) {
        guard.observe(player, side, itemId, qty, total);
        long ts = System.currentTimeMillis();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> db.logTransaction(player.getUniqueId(), itemId, side, qty, unitMid, total, ts));
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
