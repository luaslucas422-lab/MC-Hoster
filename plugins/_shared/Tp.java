package PKG;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The shared Dark teleport: a countdown (5 seconds by default) where moving cancels it, and no teleporting in combat
 * (asks Dark Combat if it's installed). Same code in every Dark plugin that teleports.
 */
public final class Tp {
    private final JavaPlugin plugin;
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    public Tp(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean busy(UUID id) {
        return pending.containsKey(id);
    }

    public void cancel(UUID id) {
        BukkitTask t = pending.remove(id);
        if (t != null) t.cancel();
    }

    /** True if Dark Combat says this player is in combat. */
    public static boolean inCombat(Player p) {
        Plugin c = Bukkit.getPluginManager().getPlugin("DarkCombat");
        if (c == null || !c.isEnabled()) return false;
        try {
            Method m = c.getClass().getMethod("inCombat", UUID.class);
            return Boolean.TRUE.equals(m.invoke(c, p.getUniqueId()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    /**
     * Counts down, then teleports p to where.get(). If cancelOnMove, walking to another block stops it.
     * where can return null (e.g. the other player left): then nothing happens and whyNot is shown.
     */
    public void start(Player p, int seconds, boolean cancelOnMove, Supplier<Location> where, String prefix, Runnable after) {
        if (inCombat(p)) {
            p.sendMessage(color(prefix + "&cYou can't teleport while you're in combat."));
            return;
        }
        cancel(p.getUniqueId());
        Location start = p.getLocation();
        int[] left = {Math.max(0, seconds)};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                cancel(p.getUniqueId());
                return;
            }
            Location now = p.getLocation();
            if (cancelOnMove && (now.getWorld() != start.getWorld() || now.getBlockX() != start.getBlockX()
                    || now.getBlockY() != start.getBlockY() || now.getBlockZ() != start.getBlockZ())) {
                cancel(p.getUniqueId());
                p.sendTitle(color("&cCancelled"), color("&7You moved"), 0, 30, 10);
                p.sendMessage(color(prefix + "&cTeleport cancelled because you moved."));
                return;
            }
            if (inCombat(p)) {
                cancel(p.getUniqueId());
                p.sendMessage(color(prefix + "&cTeleport cancelled, you're in combat."));
                return;
            }
            int n = left[0]--;
            if (n <= 0) {
                cancel(p.getUniqueId());
                Location to = where.get();
                if (to == null) return;
                p.setFallDistance(0);
                p.teleport(to);
                p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1f);
                if (after != null) after.run();
                return;
            }
            p.sendTitle(color("&dTeleporting in " + n), color(cancelOnMove ? "&7Don't move" : ""), 0, 25, 0);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1f);
        }, 0, 20);
        pending.put(p.getUniqueId(), task[0]);
    }
}
