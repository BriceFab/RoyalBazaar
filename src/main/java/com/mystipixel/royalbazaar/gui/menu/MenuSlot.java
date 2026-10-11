package com.mystipixel.royalbazaar.gui.menu;

import java.util.List;

/**
 * A fixed slot from the {@code slots:} list; {@code index} is 0-based on the page. {@code disabledItem}
 * and {@code disabledLore} replace the item and lore when the slot's trade direction is turned off for
 * its item; null when the menu file doesn't set them.
 */
public record MenuSlot(int index,
                       ItemSpec item,
                       List<String> lore,
                       List<MenuEffect> leftClick,
                       List<MenuEffect> rightClick,
                       ItemSpec disabledItem,
                       List<String> disabledLore) {
}
