package com.mystipixel.royalbazaar.service;

import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.hooks.EconGuardHook;
import com.mystipixel.royalbazaar.hooks.VaultHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.TradeMode;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.message.MessageManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeModeMessagesTest {

    @Test
    void everyTradeModeTextShipsInMessagesYmlWithAMatchingFallback() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (InputStream in = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(in);
            shipped.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        Map<String, String> fallbacks = new HashMap<>();
        MessageManager messages = mock(MessageManager.class);
        when(messages.get(anyString(), anyString())).thenAnswer(i -> {
            fallbacks.put(i.getArgument(0), i.getArgument(1));
            return i.getArgument(1);
        });
        MarketManager market = mock(MarketManager.class);
        VaultHook vault = mock(VaultHook.class);
        when(vault.formatPrice(anyDouble())).thenReturn("1.00");
        BazaarService service = new BazaarService(mock(JavaPlugin.class), market, mock(BazaarDatabase.class),
                vault, mock(EcoHook.class), mock(EconGuardHook.class), mock(PluginConfig.class), messages);
        for (TradeMode mode : TradeMode.values()) {
            MarketItem item = new MarketItem("minecraft:stone", "test", null, "Stone",
                    10, 0.05, 1000, 0.02, 1, 100, mode);
            when(market.get(item.id())).thenReturn(item);
            service.placeholders(item, null);
            service.tradeModeRefusal(item.id(), TradeSide.BUY);
            service.tradeModeRefusal(item.id(), TradeSide.SELL);
        }

        for (String key : new String[]{"price-unavailable", "trade.reasons.buy-disabled",
                "trade.reasons.sell-disabled", "trade-mode.both", "trade-mode.buy-only", "trade-mode.sell-only"}) {
            assertTrue(fallbacks.containsKey(key), key + " is read by the code");
            assertEquals(shipped.getString(key), fallbacks.get(key), key + ": code fallback matches messages.yml");
        }
    }
}
