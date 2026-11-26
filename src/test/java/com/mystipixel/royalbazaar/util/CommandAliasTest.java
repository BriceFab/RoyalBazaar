package com.mystipixel.royalbazaar.util;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandAliasTest {

    private final CommandMap map = mock(CommandMap.class);
    private final Logger logger = mock(Logger.class);
    private final Plugin plugin = mock(Plugin.class);
    private final PluginCommand target = mock(PluginCommand.class);

    CommandAliasTest() {
        Server server = mock(Server.class);
        when(server.getCommandMap()).thenReturn(map);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Plugin");
        when(plugin.getLogger()).thenReturn(logger);
        when(target.getDescription()).thenReturn("");
    }

    @Test void anAliasRunsAndCompletesLikeItsCommand() {
        when(map.register(anyString(), anyString(), any())).thenReturn(true);
        CommandAlias.registerAll(plugin, target, List.of(" Shop "));
        ArgumentCaptor<Command> registered = ArgumentCaptor.forClass(Command.class);
        verify(map).register(eq("shop"), eq("plugin"), registered.capture());

        CommandSender sender = mock(CommandSender.class);
        String[] args = {"sell"};
        when(target.tabComplete(sender, "shop", args)).thenReturn(List.of("sellall"));
        assertEquals(List.of("sellall"), registered.getValue().tabComplete(sender, "shop", args));
        registered.getValue().execute(sender, "shop", args);
        verify(target).execute(sender, "shop", args);
    }

    @Test void aTakenOrInvalidLabelIsReported() {
        when(map.register(anyString(), anyString(), any())).thenReturn(false);
        CommandAlias.registerAll(plugin, target, List.of("taken", "two words"));
        verify(map, times(1)).register(anyString(), anyString(), any());
        ArgumentCaptor<String> warnings = ArgumentCaptor.forClass(String.class);
        verify(logger, times(2)).warning(warnings.capture());
        assertTrue(warnings.getAllValues().get(0).contains("/taken"));
    }
}
