package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.gui.menu.ItemSpec;
import com.mystipixel.royalbazaar.gui.menu.MenuEffect;
import com.mystipixel.royalbazaar.gui.menu.MenuManager;
import com.mystipixel.royalbazaar.gui.menu.MenuSlot;
import com.mystipixel.royalbazaar.gui.menu.MenuTemplate;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.TradeMode;
import com.mystipixel.royalbazaar.market.TradeResult;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.message.MessageManager;
import com.mystipixel.royalbazaar.service.BazaarService;
import com.mystipixel.royalbazaar.util.ItemNames;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradeModeGuiTest {

    private static final String ID = "minecraft:stone";

    private final MarketManager market = mock(MarketManager.class);
    private final BazaarService service = mock(BazaarService.class);
    private final MenuManager menus = mock(MenuManager.class);
    private final EcoHook eco = mock(EcoHook.class);
    private final Player player = mock(Player.class);
    private final MessageManager messages = mock(MessageManager.class);
    private final TextInput input = mock(TextInput.class);
    private final AmountPrompt prompt = mock(AmountPrompt.class);
    private final GuiManager gui = mock(GuiManager.class);

    private MarketItem item(TradeMode mode) {
        MarketItem item = new MarketItem(ID, "test", null, "Stone", 10, 0.05, 1000, 0.02, 1, 100, mode);
        when(market.get(ID)).thenReturn(item);
        return item;
    }

    private EffectDispatcher dispatcher() {
        return new EffectDispatcher(gui, service, prompt, messages, market, input);
    }

    private void refuse(TradeSide side) {
        when(service.tradeModeRefusal(ID, side)).thenReturn(
                TradeResult.fail(TradeResult.Status.DISABLED, side, ID, "turned off"));
    }

    private void assertRefusedWithoutSideEffects() {
        verify(service, never()).buy(any(), anyString(), anyLong());
        verify(service, never()).sell(any(), anyString(), anyLong());
        verify(service, never()).fillAmount(any(), anyString());
        verify(service, never()).sellAll(any(), any());
        verifyNoInteractions(input, prompt);
        verify(gui, never()).openBuy(any(), anyString());
        verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        verify(messages).send(player, "trade.failed", "&c{reason}", Map.of("reason", "turned off"));
        verify(gui).refresh(player);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rbazaar_buy", "rbazaar_open_buy", "rbazaar_buy_prompt", "rbazaar_buy_amount_prompt",
            "rbazaar_sell", "rbazaar_sell_prompt"})
    void everyTradeClickIsRefusedWhenItsDirectionIsOff(String action) {
        TradeSide side = new MenuEffect(action, Map.of()).tradeSide();
        refuse(side);
        List<MenuEffect> click = List.of(
                new MenuEffect(action, Map.of("item", ID, "amount", "fill")),
                new MenuEffect("play_sound", Map.of("sound", "ui_button_click")));
        dispatcher().run(player, click);
        assertRefusedWithoutSideEffects();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rbazaar_open_buy", "rbazaar_buy_amount_prompt"})
    void buyClicksWithoutAnItemArgAreCheckedAgainstTheOpenProduct(String action) {
        refuse(TradeSide.BUY);
        when(gui.viewOf(player)).thenReturn(new OpenView("bazaar_product", "test", ID));
        dispatcher().run(player, List.of(new MenuEffect(action, Map.of())));
        assertRefusedWithoutSideEffects();
    }

    @Test
    void allowedDirectionStillTrades() {
        when(service.sell(player, ID, Long.MAX_VALUE)).thenReturn(TradeResult.ok(TradeSide.SELL, ID, 3, 9));
        dispatcher().run(player, List.of(new MenuEffect("rbazaar_sell", Map.of("item", ID, "amount", "all"))));
        verify(service).tradeModeRefusal(ID, TradeSide.SELL);
        verify(service).sell(player, ID, Long.MAX_VALUE);
    }

    @Test
    void tradeClickWithoutAnItemStillDoesNothing() {
        dispatcher().run(player, List.of(new MenuEffect("rbazaar_buy", Map.of("amount", 1))));
        verify(service, never()).buy(any(), any(), anyLong());
        verifyNoInteractions(messages);
        verify(gui, never()).refresh(player);
    }

    @Test
    void customAmountTypedAfterTheModeChangedIsRefusedByTheService() {
        AtomicReference<Consumer<String>> callback = new AtomicReference<>();
        doAnswer(inv -> { callback.set(inv.getArgument(2)); return null; })
                .when(input).request(eq(player), anyList(), any());
        when(messages.lines(anyString(), anyList())).thenAnswer(inv -> inv.getArgument(1));
        new AmountPrompt(service, gui, messages, input).begin(player, ID, true);
        when(service.buy(player, ID, 64)).thenReturn(
                TradeResult.fail(TradeResult.Status.DISABLED, TradeSide.BUY, ID, "turned off"));
        callback.get().accept("64");
        verify(messages).send(player, "trade.failed", "&c{reason}", Map.of("reason", "turned off"));
        verify(gui).openProduct(player, ID);
    }

    @Test
    void mixedButtonIsOnlyDisabledWhenEveryTradeOnItIsOff() {
        item(TradeMode.SELL_ONLY);
        GuiManager real = new GuiManager(menus, market, service, eco, mock(ItemNames.class));
        List<MenuEffect> buy = List.of(new MenuEffect("rbazaar_buy", Map.of("item", ID)));
        List<MenuEffect> sell = List.of(new MenuEffect("rbazaar_sell", Map.of("item", ID)));
        List<MenuEffect> back = List.of(new MenuEffect("rbazaar_back", Map.of()));
        assertTrue(real.tradeTurnedOff(buy, List.of(), null));
        assertTrue(real.tradeTurnedOff(List.of(new MenuEffect("rbazaar_open_buy", Map.of())), null, ID));
        assertFalse(real.tradeTurnedOff(buy, sell, null));
        assertFalse(real.tradeTurnedOff(sell, List.of(), null));
        assertFalse(real.tradeTurnedOff(back, null, ID));
        assertFalse(real.tradeTurnedOff(List.of(new MenuEffect("rbazaar_buy", Map.of("item", "minecraft:gone"))),
                null, null));
    }

    @Test
    void productPageDrawsTheTurnedOffButtonWithItsDisabledItemAndLore() {
        MarketItem item = item(TradeMode.SELL_ONLY);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Map<String, String> ph = Map.of("rbazaar_item", ID);
        when(service.placeholders(item, player)).thenReturn(ph);

        ItemSpec buySpec = mock(ItemSpec.class);
        ItemSpec buyDisabled = mock(ItemSpec.class);
        ItemSpec sellSpec = mock(ItemSpec.class);
        ItemSpec sellDisabled = mock(ItemSpec.class);
        ItemStack grey = mock(ItemStack.class);
        ItemStack sellIcon = mock(ItemStack.class);
        List<String> disabledLore = List.of("&cCan't buy this here.");
        when(buyDisabled.build(eco, ph, disabledLore)).thenReturn(grey);
        when(sellSpec.build(eco, ph, List.of("&eClick to sell"))).thenReturn(sellIcon);

        List<MenuEffect> buyClick = List.of(new MenuEffect("rbazaar_open_buy", Map.of("item", "%rbazaar_item%")));
        List<MenuEffect> sellClick = List.of(new MenuEffect("rbazaar_sell", Map.of("item", "%rbazaar_item%")));
        MenuTemplate template = mock(MenuTemplate.class);
        when(template.size()).thenReturn(36);
        when(template.title()).thenReturn("Product");
        when(template.slots()).thenReturn(List.of(
                new MenuSlot(11, buySpec, List.of("&eClick to buy"), buyClick, List.of(), buyDisabled, disabledLore),
                new MenuSlot(15, sellSpec, List.of("&eClick to sell"), sellClick, List.of(),
                        sellDisabled, List.of("&cCan't sell this here."))));
        when(menus.get("bazaar_product")).thenReturn(template);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(36);

        GuiManager real = new GuiManager(menus, market, service, eco, mock(ItemNames.class));
        try (MockedStatic<BazaarMenuHolder> holder = mockStatic(BazaarMenuHolder.class)) {
            holder.when(() -> BazaarMenuHolder.create(anyInt(), any())).thenReturn(inventory);
            real.openBuy(player, ID);      // sell-only: lands on the product page instead
        }
        verify(menus, never()).get("bazaar_buy");
        verify(inventory).setItem(11, grey);
        verify(inventory).setItem(15, sellIcon);
        verifyNoInteractions(buySpec, sellDisabled);
        OpenView view = real.viewOf(player);
        assertEquals("bazaar_product", view.menuId());
        assertEquals(ID, view.left(11).getFirst().argString("item", null), "still bound, so a click explains itself");
    }

    @Test
    void slotWithoutDisabledLoreKeepsItsNormalLook() {
        MarketItem item = item(TradeMode.BUY_ONLY);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Map<String, String> ph = Map.of("rbazaar_item", ID);
        when(service.placeholders(item, player)).thenReturn(ph);
        ItemSpec spec = mock(ItemSpec.class);
        ItemStack icon = mock(ItemStack.class);
        when(spec.build(eco, ph, List.of("&eSell"))).thenReturn(icon);
        MenuTemplate template = mock(MenuTemplate.class);
        when(template.size()).thenReturn(36);
        when(template.title()).thenReturn("Product");
        when(template.slots()).thenReturn(List.of(new MenuSlot(15, spec, List.of("&eSell"),
                List.of(new MenuEffect("rbazaar_sell", Map.of("item", ID))), List.of(), null, null)));
        when(menus.get("bazaar_product")).thenReturn(template);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(36);
        GuiManager real = new GuiManager(menus, market, service, eco, mock(ItemNames.class));
        try (MockedStatic<BazaarMenuHolder> holder = mockStatic(BazaarMenuHolder.class)) {
            holder.when(() -> BazaarMenuHolder.create(anyInt(), any())).thenReturn(inventory);
            real.openProduct(player, ID);
        }
        verify(inventory).setItem(15, icon);
    }
}
