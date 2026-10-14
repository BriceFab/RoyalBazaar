package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.hooks.*;
import com.mystipixel.royalbazaar.market.*;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalcommon.storage.DbExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// provider failure tests drive the real service, inventory planning, pricing, a real SQLite database
// and the audit path; the test thread plays the main thread by draining the main queue
class BazaarServiceTest {
    private static final Logger LOG = Logger.getLogger("BazaarServiceTest");

    private final MarketManager market = mock(MarketManager.class);
    private final VaultHook vault = mock(VaultHook.class);
    private final EcoHook eco = mock(EcoHook.class);
    private final EconGuardHook guard = mock(EconGuardHook.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final PluginConfig config = mock(PluginConfig.class);
    private final MessageManager messages = mock(MessageManager.class);
    private final AtomicReference<ItemStack[]> storage = new AtomicReference<>();
    private final ItemMeta customMetadata = mock(ItemMeta.class);
    private final MarketItem item = new MarketItem("ecoitem:test", "test", null, "Custom item",
            100, 0.05, 1000, 0.01, 1, 1000, TradeMode.BOTH);
    private final MainQueue main = new MainQueue(task -> { });

    @TempDir
    Path dir;
    private BazaarDatabase database;
    private DbExecutor db;
    private BazaarService service;

    @BeforeEach
    void setup() throws SQLException {
        YamlConfiguration storageConfig = new YamlConfiguration();
        storageConfig.set("type", "SQLITE");
        database = new BazaarDatabase(dir.toFile(), storageConfig, LOG);
        database.init();
        database.seed(List.of(item.curve(), stoneBricks(TradeMode.BUY_ONLY).curve()));
        db = new DbExecutor("BazaarServiceTest", main, LOG);
        PriceRefresher prices = new PriceRefresher(market, database, db);
        service = new BazaarService(market, database, db, prices, vault, eco, guard, config, messages, LOG);
        when(messages.get(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(market.get(item.id())).thenReturn(item);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        // Storage arrays are copied, but their stacks are shared, just as a shallow snapshot can be.
        when(inventory.getStorageContents()).thenAnswer(i -> storage.get().clone());
        doAnswer(i -> { storage.set(i.getArgument(0)); return null; })
                .when(inventory).setStorageContents(any(ItemStack[].class));
        // returned items go into the first empty slot
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(i -> {
            ItemStack[] slots = storage.get();
            for (Object given : i.getArguments()) {
                for (int s = 0; s < slots.length; s++) {
                    if (slots[s] == null) {
                        slots[s] = (ItemStack) given;
                        break;
                    }
                }
            }
            return new HashMap<Integer, ItemStack>();
        });
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

    @AfterEach
    void close() {
        main.finish(db, Duration.ofSeconds(5));
        database.close();
    }

    // runs main-thread callbacks until the future is done, as server ticks would
    private <T> T await(CompletableFuture<T> future) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!future.isDone()) {
            if (!main.drain()) {
                Thread.onSpinWait();
            }
            assertTrue(System.nanoTime() < deadline, "trade did not finish");
        }
        main.drain();
        return future.join();
    }

    private static MarketItem stoneBricks(TradeMode mode) {
        return new MarketItem("minecraft:stone_bricks", "test", null, "", 10, 0.05, 1000, 0.01, 1, 100, mode);
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

    private int heldCustom() {
        return eco.countHeld(player, item.id());
    }

    private double storedMid(String id) throws SQLException {
        return database.rows(List.of(id)).getFirst().mid();
    }

    private int loggedTrades() throws SQLException {
        try (Connection c = database.dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM rb_transactions")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void assertNoSaleRecorded() throws SQLException {
        assertEquals(100, item.mid(), 1e-9);
        assertEquals(100, storedMid(item.id()), 1e-9);
        assertEquals(0, loggedTrades());
        assertEquals(0, item.volume().sold24h());
        verify(guard, never()).observe(any(), any(), anyString(), anyLong(), anyDouble());
    }

    @Test
    void rejectedPaymentReturnsTheItemsAndTakesTheSaleBackOutOfTheMarket() throws SQLException {
        when(vault.deposit(eq(player), anyDouble())).thenReturn(false);
        TradeResult result = await(service.sell(player, item.id(), 8));
        assertEquals(TradeResult.Status.ERROR, result.status());
        assertEquals("Payment failed. Your items have been returned.", result.message());
        assertEquals(0, result.filled());
        assertEquals(14, heldCustom(), "the 8 taken come back");
        assertEquals(3, storage.get()[2].getAmount());
        for (ItemStack slot : storage.get()) {
            if (slot != null && slot != storage.get()[2]) {
                assertSame(customMetadata, slot.getItemMeta(), "returned stacks keep their metadata");
            }
        }
        assertNoSaleRecorded();
    }

    @Test
    void acceptedPaymentRemovesExactlyTheQuotedQuantityAndPreservesMetadata() throws SQLException {
        ItemStack[] before = storage.get().clone();
        double quote = PricingEngine.sellProceeds(item, 8);
        when(vault.deposit(player, quote)).thenAnswer(i -> {
            assertNull(storage.get()[0], "sold items are reserved during the provider call");
            assertEquals(6, storage.get()[1].getAmount());
            return true;
        });
        TradeResult result = await(service.sell(player, item.id(), 8));
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
        assertEquals(item.mid(), storedMid(item.id()), 1e-9, "the cache shows the price the database holds");
        assertEquals(1, loggedTrades());
        verify(vault).deposit(player, quote);
        verify(guard).observe(player, TradeSide.SELL, item.id(), 8, quote);
    }

    @Test
    void failedSellAllIsNotReportedAsSoldAndCanBeRetriedOnceProviderRecovers() throws SQLException {
        when(vault.deposit(eq(player), anyDouble())).thenReturn(false, true);
        var first = await(service.sellAll(player, List.of(item)));
        assertTrue(first.soldNothing());
        assertEquals(0, first.units());
        assertEquals(0, first.proceeds());
        assertNoSaleRecorded();
        var second = await(service.sellAll(player, List.of(item)));
        assertEquals(1, second.distinctItems());
        assertEquals(14, second.units());
        assertEquals(0, heldCustom());
        assertEquals(3, storage.get()[2].getAmount());
        assertEquals(14, item.volume().sold24h());
    }

    @Test
    void nonPositiveSaleAmountsNeverReachEconomyOrInventoryMutation() throws SQLException {
        assertEquals(TradeResult.Status.ERROR, await(service.sell(player, item.id(), 0)).status());
        assertEquals(TradeResult.Status.ERROR, await(service.sell(player, item.id(), -8)).status());
        verifyNoInteractions(vault);
        verify(inventory, never()).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void staleInventoryCountDoesNotPayForItemsThatCannotBeReserved() throws SQLException {
        doReturn(100).when(eco).countHeld(player, item.id());
        assertEquals(TradeResult.Status.INSUFFICIENT_ITEMS,
                await(service.sell(player, item.id(), 100)).status());
        verifyNoInteractions(vault);
        verify(inventory, never()).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void exceptionIsNotTreatedAsAnExplicitRejectionOrAutomaticallyRetried() throws SQLException {
        // An exception may occur after a provider moved money. Do not return items automatically
        // and turn an unknown outcome into a duplicate; this path still needs manual reconciliation.
        when(vault.deposit(eq(player), anyDouble())).thenThrow(new IllegalStateException("unknown outcome"));
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> await(service.sell(player, item.id(), 8)));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        verify(vault).deposit(eq(player), anyDouble());
        verify(inventory, never()).addItem(any(ItemStack[].class));
        assertEquals(6, heldCustom());
        assertEquals(1, loggedTrades(), "the sale stays on record for reconciliation");
        verify(guard, never()).observe(any(), any(), anyString(), anyLong(), anyDouble());
        // the player's busy slot is free again
        doReturn(true).when(vault).deposit(eq(player), anyDouble());
        assertTrue(await(service.sell(player, item.id(), 1)).ok());
    }

    @Test
    void guardRejectionNeverChargesOrConsumesItems() throws SQLException {
        when(guard.allow(eq(player), eq(TradeSide.SELL), eq(item.id()), anyLong(), anyDouble()))
                .thenReturn(false);
        assertEquals(TradeResult.Status.REJECTED_BY_GUARD, await(service.sell(player, item.id(), 8)).status());
        verifyNoInteractions(vault);
        verify(inventory, never()).setStorageContents(any());
        assertNoSaleRecorded();
    }

    @Test
    void buyOverTheOrderCapIsRefusedBeforeAnyMoneyMoves() {
        when(config.maxOrder()).thenReturn(64L);
        TradeResult result = await(service.buy(player, item.id(), 65));
        assertEquals(TradeResult.Status.ERROR, result.status());
        verifyNoInteractions(vault);
        assertEquals(100, item.mid());
    }

    @Test
    void buyOfAnItemThatNoLongerResolvesIsRefusedNotThrown() {
        when(guard.allow(any(), any(), anyString(), anyLong(), anyDouble())).thenReturn(true);
        when(eco.resolve(item.id(), 1)).thenReturn(null);
        TradeResult result = await(service.buy(player, item.id(), 5));
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

    // a buyable item, an empty inventory and enough money
    private MarketItem readyToBuy(TradeMode mode) {
        MarketItem buyable = withMode(mode);
        storage.set(new ItemStack[4]);
        ItemStack prototype = mock(ItemStack.class);
        when(prototype.getMaxStackSize()).thenReturn(64);
        when(eco.resolve(eq(item.id()), anyInt())).thenReturn(prototype);
        when(guard.allow(eq(player), eq(TradeSide.BUY), eq(item.id()), anyLong(), anyDouble())).thenReturn(true);
        when(vault.has(eq(player), anyDouble())).thenReturn(true);
        when(vault.withdraw(eq(player), anyDouble())).thenReturn(true);
        when(vault.deposit(eq(player), anyDouble())).thenReturn(true);
        when(vault.balance(player)).thenReturn(1_000_000.0);
        return buyable;
    }

    @ParameterizedTest
    @EnumSource(value = TradeMode.class, names = {"BUY_ONLY", "SELL_ONLY"})
    void turnedOffDirectionIsRefusedBeforeAnythingMoves(TradeMode mode) throws SQLException {
        MarketItem fresh = withMode(mode);
        ItemStack[] before = storage.get().clone();
        for (long amount : new long[]{1, 64, Long.MAX_VALUE, 0, -1}) {
            TradeResult result = await(mode == TradeMode.BUY_ONLY
                    ? service.sell(player, item.id(), amount) : service.buy(player, item.id(), amount));
            assertEquals(TradeResult.Status.DISABLED, result.status());
            assertEquals(0, result.filled());
            assertEquals(mode == TradeMode.BUY_ONLY
                    ? "This item can't be sold here, only bought." : "This item can't be bought here, only sold.",
                    result.message());
        }
        assertEquals(100, fresh.mid());
        assertEquals(0, fresh.volume().bought24h());
        assertEquals(0, fresh.volume().sold24h());
        assertEquals(0, loggedTrades());
        assertArrayEquals(before, storage.get());
        verifyNoInteractions(vault, eco, guard, inventory);
    }

    @Test
    void refusalReasonComesFromMessages() {
        withMode(TradeMode.SELL_ONLY);
        when(messages.get(eq("trade.reasons.buy-disabled"), anyString())).thenReturn("Achat impossible");
        assertEquals("Achat impossible", await(service.buy(player, item.id(), 1)).message());
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
        assertTrue(await(service.sell(player, item.id(), 8)).ok());
    }

    @Test
    void fillOnASellOnlyItemIsZeroWithoutReadingBalanceOrInventory() {
        withMode(TradeMode.SELL_ONLY);
        assertEquals(0, service.fillAmount(player, item.id()));
        verifyNoInteractions(vault, eco, inventory);
    }

    @Test
    void sellAllSkipsBuyOnlyItemsEvenFromACollectionOlderThanTheReload() throws SQLException {
        withMode(TradeMode.BUY_ONLY);
        var result = await(service.sellAll(player, List.of(item)));   // item itself is still BOTH
        assertTrue(result.soldNothing());
        assertEquals(0, result.blocked(), "a buy-only item isn't a blocked sale, it isn't sellable at all");
        assertEquals(0, loggedTrades());
        verifyNoInteractions(vault, eco, guard, inventory);
    }

    @Test
    void sellAllSellsTheSellableItemsAndLeavesBuyOnlyOnes() {
        MarketItem sellable = withMode(TradeMode.SELL_ONLY);
        MarketItem buyOnly = stoneBricks(TradeMode.BUY_ONLY);
        when(market.get(buyOnly.id())).thenReturn(buyOnly);
        when(vault.deposit(eq(player), anyDouble())).thenReturn(true);
        var result = await(service.sellAll(player, List.of(buyOnly, item)));
        assertEquals(1, result.distinctItems());
        assertEquals(14, result.units());
        assertEquals(14, sellable.volume().sold24h());
        verify(eco, never()).countHeld(player, buyOnly.id());
        assertEquals(10, buyOnly.mid());
    }

    @Test
    void buyOnlyItemBuysNormally() throws SQLException {
        MarketItem buyable = readyToBuy(TradeMode.BUY_ONLY);
        assertTrue(service.fillAmount(player, item.id()) > 0);
        double cost = PricingEngine.buyCost(buyable, 64);
        TradeResult result = await(service.buy(player, item.id(), 64));
        assertTrue(result.ok());
        assertEquals(cost, result.total());
        assertEquals(64, buyable.volume().bought24h());
        assertTrue(buyable.mid() > 100);
        assertEquals(buyable.mid(), storedMid(item.id()), 1e-9);
        verify(vault).withdraw(player, cost);
        verify(vault, never()).deposit(any(), anyDouble());
        verify(inventory).addItem(any(ItemStack[].class));
    }

    @Test
    void sellOnlyItemStillHonoursFreeze() {
        MarketItem sellable = withMode(TradeMode.SELL_ONLY);
        Rows.setFrozen(sellable, true);
        assertEquals("This item is temporarily frozen.", await(service.sell(player, item.id(), 8)).message());
    }

    @Test
    void freezeFromAnotherServerRefundsABuyAndShowsTheFreeze() throws SQLException {
        MarketItem buyable = readyToBuy(TradeMode.BOTH);
        database.setFrozen(item.id(), true);       // this server's cache hasn't seen it yet
        double cost = PricingEngine.buyCost(buyable, 10);
        TradeResult result = await(service.buy(player, item.id(), 10));
        assertEquals(TradeResult.Status.DISABLED, result.status());
        verify(vault).withdraw(player, cost);
        verify(vault).deposit(player, cost);
        verify(inventory, never()).addItem(any(ItemStack[].class));
        assertTrue(buyable.frozen(), "the refused trade brings the freeze into the cache");
        assertEquals(0, loggedTrades());
    }

    @Test
    void priceMovedByAnotherServerRefusesTheBuyAndRefundsIt() throws SQLException {
        MarketItem buyable = readyToBuy(TradeMode.BOTH);
        database.setMid(item.id(), 150);           // far past half the spread
        double cost = PricingEngine.buyCost(buyable, 10);
        TradeResult result = await(service.buy(player, item.id(), 10));
        assertEquals(TradeResult.Status.PRICE_MOVED, result.status());
        verify(vault).deposit(player, cost);
        assertEquals(150, buyable.mid(), 1e-9, "the player sees the new price before trying again");
        assertEquals(0, loggedTrades());
    }

    @Test
    void smallMoveByAnotherServerStillTradesAtTheQuotedPrice() throws SQLException {
        MarketItem buyable = readyToBuy(TradeMode.BOTH);
        database.setMid(item.id(), 101);           // within half the 5% spread
        double cost = PricingEngine.buyCost(buyable, 10);
        TradeResult result = await(service.buy(player, item.id(), 10));
        assertTrue(result.ok());
        assertEquals(cost, result.total());
        assertTrue(buyable.mid() > 101, "the impact lands on the database price, not the stale one");
    }

    @Test
    void sellAllAfterAnotherServerMovedThePriceHandsTheItemsBack() throws SQLException {
        when(vault.deposit(eq(player), anyDouble())).thenReturn(true);
        database.setMid(item.id(), 60);            // dumped on another server
        var result = await(service.sellAll(player, List.of(item)));
        assertTrue(result.soldNothing());
        assertEquals(1, result.retry());
        assertEquals(14, heldCustom());
        assertEquals(60, item.mid(), 1e-9, "the next sell-all quotes the new price");
        verify(vault, never()).deposit(any(), anyDouble());
        assertEquals(1, await(service.sellAll(player, List.of(item))).distinctItems());
    }

    @Test
    void buyerWhoLeftBeforeDeliveryIsRefundedAndTheTradeTakenBack() throws SQLException {
        readyToBuy(TradeMode.BOTH);
        when(player.isOnline()).thenReturn(false);
        TradeResult result = await(service.buy(player, item.id(), 10));
        assertFalse(result.ok());
        verify(vault).deposit(eq(player), anyDouble());
        verify(inventory, never()).addItem(any(ItemStack[].class));
        assertEquals(100, storedMid(item.id()), 1e-9);
        assertEquals(0, loggedTrades());
    }

    @Test
    void secondTradeWhileTheFirstIsInFlightIsRefused() {
        readyToBuy(TradeMode.BOTH);
        CompletableFuture<TradeResult> first = service.buy(player, item.id(), 10);
        TradeResult second = await(service.buy(player, item.id(), 10));
        assertEquals(TradeResult.Status.BUSY, second.status());
        assertTrue(await(first).ok());
        storage.set(new ItemStack[4]);              // room again after the delivery
        assertTrue(await(service.buy(player, item.id(), 10)).ok(), "the slot frees once the first finishes");
    }

    @Test
    void closedServiceRefusesNewTrades() {
        readyToBuy(TradeMode.BOTH);
        service.close();
        assertEquals(TradeResult.Status.ERROR, await(service.buy(player, item.id(), 1)).status());
        assertNotNull(await(service.sellAll(player, List.of(item))).refusal());
        verifyNoInteractions(vault);
    }

    @Test
    void adminFreezeAndPriceReachTheCache() throws SQLException {
        StateRow frozen = await(service.setFrozen(item.id(), true));
        assertTrue(frozen.frozen());
        assertTrue(item.frozen());
        await(service.setMid(item.id(), 250));
        assertEquals(250, item.mid(), 1e-9);
        assertEquals(250, storedMid(item.id()), 1e-9);
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
