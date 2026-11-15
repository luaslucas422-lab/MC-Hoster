package dev.darkerdawnfr.darktpa;

import org.bukkit.entity.Player;

/** "[ACCEPT] [DENY]" buttons in chat: Paper (Adventure), Spigot (BungeeCord chat), or plain text. */
final class Chat {
    private Chat() {}

    /** Sends text followed by buttons; each button is {label, command, hover}. */
    static void buttons(Player p, String text, String[][] buttons) {
        try {
            PaperChat.send(p, text, buttons);
            return;
        } catch (Throwable ignored) {
        }
        try {
            SpigotChat.send(p, text, buttons);
            return;
        } catch (Throwable ignored) {
        }
        StringBuilder b = new StringBuilder(text);
        for (String[] btn : buttons) b.append(' ').append(btn[0]).append(" (").append(btn[1]).append(')');
        p.sendMessage(b.toString());
    }

    private static final class PaperChat {
        static void send(Player p, String text, String[][] buttons) {
            var ser = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
            net.kyori.adventure.text.Component c = ser.deserialize(text);
            for (String[] b : buttons)
                c = c.append(net.kyori.adventure.text.Component.text(" ")).append(ser.deserialize(b[0])
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(b[1]))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(ser.deserialize(b[2]))));
            p.sendMessage(c);
        }
    }

    private static final class SpigotChat {
        static void send(Player p, String text, String[][] buttons) {
            java.util.List<net.md_5.bungee.api.chat.BaseComponent> parts = new java.util.ArrayList<>(
                    java.util.Arrays.asList(net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text)));
            for (String[] b : buttons) {
                parts.add(new net.md_5.bungee.api.chat.TextComponent(" "));
                net.md_5.bungee.api.chat.TextComponent t = new net.md_5.bungee.api.chat.TextComponent(
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(b[0]));
                t.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, b[1]));
                parts.add(t);
            }
            p.spigot().sendMessage(parts.toArray(new net.md_5.bungee.api.chat.BaseComponent[0]));
        }
    }
}
