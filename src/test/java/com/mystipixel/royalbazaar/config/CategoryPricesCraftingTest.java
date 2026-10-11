package com.mystipixel.royalbazaar.config;

import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.PricingEngine;
import com.mystipixel.royalbazaar.market.TradeMode;
import com.mystipixel.royalbazaar.market.TradeSide;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Same rule as `python3 tools/verify_bazaar.py crafting`: with every item at its base price, and again with
// every item at its floor, the bazaar must not pay more for a recipe's output than its inputs cost to buy.
// Furnace, smoker and blast furnace recipes also pay for fuel; a campfire burns nothing.
class CategoryPricesCraftingTest {

    private static final Path CATEGORIES = Path.of("src", "main", "resources", "categories");
    private static final Path RECIPES = Path.of("tools", "vanilla_recipes.txt");
    // keep in step with DEFAULT_FUEL and DEFAULT_FUEL_SMELTS in verify_bazaar.py
    private static final String FUEL = "coal";
    private static final double FUEL_SMELTS = 8;
    private static final Set<String> FUELED_KINDS = Set.of("smelting", "blasting", "smoking");
    private static final Set<String> COOKING_KINDS = Set.of("smelting", "blasting", "smoking", "campfire");

    private record Slot(int count, List<String> items) {
    }

    private record Recipe(String id, String kind, int count, String output, List<Slot> inputs) {
    }

    private record RecipeTable(List<Recipe> recipes, Map<String, String> remainders) {
    }

    // one entry per state: [0] mid at base, [1] mid at floor
    private record Listed(String where, MarketItem[] states) {
    }

    @Test
    void shippedCategoriesHaveNoCraftingArbitrage() throws IOException {
        RecipeTable table = loadRecipes();
        Map<String, Listed> listed = loadShipped();
        List<String> problems = violations(listed, table);
        assertTrue(problems.isEmpty(), problems.size() + " recipe printer(s) in the shipped categories:\n"
                + String.join("\n", problems));
    }

    @Test
    void recipeTableReachesTheShippedItems() throws IOException {
        RecipeTable table = loadRecipes();
        Map<String, Listed> listed = loadShipped();
        assertTrue(listed.size() > 300, "read only " + listed.size() + " listed items");
        long checked = table.recipes().stream()
                .filter(r -> listed.containsKey(r.output()))
                .filter(r -> r.inputs().stream().allMatch(s -> s.items().stream().anyMatch(listed::containsKey)))
                .count();
        assertTrue(checked > 150, "only " + checked + " recipes are made of listed items; is the table parsed?");
        long cooked = table.recipes().stream()
                .filter(r -> COOKING_KINDS.contains(r.kind()))
                .filter(r -> listed.containsKey(r.output()))
                .filter(r -> r.inputs().stream().allMatch(s -> s.items().stream().anyMatch(listed::containsKey)))
                .count();
        assertTrue(cooked > 50, "only " + cooked + " cooking recipes are made of listed items");
        assertTrue(listed.containsKey(FUEL), "the fuel item " + FUEL + " is not listed, so smelting would be free");
    }

    @Test
    void ruleFlagsAChestPricedOverItsPlanks() throws IOException {
        RecipeTable table = loadRecipes();
        Map<String, Listed> listed = new HashMap<>();
        listed.put("oak_planks", listed("oak_planks", 1.0, 0.05, 0.4, TradeMode.BOTH));
        listed.put("chest", listed("chest", 20.0, 0.05, 0.4, TradeMode.BOTH));
        assertEquals(2, violations(listed, table).size());

        listed.put("chest", listed("chest", 8.0, 0.05, 0.4, TradeMode.BOTH));
        assertEquals(List.of(), violations(listed, table));

        listed.put("chest", listed("chest", 20.0, 0.05, 0.4, TradeMode.BUY_ONLY));
        assertEquals(List.of(), violations(listed, table), "players can't sell a buy_only output here");
    }

    @Test
    void smeltingPaysForCoal() throws IOException {
        RecipeTable table = loadRecipes();
        Map<String, Listed> listed = new HashMap<>();
        listed.put("coal", listed("coal", 5.0, 0.05, 0.4, TradeMode.BOTH));
        listed.put("raw_iron", listed("raw_iron", 10.0, 0.05, 0.4, TradeMode.BOTH));
        listed.put("iron_ingot", listed("iron_ingot", 12.0, 0.05, 0.4, TradeMode.BOTH));
        // furnace and blast furnace, at base and at floor: 10.25 + 5.125 / 8 < 11.70
        assertEquals(4, violations(listed, table).size());

        listed.put("raw_iron", listed("raw_iron", 10.9, 0.05, 0.4, TradeMode.BOTH));
        assertEquals(List.of(), violations(listed, table));

        listed.remove("coal");
        assertEquals(4, violations(listed, table).size(), "an unlisted fuel counts as free");
    }

