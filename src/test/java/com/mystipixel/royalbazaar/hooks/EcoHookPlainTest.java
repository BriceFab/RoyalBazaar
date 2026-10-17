package com.mystipixel.royalbazaar.hooks;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// a vanilla listing must never buy a plugin item, yet an annotation-only tag must not block a sale
class EcoHookPlainTest {

    private static final NamespacedKey OWNER = new NamespacedKey("antidupepro", "adp_owner");
    private static final NamespacedKey CUSTOM = new NamespacedKey("someplugin", "item_id");

    @Test
    void anyDataMakesTheStackSpecialByDefault() {
        assertFalse(EcoHook.isPlain(tagged(OWNER), Set.of()));
    }

    @Test
    void ignoredNamespacesKeepTheStackPlain() {
        assertTrue(EcoHook.isPlain(tagged(OWNER), Set.of("antidupepro")));
    }

    @Test
    void otherDataStillMakesTheStackSpecial() {
        assertFalse(EcoHook.isPlain(tagged(OWNER, CUSTOM), Set.of("antidupepro")));
    }

    private static ItemStack tagged(NamespacedKey... keys) {
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.getKeys()).thenReturn(Set.of(keys));
        when(data.isEmpty()).thenReturn(keys.length == 0);
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        ItemStack stack = mock(ItemStack.class);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        return stack;
    }
}
