package dev.darkerdawnfr.darkdiscord;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Clickable links that work on every server: Paper's Adventure first, then Spigot's BungeeCord chat,
 * then plain text. Each lives in its own class so a server without it never loads it.
 */
final class Chat {
    private Chat() {}

    static void link(CommandSender to, String text, String url) {
        if (to instanceof Player p) {
            try {
                PaperChat.send(p, text, url);
                return;
            } catch (Throwable ignored) {
            }
            try {
                SpigotChat.send(p, text, url);
                return;
            } catch (Throwable ignored) {
            }
        }
        to.sendMessage(text + ChatColor.WHITE + url);
    }

    private static final class PaperChat {
        static void send(Player p, String text, String url) {
            net.kyori.adventure.text.Component c = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize(text + ChatColor.WHITE + url)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            net.kyori.adventure.text.Component.text("Click to open " + url)));
            p.sendMessage(c);
        }
    }

    private static final class SpigotChat {
        static void send(Player p, String text, String url) {
            net.md_5.bungee.api.chat.TextComponent c = new net.md_5.bungee.api.chat.TextComponent(
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text + ChatColor.WHITE + url));
            c.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, url));
            p.spigot().sendMessage(c);
        }
    }
}
