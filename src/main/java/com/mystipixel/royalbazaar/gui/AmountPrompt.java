package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.market.TradeResult;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

import java.util.List;
import java.util.Map;

/**
 * Custom amount flow: read a number from a text dialog (main-thread callback), run the trade, reopen
 * the product page.
 */
public final class AmountPrompt {

    private final BazaarService service;
    private final GuiManager gui;
    private final MessageManager messages;
    private final TextInput textInput;

    public AmountPrompt(BazaarService service, GuiManager gui, MessageManager messages, TextInput textInput) {
        this.service = service;
        this.gui = gui;
        this.messages = messages;
        this.textInput = textInput;
    }

    public void begin(Player player, String itemId, boolean buy) {
        if (itemId == null) {
            return;
        }
        List<String> title = buy
                ? messages.lines("input.amount-buy", List.of("&fBuy a custom amount", "&7How many do you want to buy?"))
                : messages.lines("input.amount-sell", List.of("&fSell a custom amount", "&7How many do you want to sell?"));
        textInput.request(player, title, typed -> finish(player, itemId, buy, typed));
    }

    // typed is null when no answer is coming
    private void finish(Player player, String itemId, boolean buy, String typed) {
        if (typed == null) {
            if (canReopen(player)) gui.openProduct(player, itemId);
            return;
        }
        if (typed.isBlank() || typed.equalsIgnoreCase("cancel")) {
            gui.openProduct(player, itemId);      // cancelled: back where they were
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(typed.replace(",", "").trim());
        } catch (NumberFormatException e) {
            messages.send(player, "not-a-number", "&cNot a number.");
            gui.openProduct(player, itemId);
            return;
        }
        if (amount <= 0) {
            messages.send(player, "amount-positive", "&cAmount must be positive.");
            gui.openProduct(player, itemId);
            return;
        }
        (buy ? service.buy(player, itemId, amount) : service.sell(player, itemId, amount)).thenAccept(r -> {
            if (r.ok()) {
                messages.send(player, "trade.done", "&aDone: &f{amount} &7for &e${total}",
                        Map.of("amount", String.valueOf(r.filled()),
                                "total", String.format("%,.2f", r.total())));
            } else {
                EffectDispatcher.sendFailure(messages, player, r);
            }
            gui.openProduct(player, itemId);
        });
    }

    // after a null answer the player may have quit, died, opened another menu or started a newer prompt
    private boolean canReopen(Player player) {
        if (!player.isOnline() || player.isDead() || textInput.isWaiting(player)) return false;
        InventoryType top = player.getOpenInventory().getTopInventory().getType();
        return top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
    }
}
