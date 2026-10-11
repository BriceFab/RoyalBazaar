package com.mystipixel.royalbazaar.command;

import com.mystipixel.royalbazaar.RoyalBazaarPlugin;
import com.mystipixel.royalbazaar.gui.GuiManager;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.TradeMode;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BazaarCommandTest {

    private final RoyalBazaarPlugin plugin = mock(RoyalBazaarPlugin.class);
    private final MessageManager messages = mock(MessageManager.class);
    private final BazaarService service = mock(BazaarService.class);
    private final MarketManager market = mock(MarketManager.class);

    private BazaarCommand command() {
        when(plugin.messages()).thenReturn(messages);
        return new BazaarCommand(plugin, mock(GuiManager.class), market, service);
    }

    @ParameterizedTest
    @EnumSource(TradeMode.class)
    void priceShowsTheUnavailableTextForATurnedOffSide(TradeMode mode) {
        when(service.unavailablePrice()).thenReturn("-");
        MarketItem item = new MarketItem("minecraft:stone_bricks", "test", null, "",
                10, 0.05, 1000, 0.02, 1, 100, mode);
        when(market.lookup("stone_bricks")).thenReturn(item);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("royalbazaar.use")).thenReturn(true);
        command().onCommand(sender, null, "bazaar", new String[]{"price", "stone_bricks"});
        verify(messages).send(eq(sender), eq("price-line"), anyString(), argThat((Map<String, String> values) ->
                values.get("buy").equals("-") == !mode.allows(TradeSide.BUY)
                        && values.get("sell").equals("-") == !mode.allows(TradeSide.SELL)
                        && !values.get("mid").equals("-")));
    }

    @Test
    void sellAllCommandGoesThroughTheServiceThatSkipsBuyOnlyItems() {
        Player player = mock(Player.class);
        when(player.hasPermission("royalbazaar.use")).thenReturn(true);
        List<MarketItem> all = List.of(new MarketItem("minecraft:stone_bricks", "test", null, "",
                10, 0.05, 1000, 0.02, 1, 100, TradeMode.BUY_ONLY));
        when(market.all()).thenReturn(all);
        when(service.sellAll(eq(player), any())).thenReturn(new BazaarService.SellAllResult(0, 0, 0, 0));
        command().onCommand(player, null, "bazaar", new String[]{"sellall"});
        verify(service).sellAll(eq(player), argThat((Collection<MarketItem> scope) -> scope == all));
        verify(service, never()).sell(any(), anyString(), anyLong());
    }
}
