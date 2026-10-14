package com.mystipixel.royalbazaar.api;

import com.mystipixel.royalbazaar.service.BazaarService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.CompletableFuture;

/**
 * RoyalBazaar's public API for other plugins, registered with the ServicesManager once the Bazaar is
 * ready to trade. Only JDK and Bukkit types cross it, so a caller can reach it by reflection without
 * compiling against RoyalBazaar. Call it from the main thread.
 *
 * <p>Buys go through the same path as a player's own: EconGuard's veto, the one-trade-at-a-time guard,
 * the trade log, and the price moving along the curve for every server sharing the database.
 */
public final class BazaarApi {

    /**
     * How a buy ended. {@code cost} is what was charged and {@code message} is null on success; on a
     * refusal nothing was charged and {@code message} is the reason, worded for the player.
     */
    public record Purchase(boolean ok, long amount, double cost, String message) {
    }

    private final BazaarService service;

    public BazaarApi(BazaarService service) {
        this.service = service;
    }

    /**
     * The id of the listing that sells {@code item}, or null when nothing listed matches it. Scans every
     * listing, so look ids up once and keep them rather than calling this per tick.
     */
    public String listing(ItemStack item) {
        return service.listingOf(item);
    }

    /**
     * What buying {@code amount} of {@code itemId} costs right now, spread and curve included, or -1 when
     * the Bazaar would refuse the buy whatever the player has: not listed, buy turned off, frozen, or over
     * the order limit. The price rises with quantity, so quote the whole amount rather than one unit.
     */
    public double quote(String itemId, long amount) {
        return service.buyQuote(itemId, amount);
    }

    /**
     * Buys exactly {@code amount} into the player's inventory, or nothing: refused when it doesn't all fit.
     * The price is taken again at the moment of buying and the buy is refused when, times {@code markup},
     * it is above {@code maxCost}; pass the figure the player agreed to. {@code markup} of 1 is the plain
     * Bazaar price, 1.25 charges 25% more (the extra goes to nobody). Completes on the main thread.
     */
    public CompletableFuture<Purchase> buy(Player player, String itemId, long amount, double maxCost, double markup) {
        if (!(maxCost > 0) || !(markup > 0)) {
            throw new IllegalArgumentException("maxCost and markup must be positive, got " + maxCost + " and " + markup);
        }
        BazaarService.BuyTerms terms = new BazaarService.BuyTerms(Math.max(1.0, markup), maxCost, true);
        return service.buy(player, itemId, amount, terms)
                .thenApply(result -> new Purchase(result.ok(), result.filled(), result.total(), result.message()));
    }

    /** The player's balance in the economy the Bazaar charges, to check an order before buying. */
    public double balance(Player player) {
        return service.balance(player);
    }

    /** A price as the Bazaar's own menus write it. */
    public String format(double amount) {
        return service.formatPrice(amount);
    }
}
