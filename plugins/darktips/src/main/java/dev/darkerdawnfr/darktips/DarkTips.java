package dev.darkerdawnfr.darktips;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dark Tips by DarkerDawnFR: a tip in chat every few minutes, and an announcement when someone logs in.
 * Everything is changed with /tips (ops).
 */
public class DarkTips extends JavaPlugin implements Listener, TabCompleter {
    private BukkitTask task;
    private int next;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("tips");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        restart();
    }

    private void restart() {
        if (task != null) task.cancel();
        long every = Math.max(1, getConfig().getLong("interval-minutes", 5)) * 60 * 20;
        task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (getConfig().getBoolean("enabled", true) && !Bukkit.getOnlinePlayers().isEmpty()) sendTip();
        }, every, every);
    }

    private List<String> tips() {
        return new ArrayList<>(getConfig().getStringList("tips"));
    }

    /** Broadcasts the next tip and returns it (null if there are none). */
    public String sendTip() {
        List<String> t = tips();
        if (t.isEmpty()) return null;
        String tip = getConfig().getBoolean("random-order", false) ? t.get(ThreadLocalRandom.current().nextInt(t.size())) : t.get(next++ % t.size());
        String line = Util.color(getConfig().getString("prefix", "&b&lTIP &8» &7") + tip);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Settings.on(p.getUniqueId(), "tips")) continue;
            p.sendMessage(line);
            if (getConfig().getBoolean("sound", true)) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.4f);
        }
        return line;
    }

    // ------------------------------------------------------------------ login announcement

    @EventHandler
    public void join(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("login.enabled", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            for (String l : getConfig().getStringList("login.lines")) p.sendMessage(Util.color(l.replace("{player}", p.getName())
                    .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))));
            String title = getConfig().getString("login.title", "");
            if (title != null && !title.isEmpty())
                p.sendTitle(Util.color(title.replace("{player}", p.getName())), Util.color(getConfig().getString("login.subtitle", "").replace("{player}", p.getName())), 10, 70, 20);
            if (getConfig().getBoolean("sound", true)) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        }, Math.max(0, getConfig().getLong("login.delay-seconds", 2)) * 20);
    }

    // ------------------------------------------------------------------ commands

    private boolean say(CommandSender s, String text) {
        s.sendMessage(Util.color("&8[&bTips&8] " + text));
        return true;
    }

    private void set(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        String rest = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "";
        if (!s.hasPermission("darktips.admin")) {
            List<String> t = tips();
            if (!t.isEmpty()) s.sendMessage(Util.color(getConfig().getString("prefix", "") + t.get(ThreadLocalRandom.current().nextInt(t.size()))));
            return true;
        }
        switch (sub) {
            case "add" -> {
                if (rest.isEmpty()) return say(s, "&7Usage: /tips add <tip>");
                List<String> t = tips();
                t.add(rest);
                set("tips", t);
                say(s, "&aAdded tip " + t.size() + ".");
            }
            case "remove", "delete" -> {
                List<String> t = tips();
                int n = num(a.length > 1 ? a[1] : "") - 1;
                if (n < 0 || n >= t.size()) return say(s, "&cTip numbers go from 1 to " + t.size() + ".");
                t.remove(n);
                set("tips", t);
                say(s, "&7Removed tip " + (n + 1) + ".");
            }
            case "set", "edit" -> {
                List<String> t = tips();
                int n = num(a.length > 1 ? a[1] : "") - 1;
                if (n < 0 || n >= t.size() || a.length < 3) return say(s, "&7Usage: /tips set <number> <tip>");
                t.set(n, String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                set("tips", t);
                say(s, "&aTip " + (n + 1) + " changed.");
            }
            case "list" -> {
                List<String> t = tips();
                say(s, "&fTips every &b" + getConfig().getLong("interval-minutes", 5) + " min &7(" + (getConfig().getBoolean("enabled", true) ? "&aon" : "&coff") + "&7):");
                for (int i = 0; i < t.size(); i++) s.sendMessage(Util.color("&8" + (i + 1) + ". &7" + t.get(i)));
            }
            case "interval", "every" -> {
                int n = num(a.length > 1 ? a[1] : "");
                if (n < 1) return say(s, "&7Usage: /tips interval <minutes>");
                set("interval-minutes", n);
                restart();
                say(s, "&aA tip every " + n + " minutes.");
            }
            case "prefix" -> {
                set("prefix", rest + " ");
                say(s, "&aPrefix is now " + rest);
            }
            case "on", "off" -> {
                set("enabled", sub.equals("on"));
                say(s, "&7Tips are " + sub + ".");
            }
            case "random" -> {
                set("random-order", !getConfig().getBoolean("random-order", false));
                say(s, "&7Random order: " + getConfig().getBoolean("random-order"));
            }
            case "send", "now" -> {
                if (sendTip() == null) say(s, "&cThere are no tips. /tips add <tip>");
            }
            case "login" -> login(s, a);
            case "reload" -> {
                reloadConfig();
                restart();
                say(s, "&aReloaded.");
            }
            case "test" -> test(s);
            default -> {
                say(s, "&f/tips list &8| &f/tips add <tip> &8| &f/tips set <n> <tip> &8| &f/tips remove <n>");
                say(s, "&f/tips interval <minutes> &8| &f/tips on|off &8| &f/tips send &8| &f/tips prefix <text> &8| &f/tips random");
                say(s, "&f/tips login show|set <text>|add <text>|clear|title <text>|subtitle <text>|on|off");
            }
        }
        return true;
    }

    private void login(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "show";
        String rest = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "";
        List<String> lines = new ArrayList<>(getConfig().getStringList("login.lines"));
        switch (sub) {
            case "set" -> {
                set("login.lines", Arrays.asList(rest.split("\\\\n")));
                say(s, "&aLogin announcement set. &8(\\n makes a new line)");
            }
            case "add" -> {
                lines.add(rest);
                set("login.lines", lines);
                say(s, "&aLine added.");
            }
            case "clear" -> {
                set("login.lines", new ArrayList<String>());
                say(s, "&7Login announcement cleared.");
            }
            case "title", "subtitle" -> {
                set("login." + sub, rest);
                say(s, "&aLogin " + sub + " set.");
            }
            case "on", "off" -> {
                set("login.enabled", sub.equals("on"));
                say(s, "&7Login announcement is " + sub + ".");
            }
            default -> {
                say(s, "&fLogin announcement (" + (getConfig().getBoolean("login.enabled", true) ? "&aon" : "&coff") + "&f):");
                for (String l : lines) s.sendMessage(Util.color(l.replace("{player}", s.getName())));
            }
        }
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        String backup = getConfig().saveToString();
        try {
            int before = tips().size();
            if (before < 3) fails.add("default tips");
            CommandSender c = Bukkit.getConsoleSender();
            onCommand(c, getCommand("tips"), "tips", new String[]{"add", "Test", "tip"});
            onCommand(c, getCommand("tips"), "tips", new String[]{"interval", "7"});
            onCommand(c, getCommand("tips"), "tips", new String[]{"login", "set", "Hi {player}\\nLine two"});
            reloadConfig();
            if (tips().size() != before + 1 || !tips().get(before).equals("Test tip")) fails.add("add tip");
            if (getConfig().getLong("interval-minutes") != 7) fails.add("interval");
            if (!getConfig().getStringList("login.lines").equals(List.of("Hi {player}", "Line two"))) fails.add("login " + getConfig().getStringList("login.lines"));
            String sent = sendTip();
            if (sent == null) fails.add("no tip sent");
            onCommand(c, getCommand("tips"), "tips", new String[]{"remove", String.valueOf(before + 1)});
            if (tips().size() != before) fails.add("remove tip");
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            try {
                java.nio.file.Files.writeString(new java.io.File(getDataFolder(), "config.yml").toPath(), backup);
            } catch (java.io.IOException ignored) {
            }
            reloadConfig();
            restart();
        }
        String r = fails.isEmpty() ? "DARKTIPS TEST OK" : "DARKTIPS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("darktips.admin")) return out;
        if (a.length == 1) out.addAll(List.of("list", "add", "set", "remove", "interval", "on", "off", "send", "prefix", "random", "login", "reload"));
        if (a.length == 2 && a[0].equalsIgnoreCase("login")) out.addAll(List.of("show", "set", "add", "clear", "title", "subtitle", "on", "off"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.startsWith(last));
        return out;
    }
}
