package dev.darkerdawnfr.darkshop;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Playtime money: every 30 minutes of real (not AFK) playing you get money, more the longer you've played on the server.
 * amount = base + per-hour-played * hours played, up to max. Everything is in config.yml under playtime-rewards.
 */
public class Playtime implements Listener {
    private final DarkShop plugin;
    /** minutes played (not AFK) since the last reward */
    private final Map<UUID, Integer> minutes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();

    public Playtime(DarkShop plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::minute, 20 * 60, 20 * 60);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("playtime-rewards");
        return c != null ? c : plugin.getConfig().createSection("playtime-rewards");
    }

    public boolean enabled() {
        return cfg().getBoolean("enabled", true);
    }

    public int every() {
        return Math.max(1, cfg().getInt("every-minutes", 30));
    }

    /** What someone with this many hours played gets each time. */
    public double amount(double hours) {
        double a = cfg().getDouble("base", 1000) + cfg().getDouble("per-hour-played", 20) * Math.max(0, hours);
        double max = cfg().getDouble("max", 5000);
        return Math.round((max > 0 ? Math.min(max, a) : a) * 100) / 100.0;
    }

    public static double hours(Player p) {
        try {
            return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 72000.0; // the statistic is in ticks
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Minutes until this player's next reward. */
    public int left(UUID id) {
        return Math.max(0, every() - minutes.getOrDefault(id, 0));
    }

    public boolean afk(UUID id, long now) {
        long afk = Math.max(1, cfg().getLong("afk-minutes", 5)) * 60_000L;
        Long l = lastActive.get(id);
        return l == null || now - l > afk;
    }

    /** One minute passed for this player. Returns the money they earned now (0 most minutes). */
    public double count(UUID id, double hours, boolean active) {
        if (!active) return 0;
        int m = minutes.merge(id, 1, Integer::sum);
        if (m < every()) return 0;
        minutes.put(id, 0);
        return amount(hours);
    }

    private void minute() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            double hours = hours(p);
            double got = count(p.getUniqueId(), hours, !afk(p.getUniqueId(), now));
            if (got <= 0) continue;
            plugin.money().give(p.getUniqueId(), got);
            p.sendMessage(Util.color(cfg().getString("message", "&a+{amount} &7for playing {minutes} minutes &8(you've played {hours}h)")
                    .replace("{amount}", Util.money(got)).replace("{minutes}", String.valueOf(every()))
                    .replace("{hours}", String.valueOf((int) hours))));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        }
    }

    public void active(UUID id) {
        lastActive.put(id, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        // turning your head or walking counts, being pushed by water doesn't really matter
        if (e.getTo() == null) return;
        if (e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch()
                || e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockZ() != e.getTo().getBlockZ())
            active(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation")
    public void chat(AsyncPlayerChatEvent e) {
        active(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void command(PlayerCommandPreprocessEvent e) {
        active(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void interact(PlayerInteractEvent e) {
        active(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        active(e.getPlayer().getUniqueId());
    }
}
