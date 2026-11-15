package dev.darkerdawnfr.darkshards;

import org.bukkit.entity.Player;

/** Action bar text on Paper (Adventure) or Spigot (BungeeCord chat); each in its own class so a missing one is never loaded. */
final class Chat {
    private Chat() {}

    static void actionBar(Player p, String text) {
        try {
            PaperChat.bar(p, text);
            return;
        } catch (Throwable ignored) {
        }
        try {
            SpigotChat.bar(p, text);
        } catch (Throwable ignored) {
        }
    }

    private static final class PaperChat {
        static void bar(Player p, String text) {
            p.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(text));
        }
    }

    private static final class SpigotChat {
        static void bar(Player p, String text) {
            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text));
        }
    }
}
