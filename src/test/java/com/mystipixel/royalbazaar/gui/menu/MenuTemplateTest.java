package com.mystipixel.royalbazaar.gui.menu;

import com.mystipixel.royalbazaar.hooks.EcoHook;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MenuTemplateTest {

    private static MenuTemplate shippedProductMenu(Path tmp) throws Exception {
        Path file = tmp.resolve("bazaar_product.yml");
        try (InputStream in = MenuTemplateTest.class.getResourceAsStream("/gui/bazaar_product.yml")) {
            assertNotNull(in);
            Files.copy(in, file);
        }
        // the mask filler is built while loading
        EcoHook eco = mock(EcoHook.class);
        when(eco.resolve(anyString(), anyInt())).thenReturn(mock(ItemStack.class));
        EcoHook previous = MenuTemplate.EcoHookHolder.get();
        MenuTemplate.EcoHookHolder.set(eco);
        try {
            return MenuTemplate.load(file.toFile(), "Product", 4);
        } finally {
            MenuTemplate.EcoHookHolder.set(previous);
        }
    }

    private static MenuSlot slotWith(MenuTemplate tmpl, String effectId) {
        return tmpl.slots().stream()
                .filter(s -> s.leftClick().stream().anyMatch(e -> e.id().equals(effectId)))
                .findFirst().orElseThrow();
    }

    @Test
    void shippedProductMenuHasDisabledLooksForBuyAndSell(@TempDir Path tmp) throws Exception {
        MenuTemplate tmpl = shippedProductMenu(tmp);
        MenuSlot buy = slotWith(tmpl, "rbazaar_open_buy");
        MenuSlot sell = slotWith(tmpl, "rbazaar_sell");
        assertEquals("gray_dye", buy.disabledItem().lookupId());
        assertEquals("gray_dye", sell.disabledItem().lookupId());
        assertFalse(buy.disabledLore().isEmpty());
        assertFalse(sell.disabledLore().isEmpty());
    }

    @Test
    void slotsWithoutDisabledKeysHaveNone(@TempDir Path tmp) throws Exception {
        MenuSlot back = slotWith(shippedProductMenu(tmp), "rbazaar_back");
        assertNull(back.disabledItem());
        assertNull(back.disabledLore());
        assertEquals(List.of(), back.lore());
    }

    @Test
    void shippedProductInfoShowsTheTradeMode(@TempDir Path tmp) throws Exception {
        MenuTemplate tmpl = shippedProductMenu(tmp);
        assertTrue(tmpl.slots().stream().anyMatch(s -> s.lore().stream()
                .anyMatch(line -> line.contains("%rbazaar_trade_mode%"))));
    }
}
