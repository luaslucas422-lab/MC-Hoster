package dev.darkerdawnfr.darkshop;

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
        Plugin s = Bukkit.getPluginManager().getPlugin("DarkSettings");
        if (s == null || !s.isEnabled()) return true;
        try {
            if (owner != s || method == null) {
                method = s.getClass().getMethod("enabled", UUID.class, String.class);
                owner = s;
            }
            return !Boolean.FALSE.equals(method.invoke(s, id, key));
        } catch (Throwable t) {
            return true;
        }
    }
}
