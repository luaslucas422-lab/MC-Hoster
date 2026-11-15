package dev.darkerdawnfr.darkcombat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dark Combat by DarkerDawnFR: the DonutSMP combat tag. Hitting a player (or being hit) tags you both for
 * 20 seconds (/combat time <seconds> changes it). In combat you can't teleport or use blocked commands,
 * and logging out kills you. Shown in dark colours above the hotbar.
 */
public class DarkCombat extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, Long> until = new ConcurrentHashMap<>();
    private final Set<UUID> safeQuit = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("combat");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 10, 10);
    }

    // ------------------------------------------------------------------ API (also used by the other Dark plugins)

    public boolean inCombat(UUID id) {
        Long t = until.get(id);
        return t != null && t > System.currentTimeMillis();
    }

    /** Seconds of combat left, 0 if not in combat. */
    public int remaining(UUID id) {
        Long t = until.get(id);
        if (t == null) return 0;
        long ms = t - System.currentTimeMillis();
        return ms <= 0 ? 0 : (int) Math.ceil(ms / 1000.0);
    }

    public int seconds() {
        return Math.max(1, getConfig().getInt("seconds", 20));
    }

    public void tag(UUID id) {
        boolean was = inCombat(id);
        until.put(id, System.currentTimeMillis() + seconds() * 1000L);
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        if (!was) p.sendMessage(Util.color(getConfig().getString("messages.tagged", "")).replace("{time}", String.valueOf(seconds())));
        if (getConfig().getBoolean("stop-flying", true)) {
            if (p.isFlying() && !p.hasPermission("darkcombat.bypass")) p.setFlying(false);
            if (p.isGliding() && getConfig().getBoolean("stop-elytra", false)) p.setGliding(false);
        }
    }

    public void untag(UUID id) {
        until.remove(id);
    }

    // ------------------------------------------------------------------ events

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player a = attacker(e.getDamager());
        if (a == null || a.equals(victim)) return;
        if (getConfig().getStringList("disabled-worlds").contains(victim.getWorld().getName())) return;
        tag(victim.getUniqueId());
        tag(a.getUniqueId());
    }

    @EventHandler
    public void glide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && inCombat(p.getUniqueId()) && getConfig().getBoolean("stop-elytra", false)) {
            e.setCancelled(true);
            p.sendMessage(Util.color(getConfig().getString("messages.no-elytra", "&4You can't fly with an elytra in combat.")));
        }
    }

    @EventHandler
    public void death(PlayerDeathEvent e) {
        untag(e.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void command(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!inCombat(p.getUniqueId()) || p.hasPermission("darkcombat.bypass")) return;
        if (blocked(e.getMessage())) {
            e.setCancelled(true);
            p.sendMessage(Util.color(getConfig().getString("messages.blocked", "")).replace("{time}", String.valueOf(remaining(p.getUniqueId()))));
        }
    }

    /** "/home 1" -> is "home" in the blocked list? Also catches "/darkhomes:home". */
    public boolean blocked(String message) {
        String cmd = message.startsWith("/") ? message.substring(1) : message;
        cmd = cmd.split(" ")[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        for (String b : getConfig().getStringList("blocked-commands")) if (b.equalsIgnoreCase(cmd)) return true;
        return false;
    }

    @EventHandler
    public void kick(PlayerKickEvent e) {
        safeQuit.add(e.getPlayer().getUniqueId()); // kicked by an admin or the server stopping, not combat logging
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        boolean safe = safeQuit.remove(p.getUniqueId()) || stopping();
        if (!inCombat(p.getUniqueId())) return;
        untag(p.getUniqueId());
        if (safe || !getConfig().getBoolean("kill-on-logout", true) || p.hasPermission("darkcombat.bypass")) return;
        p.setHealth(0);
        Bukkit.broadcastMessage(Util.color(getConfig().getString("messages.logged-out", "")).replace("{player}", p.getName()));
    }

    private static boolean stopping() {
        try {
            return Bukkit.isStopping();
        } catch (Throwable notPaper) {
            return false;
        }
    }

    /** Above the hotbar, in dark colours, every half second. */
    private void tick() {
        for (Map.Entry<UUID, Long> e : until.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            int left = remaining(e.getKey());
            if (p == null) {
                if (left == 0) until.remove(e.getKey());
                continue;
            }
            if (left == 0) {
                until.remove(e.getKey());
                Chat.actionBar(p, Util.color(getConfig().getString("messages.ended-bar", "&8You are no longer in combat")));
                p.sendMessage(Util.color(getConfig().getString("messages.ended", "")));
                continue;
            }
            int total = seconds();
            int bars = 20, filled = Math.round(bars * left / (float) total);
            String bar = "&4" + "|".repeat(Math.max(0, filled)) + "&8" + "|".repeat(Math.max(0, bars - filled));
            Chat.actionBar(p, Util.color(getConfig().getString("messages.bar", "&4⚔ &8Combat &4{time}s")
                    .replace("{time}", String.valueOf(left)).replace("{bar}", bar)));
        }
    }

    // ------------------------------------------------------------------ commands

    private boolean say(CommandSender s, String text) {
        s.sendMessage(Util.color(getConfig().getString("prefix", "&8[&4Combat&8] ") + text));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission("darkcombat.admin");
        switch (sub) {
            case "time", "set" -> {
                if (!admin) return say(s, "&cOnly ops can change the combat time.");
                if (a.length < 2) return say(s, "&7Combat lasts &4" + seconds() + "s&7. Change it: /combat time <seconds>");
                int n;
                try {
                    n = Integer.parseInt(a[1].replace("s", ""));
                } catch (NumberFormatException e) {
                    return say(s, "&c" + a[1] + " is not a number of seconds.");
                }
                if (n < 1 || n > 3600) return say(s, "&cUse 1 to 3600 seconds.");
                getConfig().set("seconds", n);
                saveConfig();
                return say(s, "&7Combat now lasts &4" + n + " seconds&7.");
            }
            case "reload" -> {
                if (!admin) return true;
                reloadConfig();
                return say(s, "&7Reloaded. Combat lasts &4" + seconds() + "s&7.");
            }
            case "test" -> {
                if (admin) test(s);
                return true;
            }
            case "untag" -> {
                if (!admin || a.length < 2) return say(s, "&7Usage: /combat untag <player>");
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) untag(t.getUniqueId());
                return say(s, "&7Done.");
            }
            default -> {
                if (s instanceof Player p && inCombat(p.getUniqueId())) say(s, "&7You're in combat for &4" + remaining(p.getUniqueId()) + "s&7.");
                else say(s, "&7You're not in combat. Combat lasts &4" + seconds() + "s&7.");
                if (admin) say(s, "&8/combat time <seconds>  /combat untag <player>  /combat reload");
                return true;
            }
        }
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        String backup = getConfig().saveToString();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try {
            if (seconds() != 20) fails.add("default is " + seconds() + "s");
            tag(a);
            if (!inCombat(a) || inCombat(b) || remaining(a) != 20) fails.add("tag: " + remaining(a));
            onCommand(Bukkit.getConsoleSender(), getCommand("combat"), "combat", new String[]{"time", "35"});
            reloadConfig();
            if (seconds() != 35) fails.add("/combat time didn't save");
            tag(b);
            if (remaining(b) != 35) fails.add("new time not used: " + remaining(b));
            until.put(a, System.currentTimeMillis() - 1);
            if (inCombat(a)) fails.add("didn't run out");
            if (!blocked("/home 1") || !blocked("/darkhomes:home") || !blocked("/TPA Geo") || blocked("/msg hi") || blocked("/ah"))
                fails.add("blocked commands");
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            until.remove(a);
            until.remove(b);
            try {
                java.nio.file.Files.writeString(new java.io.File(getDataFolder(), "config.yml").toPath(), backup);
            } catch (java.io.IOException ignored) {
            }
            reloadConfig();
        }
        String r = fails.isEmpty() ? "DARKCOMBAT TEST OK" : "DARKCOMBAT TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1 && s.hasPermission("darkcombat.admin")) out.addAll(List.of("time", "untag", "reload"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.startsWith(last));
        return out;
    }
}
