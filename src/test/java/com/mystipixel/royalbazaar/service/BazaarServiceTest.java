package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.hooks.*;
import com.mystipixel.royalbazaar.market.*;
import com.mystipixel.royalbazaar.message.MessageManager;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// provider failure tests drive the real service, inventory planning, pricing and audit path
class BazaarServiceTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final MarketManager market = mock(MarketManager.class);
    private final BazaarDatabase db = mock(BazaarDatabase.class);
    private final VaultHook vault = mock(VaultHook.class);
    private final EcoHook eco = mock(EcoHook.class);
    private final EconGuardHook guard = mock(EconGuardHook.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final PluginConfig config = mock(PluginConfig.class);
    private final MessageManager messages = mock(MessageManager.class);
    private final AtomicReference<ItemStack[]> storage = new AtomicReference<>();
    private final ItemMeta customMetadata = mock(ItemMeta.class);
    private final MarketItem item = new MarketItem("ecoitem:test", "test", null, "Custom item",
            100, 0.05, 1000, 0.01, 1, 1000, TradeMode.BOTH);
    private BazaarService service;

    @BeforeEach
    void setup() {
        service = new BazaarService(plugin, market, db, vault, eco, guard, config, messages);
        when(messages.get(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(market.get(item.id())).thenReturn(item);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        // Storage arrays are copied, but their stacks are shared, just as a shallow snapshot can be.
        when(inventory.getStorageContents()).thenAnswer(i -> storage.get().clone());
        doAnswer(i -> { storage.set(i.getArgument(0)); return null; })
                .when(inventory).setStorageContents(any(ItemStack[].class));
        when(eco.matches(eq(item.id()), any())).thenAnswer(i -> {
            ItemStack stack = i.getArgument(1);
            return stack != null && stack.getItemMeta() == customMetadata;
        });
        when(eco.countHeld(player, item.id())).thenAnswer(i -> {
            int count = 0;
            for (ItemStack stack : storage.get()) {
                if (eco.matches(item.id(), stack)) count += stack.getAmount();
            }
            return count;
        });
        when(guard.allow(eq(player), eq(TradeSide.SELL), eq(item.id()), anyLong(), anyDouble()))
                .thenReturn(true);
        storage.set(new ItemStack[] { stack(5, customMetadata), stack(9, customMetadata),
                stack(3, mock(ItemMeta.class)), null });
    }

    // Stateful stack doubles avoid booting Paper. clone preserves metadata and isolates quantity.
    private ItemStack stack(int amount, ItemMeta metadata) {
        ItemStack stack = mock(ItemStack.class);
        AtomicInteger count = new AtomicInteger(amount);
        when(stack.getAmount()).thenAnswer(i -> count.get());
        doAnswer(i -> { count.set(i.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
        when(stack.getItemMeta()).thenReturn(metadata);
        when(stack.clone()).thenAnswer(i -> stack(count.get(), metadata));
        return stack;
    }

    private void assertNoSaleRecorded() {
        assertEquals(100, item.mid());
        assertEquals(0, item.volume().sold24h());
        verify(guard, never()).observe(any(), any(), anyString(), anyLong(), anyDouble());
        verifyNoInteractions(scheduler, db);
    }

    @Test
    void rejectedPaymentDoesNotConsumeAnyCustomItemsOrMoveTheMarket() {
        ItemStack[] before = storage.get().clone();
        when(vault.deposit(eq(player), anyDouble())).thenReturn(false);
        TradeResult result = service.sell(player, item.id(), 8);
        assertEquals(TradeResult.Status.ERROR, result.status());
        assertEquals(0, result.filled());
        assertEquals(0, result.total());
        assertArrayEquals(before, storage.get());
        assertEquals(5, before[0].getAmount());
        assertEquals(9, before[1].getAmount());
        verify(inventory, times(2)).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void acceptedPaymentRemovesExactlyTheQuotedQuantityAndPreservesMetadata() {
        ItemStack[] before = storage.get().clone();
        double quote = PricingEngine.sellProceeds(item, 8);
        when(vault.deposit(player, quote)).thenAnswer(i -> {
            assertNull(storage.get()[0], "sold items are reserved during the provider call");
            assertEquals(6, storage.get()[1].getAmount());
            return true;
        });
        TradeResult result = service.sell(player, item.id(), 8);
        assertTrue(result.ok());
        assertEquals(8, result.filled());
        assertEquals(quote, result.total());
        assertNull(storage.get()[0]);
        assertEquals(6, storage.get()[1].getAmount());
        assertSame(customMetadata, storage.get()[1].getItemMeta());
        assertEquals(3, storage.get()[2].getAmount());
        assertSame(before[2].getItemMeta(), storage.get()[2].getItemMeta());
        assertEquals(8, item.volume().sold24h());
        assertTrue(item.mid() < 100);
        verify(vault).deposit(player, quote);
        verify(guard).observe(player, TradeSide.SELL, item.id(), 8, quote);
        verify(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
    }

    @Test
    void failedSellAllIsNotReportedAsSoldAndCanBeRetriedOnceProviderRecovers() {
        when(vault.deposit(eq(player), anyDouble())).thenReturn(false, true);
        var first = service.sellAll(player, List.of(item));
        assertTrue(first.soldNothing());
        assertEquals(0, first.units());
        assertEquals(0, first.proceeds());
        assertNoSaleRecorded();
        var second = service.sellAll(player, List.of(item));
        assertEquals(1, second.distinctItems());
        assertEquals(14, second.units());
        assertNull(storage.get()[0]);
        assertNull(storage.get()[1]);
        assertEquals(3, storage.get()[2].getAmount());
        assertEquals(14, item.volume().sold24h());
    }

    @Test
    void nonPositiveSaleAmountsNeverReachEconomyOrInventoryMutation() {
        assertEquals(TradeResult.Status.ERROR, service.sell(player, item.id(), 0).status());
        assertEquals(TradeResult.Status.ERROR, service.sell(player, item.id(), -8).status());
        verifyNoInteractions(vault);
        verify(inventory, never()).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void partialStackRejectionPreservesTheOriginalQuantityAndMetadata() {
        ItemStack original = storage.get()[0];
        when(vault.deposit(eq(player), anyDouble())).thenReturn(false);
        assertFalse(service.sell(player, item.id(), 2).ok());
        assertSame(original, storage.get()[0]);
        assertEquals(5, original.getAmount());
        assertSame(customMetadata, original.getItemMeta());
        assertNoSaleRecorded();
    }

    @Test
    void staleInventoryCountDoesNotPayForItemsThatCannotBeReserved() {
        doReturn(100).when(eco).countHeld(player, item.id());
        assertEquals(TradeResult.Status.INSUFFICIENT_ITEMS,
                service.sell(player, item.id(), 100).status());
        verifyNoInteractions(vault);
        verify(inventory, never()).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void exceptionIsNotTreatedAsAnExplicitRejectionOrAutomaticallyRetried() {
        // An exception may occur after a provider moved money. Do not return items automatically
        // and turn an unknown outcome into a duplicate; this path still needs manual reconciliation.
        when(vault.deposit(eq(player), anyDouble())).thenThrow(new IllegalStateException("unknown outcome"));
        assertThrows(IllegalStateException.class, () -> service.sell(player, item.id(), 8));
        verify(vault).deposit(eq(player), anyDouble());
        verify(inventory).setStorageContents(any());
        assertNull(storage.get()[0]);
        assertEquals(6, storage.get()[1].getAmount());
        assertNoSaleRecorded();
    }

    @Test
    void guardRejectionNeverChargesOrConsumesItems() {
        when(guard.allow(eq(player), eq(TradeSide.SELL), eq(item.id()), anyLong(), anyDouble()))
                .thenReturn(false);
        assertEquals(TradeResult.Status.REJECTED_BY_GUARD, service.sell(player, item.id(), 8).status());
        verifyNoInteractions(vault);
        verify(inventory, never()).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void buyOverTheOrderCapIsRefusedBeforeAnyMoneyMoves() {
        when(config.maxOrder()).thenReturn(64L);
        TradeResult result = service.buy(player, item.id(), 65);
        assertEquals(TradeResult.Status.ERROR, result.status());
        verifyNoInteractions(vault);
        assertEquals(100, item.mid());
    }

    @Test
    void buyOfAnItemThatNoLongerResolvesIsRefusedNotThrown() {
        when(guard.allow(any(), any(), anyString(), anyLong(), anyDouble())).thenReturn(true);
        when(eco.resolve(item.id(), 1)).thenReturn(null);
        TradeResult result = service.buy(player, item.id(), 5);
        assertEquals(TradeResult.Status.DISABLED, result.status());
        verifyNoInteractions(vault);
        assertEquals(100, item.mid());
        assertEquals(0, service.fillAmount(player, item.id()));
    }

    private MarketItem withMode(TradeMode mode) {
        MarketItem fresh = new MarketItem(item.id(), "test", null, "Custom item",
                100, 0.05, 1000, 0.01, 1, 1000, mode);
        when(market.get(item.id())).thenReturn(fresh);
        return fresh;
    }

    @ParameterizedTest
    @EnumSource(value = TradeMode.class, names = {"BUY_ONLY", "SELL_ONLY"})
    void turnedOffDirectionIsRefusedBeforeAnythingMoves(TradeMode mode) {
        MarketItem fresh = withMode(mode);
        ItemStack[] before = storage.get().clone();
        for (long amount : new long[]{1, 64, Long.MAX_VALUE, 0, -1}) {
            TradeResult result = mode == TradeMode.BUY_ONLY
                    ? service.sell(player, item.id(), amount) : service.buy(player, item.id(), amount);
            assertEquals(TradeResult.Status.DISABLED, result.status());
            assertEquals(0, result.filled());
            assertEquals(mode == TradeMode.BUY_ONLY
                    ? "This item can't be sold here, only bought." : "This item can't be bought here, only sold.",
                    result.message());
        }
        assertEquals(100, fresh.mid());
        assertEquals(0, fresh.volume().bought24h());
        assertEquals(0, fresh.volume().sold24h());
        assertFalse(fresh.dirty());
        assertArrayEquals(before, storage.get());
        verifyNoInteractions(vault, eco, guard, inventory, scheduler, db);
    }

    @Test
    void refusalReasonComesFromMessages() {
        withMode(TradeMode.SELL_ONLY);
        when(messages.get(eq("trade.reasons.buy-disabled"), anyString())).thenReturn("Achat impossible");
        assertEquals("Achat impossible", service.buy(player, item.id(), 1).message());
        assertEquals("Achat impossible", service.tradeModeRefusal(item.id(), TradeSide.BUY).message());
        assertNull(service.tradeModeRefusal(item.id(), TradeSide.SELL));
        assertNull(service.tradeModeRefusal("minecraft:not_listed", TradeSide.BUY));
        assertNull(service.tradeModeRefusal(null, TradeSide.BUY));
    }

    @Test
    void bothDirectionsStayOpenByDefault() {
        assertNull(service.tradeModeRefusal(item.id(), TradeSide.BUY));
        assertNull(service.tradeModeRefusal(item.id(), TradeSide.SELL));
        when(vault.deposit(eq(player), anyDouble())).thenReturn(true);
        assertTrue(service.sell(player, item.id(), 8).ok());
    }

    @Test
    void fillOnASellOnlyItemIsZeroWithoutReadingBalanceOrInventory() {
        withMode(TradeMode.SELL_ONLY);
        assertEquals(0, service.fillAmount(player, item.id()));
        verifyNoInteractions(vault, eco, inventory);
    }

    @Test
    void sellAllSkipsBuyOnlyItemsEvenFromACollectionOlderThanTheReload() {
        withMode(TradeMode.BUY_ONLY);
        var result = service.sellAll(player, List.of(item));   // item itself is still BOTH
        assertTrue(result.soldNothing());
        assertEquals(0, result.blocked(), "a buy-only item isn't a blocked sale, it isn't sellable at all");
        verifyNoInteractions(vault, eco, guard, inventory, scheduler, db);
    }

    @Test
    void sellAllSellsTheSellableItemsAndLeavesBuyOnlyOnes() {
        MarketItem sellable = withMode(TradeMode.SELL_ONLY);
        MarketItem buyOnly = new MarketItem("minecraft:stone_bricks", "test", null, "",
                10, 0.05, 1000, 0.01, 1, 100, TradeMode.BUY_ONLY);
        when(market.get(buyOnly.id())).thenReturn(buyOnly);
        when(vault.deposit(eq(player), anyDouble())).thenReturn(true);
        var result = service.sellAll(player, List.of(buyOnly, item));
        assertEquals(1, result.distinctItems());
        assertEquals(14, result.units());
        assertEquals(14, sellable.volume().sold24h());
        verify(eco, never()).countHeld(player, buyOnly.id());
        assertEquals(10, buyOnly.mid());
    }

    @Test
    void buyOnlyItemBuysNormally() {
        MarketItem buyable = withMode(TradeMode.BUY_ONLY);
        storage.set(new ItemStack[4]);
        ItemStack prototype = mock(ItemStack.class);
        when(prototype.getMaxStackSize()).thenReturn(64);
        when(eco.resolve(eq(item.id()), anyInt())).thenReturn(prototype);
        when(guard.allow(eq(player), eq(TradeSide.BUY), eq(item.id()), anyLong(), anyDouble())).thenReturn(true);
        when(vault.has(eq(player), anyDouble())).thenReturn(true);
        when(vault.withdraw(eq(player), anyDouble())).thenReturn(true);
        when(vault.balance(player)).thenReturn(1_000_000.0);
        assertTrue(service.fillAmount(player, item.id()) > 0);
        double cost = PricingEngine.buyCost(buyable, 64);
        TradeResult result = service.buy(player, item.id(), 64);
        assertTrue(result.ok());
        assertEquals(cost, result.total());
        assertEquals(64, buyable.volume().bought24h());
        verify(vault).withdraw(player, cost);
    }

    @Test
    void sellOnlyItemStillHonoursFreeze() {
        MarketItem sellable = withMode(TradeMode.SELL_ONLY);
        sellable.setFrozen(true);
        assertEquals("This item is temporarily frozen.", service.sell(player, item.id(), 8).message());
    }

    @Test
    void menuPlaceholdersShowTheModeAndHideTheTurnedOffPrices() {
        when(vault.formatPrice(anyDouble())).thenAnswer(i -> Double.toString(i.getArgument(0)));
        var both = service.placeholders(item, player);
        assertEquals("&aBuy and sell", both.get("rbazaar_trade_mode"));
        assertNotEquals("-", both.get("rbazaar_buy_price"));
        assertNotEquals("-", both.get("rbazaar_sell_price"));

        var buyOnly = service.placeholders(withMode(TradeMode.BUY_ONLY), player);
        assertEquals("&eBuy only", buyOnly.get("rbazaar_trade_mode"));
        for (String key : List.of("sell_price", "sell_value_1", "sell_value_64", "sell_value_all")) {
            assertEquals("-", buyOnly.get("rbazaar_" + key), key);
        }
        assertNotEquals("-", buyOnly.get("rbazaar_buy_price"));

        var sellOnly = service.placeholders(withMode(TradeMode.SELL_ONLY), player);
        assertEquals("&eSell only", sellOnly.get("rbazaar_trade_mode"));
        for (String key : List.of("buy_price", "buy_cost_1", "buy_cost_64")) {
            assertEquals("-", sellOnly.get("rbazaar_" + key), key);
        }
        assertNotEquals("-", sellOnly.get("rbazaar_sell_price"));
    }

    @Test
    void groupBuyRangeLeavesOutSellOnlyItems() {
        when(vault.formatPrice(anyDouble())).thenAnswer(i -> Double.toString(i.getArgument(0)));
        MarketItem sellOnly = new MarketItem("minecraft:rotten_flesh", "test", null, "",
                1, 0.05, 1000, 0.01, 0.1, 10, TradeMode.SELL_ONLY);
        MarketItem buyOnly = new MarketItem("minecraft:stone_bricks", "test", null, "",
                50, 0.05, 1000, 0.01, 1, 500, TradeMode.BUY_ONLY);
        var cat = mock(com.mystipixel.royalbazaar.config.CategoryConfig.class);
        var group = new com.mystipixel.royalbazaar.config.CategoryConfig.Group("g", "G", "stone", 0, -1);
        var onlySell = service.groupPlaceholders(cat, group, List.of(sellOnly));
        assertEquals("-", onlySell.get("rbazaar_group_min_buy"));
        assertEquals("-", onlySell.get("rbazaar_group_max_buy"));
        var mixed = service.groupPlaceholders(cat, group, List.of(sellOnly, buyOnly));
        assertEquals(Double.toString(PricingEngine.buyPrice(buyOnly)), mixed.get("rbazaar_group_min_buy"));
    }
}
