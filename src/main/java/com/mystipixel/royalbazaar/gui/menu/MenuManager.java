package com.mystipixel.royalbazaar.gui.menu;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads (and, on first run, writes out) every {@code gui/*.yml} template. Reloadable. */
public final class MenuManager {

    private final JavaPlugin plugin;
    private final Map<String, MenuTemplate> byId = new LinkedHashMap<>();

    public MenuManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        byId.clear();
        // no bazaar_main: /bazaar opens a category directly and the category rail is the navigation
        byId.put("bazaar_category", load("bazaar_category.yml", "&8Bazaar &7» &f%rbazaar_category%", 6));
        byId.put("bazaar_group", load("bazaar_group.yml", "&f%rbazaar_category% &7» &f%rbazaar_group_name%", 6));
        byId.put("bazaar_product", load("bazaar_product.yml", "%rbazaar_category_id% &7» &f%rbazaar_item_display%", 6));
        byId.put("bazaar_buy", load("bazaar_buy.yml", "&f%rbazaar_item_display% &7» &fInstant Buy", 3));
        byId.put("bazaar_trends", load("bazaar_trends.yml", "&8Bazaar &7» &fMarket Trends", 6));
    }

    private MenuTemplate load(String fileName, String defaultTitle, int defaultRows) {
        File file = new File(plugin.getDataFolder(), "gui/" + fileName);
        if (!file.exists()) {
            plugin.saveResource("gui/" + fileName, false);
        }
        return MenuTemplate.load(file, defaultTitle, defaultRows);
    }

    public MenuTemplate get(String id) {
        return byId.get(id);
    }
}
