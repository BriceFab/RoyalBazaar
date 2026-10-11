package com.mystipixel.royalbazaar.hooks;

import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.market.TradeMode;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.service.BazaarService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BazaarPlaceholderExpansionTest {

    @ParameterizedTest
    @EnumSource(TradeMode.class)
    void pricesForATurnedOffSideShowTheUnavailableText(TradeMode mode) {
        MarketManager market = mock(MarketManager.class);
        VaultHook vault = mock(VaultHook.class);
        BazaarService service = mock(BazaarService.class);
        when(service.unavailablePrice()).thenReturn("-");
        when(vault.format(anyDouble())).thenAnswer(i -> "$" + i.getArgument(0));
        MarketItem item = new MarketItem("minecraft:stone_bricks", "test", null, "",
                10, 0.05, 1000, 0.02, 1, 100, mode);
        when(market.get(item.id())).thenReturn(item);
        BazaarPlaceholderExpansion expansion = new BazaarPlaceholderExpansion(market, vault, "test", service);
        assertEquals(mode.allows(TradeSide.BUY) ? "$" + PricingEngine.buyPrice(item) : "-",
                expansion.onRequest(null, "buy_" + item.id()));
        assertEquals(mode.allows(TradeSide.SELL) ? "$" + PricingEngine.sellPrice(item) : "-",
                expansion.onRequest(null, "sell_" + item.id()));
        assertEquals("$10.0", expansion.onRequest(null, "mid_" + item.id()));
    }
}
