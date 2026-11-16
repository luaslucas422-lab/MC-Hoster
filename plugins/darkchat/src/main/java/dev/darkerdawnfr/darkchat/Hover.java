package dev.darkerdawnfr.darkchat;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** "[Diamond Sword]" in chat that shows the real item when hovered (Paper); plain text elsewhere. */
final class Hover {
    private Hover() {}

    static void send(Player to, String before, String label, ItemStack item, String after) {
        try {
            PaperHover.send(to, before, label, item, after);
            return;
        } catch (Throwable ignored) {
        }
        to.sendMessage(before + label + after);
    }

    private static final class PaperHover {
        static void send(Player to, String before, String label, ItemStack item, String after) {
            var ser = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
            to.sendMessage(ser.deserialize(before).append(ser.deserialize(label).hoverEvent(item)).append(ser.deserialize(after)));
        }
    }
}
