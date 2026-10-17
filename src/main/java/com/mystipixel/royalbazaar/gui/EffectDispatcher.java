package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.gui.menu.MenuEffect;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.TradeResult;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import com.mystipixel.royalbazaar.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Runs the effect list bound to a clicked slot. Unknown effect ids are ignored so a typo can't break a menu. */
public final class EffectDispatcher {

    private final GuiManager gui;
    private final BazaarService service;
    private final AmountPrompt prompt;
    private final MessageManager messages;
    private final MarketManager market;
    private final TextInput textInput;

    public EffectDispatcher(GuiManager gui, BazaarService service, AmountPrompt prompt, MessageManager messages, MarketManager market, TextInput textInput) {
        this.textInput = textInput;
        this.market = market;
        this.gui = gui;
        this.service = service;
        this.prompt = prompt;
        this.messages = messages;
    }

    public void run(Player player, List<MenuEffect> effects) {
        if (effects == null) {
            return;
        }
        // checked against the live config before anything runs, so a menu or button drawn before a
        // reload can't open a prompt or play a success sound for a direction that's now off
        for (MenuEffect effect : effects) {
            TradeSide side = effect.tradeSide();
            if (side == null) {
                continue;
            }
            TradeResult refused = service.tradeModeRefusal(effect.tradeItem(itemOf(player)), side);
            if (refused != null) {
                sendFeedback(player, refused);
                gui.refresh(player);
                return;
            }
        }
        for (MenuEffect effect : effects) {
            dispatch(player, effect);
        }
    }

    private void dispatch(Player player, MenuEffect e) {
        switch (e.id().toLowerCase(Locale.ROOT)) {
            case "close_inventory" -> player.closeInventory();
            case "open_menu" -> openMenu(player, e.argString("menu", "bazaar_category"),
                    e.argString("category", null), e.argString("group", null));
            case "play_sound" -> playSound(player, e);
            case "send_message" -> player.sendMessage(Text.chat(e.argString("message", "")));

            case "rbazaar_open_product" -> gui.openProduct(player, e.argString("item", null));
            case "rbazaar_buy" -> trade(player, e, true);
            case "rbazaar_sell" -> trade(player, e, false);
            case "rbazaar_buy_prompt" -> prompt.begin(player, e.argString("item", null), true);
            case "rbazaar_sell_prompt" -> prompt.begin(player, e.argString("item", null), false);
            case "rbazaar_sell_all" -> sellAll(player, e.argString("scope", "category"));
            case "rbazaar_open_trends" -> gui.openTrends(player);
            case "rbazaar_search" -> beginSearch(player);
            case "rbazaar_back" -> goBack(player);
            case "rbazaar_open_buy" -> gui.openBuy(player, e.argString("item", itemOf(player)));
            case "rbazaar_buy_amount_prompt" -> askBuyAmount(player, e.argString("item", itemOf(player)));

            case "rbazaar_next_page" -> turnPage(player, page(player) + 1);
            case "rbazaar_prev_page" -> turnPage(player, Math.max(1, page(player) - 1));

            default -> { /* unknown effect id: ignore */ }
        }
    }

    private void trade(Player player, MenuEffect e, boolean buy) {
        String item = e.argString("item", null);
        if (item == null) {
            return;
        }
        String amountArg = e.argString("amount", "1");
        long amount;
        if ("all".equalsIgnoreCase(amountArg)) {
            amount = Long.MAX_VALUE;
        } else if ("fill".equalsIgnoreCase(amountArg)) {
            // as much as fits and can be paid for
            amount = service.fillAmount(player, item);
            if (amount <= 0) {
                messages.send(player, "buy.cannot-fill",
                        "&cNo room in your inventory, or not enough money to fill it.");
                return;
            }
        } else {
            amount = e.argLong("amount", 1);
        }
        (buy ? service.buy(player, item, amount) : service.sell(player, item, amount)).thenAccept(result -> {
            sendFeedback(player, result);
            gui.refresh(player);
        });
    }

    private void openMenu(Player player, String menu, String category, String group) {
        switch (menu) {
            // retired hub: old configs pointing at it land on the default category
            case "bazaar_main" -> gui.openDefault(player);
            case "bazaar_category" -> gui.openCategory(player, category, 1);
            case "bazaar_group" -> gui.openGroup(player, category, group, 1);
            case "bazaar_trends" -> gui.openTrends(player);
            default -> gui.openDefault(player);
        }
    }

