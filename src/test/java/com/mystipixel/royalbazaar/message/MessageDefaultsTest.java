package com.mystipixel.royalbazaar.message;

import com.mystipixel.royalbazaar.config.CategoryConfig;
import com.mystipixel.royalbazaar.config.PluginConfig;
import com.mystipixel.royalbazaar.data.BazaarDatabase;
import com.mystipixel.royalbazaar.hooks.EcoHook;
import com.mystipixel.royalbazaar.hooks.EconGuardHook;
import com.mystipixel.royalbazaar.hooks.VaultHook;
import com.mystipixel.royalbazaar.market.MarketItem;
import com.mystipixel.royalbazaar.market.MarketManager;
import com.mystipixel.royalbazaar.market.TradeMode;
import com.mystipixel.royalbazaar.market.TradeSide;
import com.mystipixel.royalbazaar.service.BazaarService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Every message key the code reads must ship in messages.yml with the same text as the code fallback,
// so a server whose messages.yml predates a key still sees the shipped wording.
class MessageDefaultsTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern MESSAGE_CALL =
            Pattern.compile("\\bmessages(?:\\(\\))?\\s*\\.\\s*(get|send|format|component|lines)\\s*\\(");
    private static final Pattern MENU_LOAD = Pattern.compile("load\\(\"([a-z_]+\\.yml)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private record Call(String where, String key, Object fallback) {
    }

    @Test
    void everyKeyReadInCodeShipsWithTheSameDefault() throws IOException {
        YamlConfiguration shipped = load("/messages.yml");
        List<Call> calls = scanMessageCalls();
        assertTrue(calls.size() > 50, "found only " + calls.size() + " message reads; is the scan still matching?");

        List<String> problems = new ArrayList<>();
        for (Call call : calls) {
            if (!shipped.contains(call.key())) {
                problems.add(call.where() + ": " + call.key() + " is not in messages.yml");
            } else if (call.fallback() instanceof List<?> lines) {
                if (!shipped.getStringList(call.key()).equals(lines)) {
                    problems.add(call.where() + ": " + call.key() + " fallback " + lines
                            + " differs from messages.yml " + shipped.getStringList(call.key()));
                }
            } else if (!call.fallback().equals(shipped.getString(call.key()))) {
                problems.add(call.where() + ": " + call.key() + " fallback \"" + call.fallback()
                        + "\" differs from messages.yml \"" + shipped.getString(call.key()) + "\"");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void messagesYmlHasNoDuplicateKeys() throws IOException {
        // YAML keeps only the last of two equal keys, silently dropping the first block
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String line : resource("/messages.yml").split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            String key = String.join(".", path);
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        assertTrue(duplicates.isEmpty(), "duplicate keys in messages.yml: " + duplicates);
    }

    @Test
    void menuTitleFallbacksMatchTheShippedMenus() throws IOException {
        String source = Files.readString(SOURCES.resolve("com/mystipixel/royalbazaar/gui/menu/MenuManager.java"));
        Matcher m = MENU_LOAD.matcher(source);
        int found = 0;
        while (m.find()) {
            found++;
            YamlConfiguration menu = load("/gui/" + m.group(1));
            assertEquals(menu.getString("title"), unescape(m.group(2)), m.group(1) + " title fallback");
        }
        assertEquals(5, found, "menus loaded by MenuManager");
    }

    // runs the placeholder and refusal code so fallbacks built at runtime are covered too
    @Test
    void placeholderAndRefusalTextComesFromMessagesYml() throws IOException {
        YamlConfiguration shipped = load("/messages.yml");
        Map<String, String> fallbacks = new HashMap<>();
        MessageManager messages = mock(MessageManager.class);
        when(messages.get(anyString(), anyString())).thenAnswer(i -> {
            fallbacks.put(i.getArgument(0), i.getArgument(1));
            return i.getArgument(1);
        });
        when(messages.format(anyString(), anyString(), anyMap())).thenAnswer(i -> {
            fallbacks.put(i.getArgument(0), i.getArgument(1));
            return i.getArgument(1);
        });
        MarketManager market = mock(MarketManager.class);
        VaultHook vault = mock(VaultHook.class);
        when(vault.formatPrice(anyDouble())).thenReturn("1.00");
        BazaarService service = new BazaarService(mock(JavaPlugin.class), market, mock(BazaarDatabase.class),
                vault, mock(EcoHook.class), mock(EconGuardHook.class), mock(PluginConfig.class), messages);
        CategoryConfig category = mock(CategoryConfig.class);
        when(category.id()).thenReturn("test");
        when(category.displayName()).thenReturn("Test");
        CategoryConfig.Group group = new CategoryConfig.Group("g", "Group", "stone", 0, -1);
        for (TradeMode mode : TradeMode.values()) {
            MarketItem item = new MarketItem("minecraft:stone", "test", null, "Stone",
                    10, 0.05, 1000, 0.02, 1, 100, mode);
            when(market.get(item.id())).thenReturn(item);
            service.placeholders(item, null);
            service.groupPlaceholders(category, group, List.of(item));
            service.groupPlaceholders(category, group, List.of());
            service.tradeModeRefusal(item.id(), TradeSide.BUY);
            service.tradeModeRefusal(item.id(), TradeSide.SELL);
        }

        for (String key : List.of("price-unavailable", "trade.reasons.buy-disabled", "trade.reasons.sell-disabled",
                "trade-mode.both", "trade-mode.buy-only", "trade-mode.sell-only", "format.trend.flat",
                "format.trend.group-empty", "format.change.up", "format.change.no-history",
                "format.change.no-history-7d", "format.no-week-range")) {
            assertTrue(fallbacks.containsKey(key), key + " is read by the code");
        }
        fallbacks.forEach((key, fallback) ->
                assertEquals(shipped.getString(key), fallback, key + ": code fallback matches messages.yml"));
    }

    private static List<Call> scanMessageCalls() throws IOException {
        List<Call> calls = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("MessageManager.java"))
                    .toList();
        }
        for (Path file : files) {
            String source = Files.readString(file);
            Matcher m = MESSAGE_CALL.matcher(source);
            while (m.find()) {
                String where = file.getFileName() + ":" + lineOf(source, m.start());
                List<String> args = arguments(source, m.end());
                int keyIndex = m.group(1).equals("send") ? 1 : 0;
                assertTrue(args.size() > keyIndex + 1, where + ": message call without a fallback");
                String key = literal(args.get(keyIndex));
                assertNotNull(key, where + ": message key must be a string literal, not " + args.get(keyIndex));
                Object fallback = m.group(1).equals("lines")
                        ? listLiteral(args.get(keyIndex + 1)) : literal(args.get(keyIndex + 1));
                assertNotNull(fallback, where + ": fallback for " + key + " must be a literal");
                calls.add(new Call(where, key, fallback));
            }
        }
        return calls;
    }

    // top-level arguments of the call whose '(' ends just before start
    private static List<String> arguments(String source, int start) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                current.append(source, i, end + 1);
                i = end;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    args.add(current.toString().strip());
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        throw new IllegalStateException("unclosed call");
    }

    private static String literal(String expression) {
        if (expression.length() < 2 || !expression.startsWith("\"") || !expression.endsWith("\"")) {
            return null;
        }
        String body = expression.substring(1, expression.length() - 1);
        return body.matches("(?:[^\"\\\\]|\\\\.)*") ? unescape(body) : null;
    }

    private static List<String> listLiteral(String expression) {
        if (!expression.startsWith("List.of(") || !expression.endsWith(")")) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String arg : arguments(expression, "List.of(".length())) {
            String line = literal(arg);
            if (line == null) {
                return null;
            }
            out.add(line);
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = MessageDefaultsTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration load(String name) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource(name));
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IOException(e);
        }
        return yaml;
    }
}
