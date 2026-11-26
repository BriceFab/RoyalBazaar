package com.mystipixel.royalbazaar.util;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * An extra label for a plugin command, set in config.yml. Unlike a commands.yml alias, it runs the same
 * executor and tab completer, so players get the command's suggestions under the new name.
 */
public final class CommandAlias extends Command {

    private final PluginCommand target;

    private CommandAlias(String label, PluginCommand target) {
        super(label, target.getDescription(), "/" + label, List.of());
        setPermission(target.getPermission());
        this.target = target;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        return target.execute(sender, label, args);
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        return target.tabComplete(sender, alias, args);
    }

    /** Registers each label for {@code target}; a label another command already owns is skipped with a warning. */
    public static void registerAll(Plugin plugin, PluginCommand target, List<String> labels) {
        String prefix = plugin.getName().toLowerCase(Locale.ROOT);
        for (String raw : labels) {
            String label = raw.toLowerCase(Locale.ROOT).trim();
            if (label.isEmpty() || label.contains(" ") || label.contains(":")) {
                plugin.getLogger().warning("Ignoring command alias '" + raw + "': use one word without ':'.");
                continue;
            }
            if (!plugin.getServer().getCommandMap().register(label, prefix, new CommandAlias(label, target))) {
                plugin.getLogger().warning("Command alias /" + label + " is taken by another command; only /"
                        + prefix + ":" + label + " opens this one.");
            }
        }
    }
}
