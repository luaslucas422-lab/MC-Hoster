package dev.darkerdawnfr.darkrsaddon;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/** Asks Dark Settings (/settings) whether a player has something turned on. On when Dark Settings isn't installed. */
final class Settings {
    private static Method method;
    private static Plugin owner;

    private Settings() {}

    static boolean on(UUID id, String key) {
        return on(id, key, true);
    }

    /** whenMissing = the answer when Dark Settings isn't installed. */
    static boolean on(UUID id, String key, boolean whenMissing) {
        Plugin s = Bukkit.getPluginManager().getPlugin("DarkSettings");
        if (s == null || !s.isEnabled()) return whenMissing;
        try {
            if (owner != s || method == null) {
                method = s.getClass().getMethod("enabled", UUID.class, String.class);
                owner = s;
            }
            Object r = method.invoke(s, id, key);
            return r instanceof Boolean b ? b : whenMissing;
        } catch (Throwable t) {
            return whenMissing;
        }
    }
}
