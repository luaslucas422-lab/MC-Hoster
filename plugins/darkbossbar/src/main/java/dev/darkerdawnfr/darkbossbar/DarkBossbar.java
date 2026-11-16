package dev.darkerdawnfr.darkbossbar;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dark Bossbar by DarkerDawnFR: a boss bar at the top of the screen, e.g. with your server's IP.
 * Any text, any colour, and it can flash between colours. {ip} is filled in by MC Hoster.
 */
public class DarkBossbar extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitTask task;
    private int frame;
    private String ip = "";
    private long ipRead;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("bar");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        restart();
    }

    @Override
    public void onDisable() {
        for (BossBar b : bars.values()) b.removeAll();
        bars.clear();
    }

    private void restart() {
        if (task != null) task.cancel();
        int speed = Math.max(2, getConfig().getInt("flash-speed-ticks", 10));
        task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            frame++;
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        }, 1, speed);
    }

    /** The server's address: what MC Hoster wrote to address.txt, or the "ip" setting. */
    public String ip() {
        String set = getConfig().getString("ip", "");
        if (set != null && !set.isEmpty()) return set;
        if (System.currentTimeMillis() - ipRead > 30_000) {
            ipRead = System.currentTimeMillis();
            try {
                File f = new File(getDataFolder(), "address.txt");
                if (f.exists()) ip = Files.readString(f.toPath()).trim();
            } catch (Exception ignored) {
            }
        }
        return ip.isEmpty() ? "your server" : ip;
    }

    private List<BarColor> colors() {
        List<BarColor> out = new ArrayList<>();
        for (String c : getConfig().getStringList("colors")) {
            try {
                out.add(BarColor.valueOf(c.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (out.isEmpty()) out.add(BarColor.PURPLE);
        return out;
    }

    public String text(Player p) {
        List<String> texts = getConfig().getStringList("texts");
        String t = texts.isEmpty() ? getConfig().getString("text", "{ip}") : texts.get((frame / Math.max(1, getConfig().getInt("text-every-flashes", 10))) % texts.size());
        List<String> cc = getConfig().getStringList("text-colors");
        String c = cc.isEmpty() ? "&d" : getConfig().getBoolean("flash", true) ? cc.get(frame % cc.size()) : cc.get(0);
        return Util.color(t.replace("{c}", c).replace("{ip}", ip()).replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max}", String.valueOf(Bukkit.getMaxPlayers())).replace("{player}", p == null ? "" : p.getName()));
    }

    public BarColor color() {
        List<BarColor> cs = colors();
        return getConfig().getBoolean("flash", true) ? cs.get(frame % cs.size()) : cs.get(0);
    }

    private void update(Player p) {
        BossBar b = bars.get(p.getUniqueId());
        if (!getConfig().getBoolean("enabled", true) || getConfig().getStringList("hide-in-worlds").contains(p.getWorld().getName())
                || !Settings.on(p.getUniqueId(), "bossbar")) {
            if (b != null) b.removeAll();
            bars.remove(p.getUniqueId());
            return;
        }
        BarStyle style;
        try {
            style = BarStyle.valueOf(getConfig().getString("style", "SOLID").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            style = BarStyle.SOLID;
        }
        if (b == null) {
            b = Bukkit.createBossBar(text(p), color(), style);
            b.addPlayer(p);
            bars.put(p.getUniqueId(), b);
        }
        b.setTitle(text(p));
        b.setColor(color());
        b.setStyle(style);
        b.setProgress(Math.max(0, Math.min(1, getConfig().getDouble("progress", 1.0))));
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (e.getPlayer().isOnline()) update(e.getPlayer());
        }, 5);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        BossBar b = bars.remove(e.getPlayer().getUniqueId());
        if (b != null) b.removeAll();
    }

    // ------------------------------------------------------------------ /bar

    private boolean say(CommandSender s, String text) {
        s.sendMessage(Util.color("&8[&dBossbar&8] " + text));
        return true;
    }

    private void set(String path, Object v) {
        getConfig().set(path, v);
        saveConfig();
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        String rest = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "";
        switch (sub) {
            case "text" -> {
                if (rest.isEmpty()) return say(s, "&7Usage: /bar text <text>  &8({ip} {online} {max} {player}, {c} = flashing colour)");
                set("texts", new ArrayList<String>());
                set("text", rest);
                say(s, "&aBar text set.");
            }
            case "addtext" -> {
                List<String> t = new ArrayList<>(getConfig().getStringList("texts"));
                if (t.isEmpty()) t.add(getConfig().getString("text", "{ip}"));
                t.add(rest);
                set("texts", t);
                say(s, "&aThe bar now switches between " + t.size() + " texts.");
            }
            case "color", "colour", "colors" -> {
                List<String> ok = new ArrayList<>();
                for (String c : rest.split("[ ,]+")) {
                    try {
                        ok.add(BarColor.valueOf(c.trim().toUpperCase(Locale.ROOT)).name());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (ok.isEmpty()) return say(s, "&7Usage: /bar color <colour> [colour...]  &8(PINK BLUE RED GREEN YELLOW PURPLE WHITE)");
                set("colors", ok);
                if (ok.size() > 1) set("flash", true);
                say(s, "&aBar colour" + (ok.size() > 1 ? "s: " + ok + " (flashing)" : ": " + ok.get(0)));
            }
            case "textcolor", "textcolors" -> {
                set("text-colors", Arrays.asList(rest.split("[ ,]+")));
                say(s, "&aText colours set. Use {c} in the text where the colour goes.");
            }
            case "flash" -> {
                set("flash", !rest.equalsIgnoreCase("off"));
                say(s, "&7Flashing " + (getConfig().getBoolean("flash") ? "&aon" : "&coff"));
            }
            case "speed" -> {
                int n;
                try {
                    n = Integer.parseInt(rest);
                } catch (NumberFormatException e) {
                    return say(s, "&7Usage: /bar speed <ticks>  &8(20 = 1 second, smaller = faster)");
                }
                set("flash-speed-ticks", Math.max(2, n));
                restart();
                say(s, "&aFlashes every " + Math.max(2, n) + " ticks.");
            }
            case "style" -> {
                try {
                    set("style", BarStyle.valueOf(rest.toUpperCase(Locale.ROOT)).name());
                    say(s, "&aStyle set.");
                } catch (IllegalArgumentException e) {
                    say(s, "&7Styles: " + Arrays.toString(BarStyle.values()));
                }
            }
            case "progress", "fill" -> {
                try {
                    set("progress", Math.max(0, Math.min(1, Double.parseDouble(rest))));
                    say(s, "&aBar filled to " + getConfig().getDouble("progress"));
                } catch (NumberFormatException e) {
                    say(s, "&7Usage: /bar progress <0 to 1>");
                }
            }
            case "ip" -> {
                set("ip", rest);
                say(s, rest.isEmpty() ? "&7{ip} comes from MC Hoster again." : "&a{ip} is now " + rest);
            }
            case "on", "off" -> {
                set("enabled", sub.equals("on"));
                say(s, "&7Boss bar " + sub + ".");
            }
            case "reload" -> {
                reloadConfig();
                restart();
                say(s, "&aReloaded.");
            }
            case "test" -> test(s);
            default -> {
                say(s, "&fNow: " + text(s instanceof Player p ? p : null) + " &7(" + colors() + (getConfig().getBoolean("flash", true) ? ", flashing" : "") + ")");
                say(s, "&f/bar text <text> &8| &f/bar addtext <text> &8| &f/bar color <c> [c...] &8| &f/bar flash on|off");
                say(s, "&f/bar speed <ticks> &8| &f/bar textcolor &d &5 ... &8| &f/bar style <style> &8| &f/bar progress <0-1> &8| &f/bar ip <address> &8| &f/bar on|off");
            }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        String backup = getConfig().saveToString();
        try {
            java.nio.file.Files.writeString(new File(getDataFolder(), "address.txt").toPath(), "play.test.net");
            ipRead = 0;
            if (!ip().equals("play.test.net")) fails.add("address.txt not read: " + ip());
            if (!org.bukkit.ChatColor.stripColor(text(null)).contains("play.test.net")) fails.add("{ip} not in text: " + text(null));
            CommandSender c = Bukkit.getConsoleSender();
            onCommand(c, getCommand("bar"), "bar", new String[]{"color", "red", "blue", "green"});
            int f1 = frame;
            BarColor a1 = color();
            frame++;
            BarColor a2 = color();
            if (a1 == a2) fails.add("colours don't flash");
            onCommand(c, getCommand("bar"), "bar", new String[]{"flash", "off"});
            if (color() != BarColor.RED) fails.add("flash off should stay red");
            onCommand(c, getCommand("bar"), "bar", new String[]{"text", "&aHello", "{online}"});
            if (!org.bukkit.ChatColor.stripColor(text(null)).startsWith("Hello")) fails.add("text " + text(null));
            frame = f1;
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            new File(getDataFolder(), "address.txt").delete();
            try {
                java.nio.file.Files.writeString(new File(getDataFolder(), "config.yml").toPath(), backup);
            } catch (java.io.IOException ignored) {
            }
            reloadConfig();
            ip = "";
            ipRead = 0;
        }
        String r = fails.isEmpty() ? "DARKBOSSBAR TEST OK" : "DARKBOSSBAR TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("text", "addtext", "color", "textcolor", "flash", "speed", "style", "progress", "ip", "on", "off", "reload"));
        else if (a[0].equalsIgnoreCase("color")) for (BarColor c : BarColor.values()) out.add(c.name().toLowerCase(Locale.ROOT));
        else if (a[0].equalsIgnoreCase("style")) for (BarStyle c : BarStyle.values()) out.add(c.name().toLowerCase(Locale.ROOT));
        else if (a[0].equalsIgnoreCase("flash")) out.addAll(List.of("on", "off"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.startsWith(last));
        return out;
    }
}