    private void playSound(Player player, MenuEffect e) {
        try {
            Sound sound = Sound.valueOf(e.argString("sound", "UI_BUTTON_CLICK").toUpperCase(Locale.ROOT));
            float pitch = (float) toDouble(e.argString("pitch", "1"), 1.0);
            float volume = (float) toDouble(e.argString("volume", "1"), 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // unknown sound name, skip
        }
    }

    private void sendFeedback(Player player, TradeResult r) {
        if (!r.ok()) {
            sendFailure(messages, player, r);
            return;
        }
        Map<String, String> values = Map.of("amount", String.valueOf(r.filled()),
                "total", String.format("%,.2f", r.total()));
        if (r.side() == TradeSide.BUY) {
            messages.send(player, "trade.bought", "&aBought &f{amount} &afor &e${total}", values);
        } else {
            messages.send(player, "trade.sold", "&aSold &f{amount} &afor &e${total}", values);
        }
    }

    static void sendFailure(MessageManager messages, Player player, TradeResult r) {
        String reason = r.message() != null ? r.message() : messages.get("trade.reasons.generic", "Trade failed.");
        messages.send(player, "trade.failed", "&c{reason}", Map.of("reason", reason));
    }

    // sells the whole inventory everywhere except inside a group, where it narrows to that group;
    // scope: all forces the whole inventory there too
    private void sellAll(Player player, String scope) {
        OpenView view = gui.viewOf(player);
        String categoryId = view == null ? null : view.categoryId();
        String groupId = view == null ? null : view.groupId();

        Collection<MarketItem> items;
        String what;
        if (groupId != null && categoryId != null && !"all".equalsIgnoreCase(scope)) {
            items = market.itemsInGroup(categoryId, groupId);
            what = messages.get("sell-all.scope-group", "this group");
        } else {
            items = market.all();
            what = messages.get("sell-all.scope-inventory", "your inventory");
        }
        service.sellAll(player, items).thenAccept(result -> {
            sendSellAllSummary(messages, player, what, result);
            gui.refresh(player);
        });
    }

    /** Also used by /bazaar sellall. {@code scope} fills {scope} in sell-all.nothing. */
    public static void sendSellAllSummary(MessageManager messages, Player player, String scope,
                                          BazaarService.SellAllResult result) {
        if (result.refusal() != null) {
            sendFailure(messages, player, result.refusal());
            return;
        }
        if (result.soldNothing()) {
            messages.send(player, "sell-all.nothing", "&eNothing in {scope} could be sold here.",
                    Map.of("scope", scope));
        } else {
            messages.send(player, "sell-all.done",
                    "&aSold &f{units}&a from &f{items}&a item type(s) for &6{proceeds}&a.",
                    Map.of("units", String.valueOf(result.units()),
                            "items", String.valueOf(result.distinctItems()),
                            "proceeds", String.format("%,.2f", result.proceeds())));
        }
        if (result.blocked() > 0) {
            messages.send(player, "sell-all.blocked", "&c{blocked} item type(s) were blocked and not sold.",
                    Map.of("blocked", String.valueOf(result.blocked())));
        }
        if (result.retry() > 0) {
            messages.send(player, "sell-all.retry",
                    "&e{count} item type(s) were handed back because the price changed or the sale didn't go through. Try again.",
                    Map.of("count", String.valueOf(result.retry())));
        }
    }

    private String itemOf(Player player) {
        OpenView view = gui.viewOf(player);
        return view == null ? null : view.itemId();
    }

    private void askBuyAmount(Player player, String itemId) {
        if (itemId == null) {
            return;
        }
        List<String> title = messages.lines("input.buy-amount",
                List.of("&fBuy a custom amount", "&7How many do you want to buy?"));
        textInput.request(player, title, typed -> {
            if (typed == null) {
                if (canReopen(player)) gui.openBuy(player, itemId);
                return;
            }
            if (typed.isBlank()) {
                gui.openBuy(player, itemId);        // cancelled: back where they were
                return;
            }
            long amount;
            try {
                amount = Long.parseLong(typed.replace(",", "").trim());
            } catch (NumberFormatException bad) {
                messages.send(player, "not-a-number", "&cNot a number.");
                gui.openBuy(player, itemId);
                return;
            }
            if (amount <= 0) {
                messages.send(player, "amount-positive", "&cAmount must be positive.");
                gui.openBuy(player, itemId);
                return;
            }
            service.buy(player, itemId, amount).thenAccept(result -> {
                sendFeedback(player, result);
                gui.openBuy(player, itemId);
            });
        });
    }

    // parent is worked out from the open view, not fixed per menu: a product can be reached from a
    // group or from a search
    private void goBack(Player player) {
        OpenView view = gui.viewOf(player);
        if (view == null) {
            gui.openDefault(player);
            return;
        }
        String itemId = view.itemId();
        if ("bazaar_buy".equals(view.menuId()) && itemId != null) {
            gui.openProduct(player, itemId);        // the buy menu sits under the product page
            return;
        }
        if (itemId != null) {
            MarketItem item = market.get(itemId);
            if (item != null && item.categoryId() != null) {
                if (item.groupId() != null) {
                    gui.openGroup(player, item.categoryId(), item.groupId(), 1);
                } else {
                    gui.openCategory(player, item.categoryId(), 1);
                }
                return;
            }
        }
        if (view.groupId() != null && view.categoryId() != null) {
            gui.openCategory(player, view.categoryId(), 1);
            return;
        }
        gui.openDefault(player);
    }

    private void beginSearch(Player player) {
        List<String> title = messages.lines("input.search",
                List.of("&fSearch the bazaar", "&7Type an item name"));
        textInput.request(player, title, query -> {
            if (query == null) {
                if (canReopen(player)) gui.openDefault(player);
                return;
            }
            if (query.isBlank()) {
                gui.openDefault(player);      // cancelled
                return;
            }
            gui.openSearch(player, query, 1);
        });
    }

    // after a null answer the player may have quit, died, opened another menu or started a newer prompt
    private boolean canReopen(Player player) {
        if (!player.isOnline() || player.isDead() || textInput.isWaiting(player)) return false;
        InventoryType top = player.getOpenInventory().getTopInventory().getType();
        return top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
    }

    // stays inside a search result set rather than falling back to a category
    private void turnPage(Player player, int page) {
        OpenView view = gui.viewOf(player);
        if (view != null && view.query() != null) {
            gui.openSearch(player, view.query(), page);
        } else {
            gui.openCategory(player, current(player), page);
        }
    }

    private String current(Player player) {
        OpenView v = gui.viewOf(player);
        return v == null ? null : v.categoryId();
    }

    private int page(Player player) {
        OpenView v = gui.viewOf(player);
        return v == null ? 1 : v.page();
    }

    private double toDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