    @Test
    void campfireCookingNeedsNoFuel() throws IOException {
        RecipeTable table = loadRecipes();
        Map<String, Listed> listed = new HashMap<>();
        listed.put("coal", listed("coal", 5.0, 0.05, 0.4, TradeMode.BOTH));
        listed.put("beef", listed("beef", 5.0, 0.05, 0.4, TradeMode.BOTH));
        // covers beef plus coal in a furnace or smoker, but not beef alone on a campfire
        listed.put("cooked_beef", listed("cooked_beef", 5.6, 0.05, 0.4, TradeMode.BOTH));
        List<String> problems = violations(listed, table);
        assertEquals(2, problems.size());
        assertTrue(problems.stream().allMatch(p -> p.contains("cooked_beef_from_campfire_cooking")), problems::toString);

        listed.put("cooked_beef", listed("cooked_beef", 5.2, 0.05, 0.4, TradeMode.BOTH));
        assertEquals(List.of(), violations(listed, table));
    }

    private static List<String> violations(Map<String, Listed> listed, RecipeTable table) {
        List<String> problems = new ArrayList<>();
        Listed fuel = listed.get(FUEL);
        boolean fuelBuyable = fuel != null && fuel.states()[0].tradeMode().allows(TradeSide.BUY);
        for (Recipe r : table.recipes()) {
            Listed out = listed.get(r.output());
            if (out == null || !out.states()[0].tradeMode().allows(TradeSide.SELL)) {
                continue;
            }
            List<List<Listed>> slots = new ArrayList<>();
            for (Slot s : r.inputs()) {
                slots.add(s.items().stream().map(listed::get)
                        .filter(l -> l != null && l.states()[0].tradeMode().allows(TradeSide.BUY)).toList());
            }
            if (slots.stream().anyMatch(List::isEmpty)) {
                continue;
            }
            for (int state = 0; state < 2; state++) {
                final int st = state;
                double cost = 0;
                for (int i = 0; i < slots.size(); i++) {
                    Slot slot = r.inputs().get(i);
                    cost += slot.count() * slots.get(i).stream()
                            .mapToDouble(l -> PricingEngine.buyPrice(l.states()[st])).min().orElseThrow();
                    String handedBack = slot.items().size() == 1 ? table.remainders().get(slot.items().get(0)) : null;
                    Listed back = handedBack == null ? null : listed.get(handedBack);
                    if (back != null) {
                        cost -= slot.count() * PricingEngine.sellPrice(back.states()[st]);
                    }
                }
                if (fuelBuyable && FUELED_KINDS.contains(r.kind())) {
                    cost += PricingEngine.buyPrice(fuel.states()[st]) / FUEL_SMELTS;
                }
                double pays = r.count() * PricingEngine.sellPrice(out.states()[st]);
                if (pays > cost + 1e-9) {
                    problems.add(String.format("at %s: %s pays %.2f for %d %s, inputs cost %.2f (%s)",
                            st == 0 ? "base" : "floor", out.where(), pays, r.count(), r.output(), cost, r.id()));
                }
            }
        }
        return problems;
    }

    private static Map<String, Listed> loadShipped() throws IOException {
        Map<String, Listed> listed = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(CATEGORIES)) {
            files = s.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
        }
        for (Path file : files) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file.toFile());
            ConfigurationSection defaults = yml.getConfigurationSection("defaults");
            ConfigurationSection items = yml.getConfigurationSection("items");
            for (String key : items.getKeys(false)) {
                ConfigurationSection it = items.getConfigurationSection(key);
                String item = it.getString("item", "");
                if (!item.startsWith("minecraft:") || !it.isDouble("base_price") && !it.isInt("base_price")) {
                    continue;
                }
                TradeMode mode = TradeMode.parse(it.getString("trade_mode", defaults.getString("trade_mode", "both")));
                listed.put(item.substring("minecraft:".length()), listed(file.getFileName() + " " + key,
                        it.getDouble("base_price"),
                        it.getDouble("spread", defaults.getDouble("spread", 0.05)),
                        it.getDouble("floor_pct", defaults.getDouble("floor_pct", 0.4)),
                        mode));
            }
        }
        return listed;
    }

    private static Listed listed(String where, double base, double spread, double floorPct, TradeMode mode) {
        double floor = base * floorPct;
        MarketItem atBase = new MarketItem(where, "test", null, "", base, spread, 50000, 0.02, floor, base * 3, mode);
        MarketItem atFloor = new MarketItem(where, "test", null, "", base, spread, 50000, 0.02, floor, base * 3, mode);
        atFloor.loadState(floor, floor, 0);
        return new Listed(where, new MarketItem[]{atBase, atFloor});
    }

    private static RecipeTable loadRecipes() throws IOException {
        Map<String, List<String>> tags = new HashMap<>();
        Map<String, String> remainders = new HashMap<>();
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(RECIPES)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "tag" -> tags.put(parts[1], List.of(parts).subList(2, parts.length));
                case "remainder" -> remainders.put(parts[1], parts[2]);
                default -> rows.add(parts);
            }
        }
        List<Recipe> recipes = new ArrayList<>();
        for (String[] parts : rows) {
            assertEquals("<-", parts[4], "malformed recipe line for " + parts[0]);
            List<Slot> inputs = new ArrayList<>();
            for (int i = 5; i < parts.length; i++) {
                String[] term = parts[i].split("\\*", 2);
                List<String> items = new ArrayList<>();
                for (String alt : term[1].split("\\|")) {
                    items.addAll(alt.startsWith("#") ? tags.get(alt.substring(1)) : List.of(alt));
                }
                inputs.add(new Slot(Integer.parseInt(term[0]), items));
            }
            recipes.add(new Recipe(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3], inputs));
        }
        return new RecipeTable(recipes, remainders);
    }
}
