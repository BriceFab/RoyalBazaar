package com.mystipixel.royalbazaar.gui;

import com.mystipixel.royalbazaar.util.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Text entry through a native dialog (Paper's Dialog API): a window with a text field and
 * Done / Cancel buttons, built per prompt and sent to that player only. Nothing is placed in the
 * world, so there is no block to borrow, guard or restore, whatever happens to the player meanwhile.
 *
 * <p>The callback runs on the main thread at most once: with the typed text (trimmed) on Done, or
 * with "" on Cancel or Escape (the confirmation dialog's "no" action is also its exit action), so
 * callers treat it as cancelled. If the player never answers (disconnect, another dialog), the
 * button callbacks simply expire.
 */
public final class TextInput {

    private static final String FIELD = "input";
    private static final int MAX_LENGTH = 64;
    /** How long the buttons keep working; the prompt is dead after this anyway. */
    private static final Duration LIFETIME = Duration.ofMinutes(5);

    private final JavaPlugin plugin;
    private final Supplier<String> confirmLabel;
    private final Supplier<String> cancelLabel;

    public TextInput(JavaPlugin plugin, Supplier<String> confirmLabel, Supplier<String> cancelLabel) {
        this.plugin = plugin;
        this.confirmLabel = confirmLabel;
        this.cancelLabel = cancelLabel;
    }

    /**
     * Ask {@code player} for a line of text. {@code hints} describe what to type and become the
     * dialog's title (lines made only of '^' are dropped: they pointed at the old sign's input line).
     */
    public void request(Player player, List<String> hints, Consumer<String> callback) {
        String title = hints.stream()
                .filter(line -> !line.replaceAll("&[0-9a-fk-or]", "").matches("\\^*"))
                .collect(Collectors.joining(" "));
        AtomicBoolean answered = new AtomicBoolean();
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(LIFETIME).build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Text.color(title))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .inputs(List.of(DialogInput.text(FIELD, Text.color(title))
                                .labelVisible(false)
                                .maxLength(MAX_LENGTH)
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Text.color(confirmLabel.get()))
                                .action(DialogAction.customClick((response, audience) -> {
                                    String text = response.getText(FIELD);
                                    answer(answered, callback, text == null ? "" : text.trim());
                                }, once))
                                .build(),
                        ActionButton.builder(Text.color(cancelLabel.get()))
                                .action(DialogAction.customClick((response, audience) ->
                                        answer(answered, callback, ""), once))
                                .build())));

        // Leave the chest menu first; the dialog replaces it on screen.
        player.closeInventory();
        player.showDialog(dialog);
    }

    private void answer(AtomicBoolean answered, Consumer<String> callback, String text) {
        if (answered.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(text));
        }
    }

    /** True when nothing but the player's own inventory is open, i.e. no other menu is on screen. */
    public static boolean showingOwnInventory(Player player) {
        InventoryType type = player.getOpenInventory().getTopInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }
}
