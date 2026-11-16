package dev.darkerdawnfr.darkscoreboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Dark Scoreboard by DarkerDawnFR: the old DonutSMP sidebar (money, kills, deaths, playtime...) plus the tab list.
 * Everything is editable in game with /dsb edit, or in config.yml.
 */
public class DarkScoreboard extends JavaPlugin implements Listener, TabCompleter {
    private final Hooks hooks = new Hooks();
    private final Map<UUID, Board> boards = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, Consumer<String>> typing = new ConcurrentHashMap<>();
    private BukkitTask task;
    private File hiddenFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hiddenFile = new File(getDataFolder(), "hidden.yml");
        for (String s : YamlConfiguration.loadConfiguration(hiddenFile).getStringList("hidden")) {
            try {
                hidden.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("dsb");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        hooks.hook();
        Bukkit.getScheduler().runTask(this, () -> {
            hooks.hook(); // after every plugin has started
            getLogger().info("Dark Scoreboard ready. Connected to: " + hooks.connected());
        });
        restart();
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) clear(p);
        boards.clear();
    }

    private void restart() {
        if (task != null) task.cancel();
        int every = Math.max(5, getConfig().getInt("update-ticks", 20));
        task = Bukkit.getScheduler().runTaskTimer(this, this::updateAll, 20, every);
    }

    // ------------------------------------------------------------------ showing it

    public void updateAll() {
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    public void update(Player p) {
        boolean on = getConfig().getBoolean("scoreboard.enabled", true) && !hidden.contains(p.getUniqueId());
        if (on) {
            Board b = boards.computeIfAbsent(p.getUniqueId(), k -> new Board(getConfig().getBoolean("scoreboard.hide-numbers", true)));
            b.update(hooks.apply(p, p, getConfig().getString("scoreboard.title", "")), lines(p));
            if (p.getScoreboard() != b.scoreboard()) p.setScoreboard(b.scoreboard());
        } else if (boards.remove(p.getUniqueId()) != null) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        if (getConfig().getBoolean("tab.enabled", true)) {
            p.setPlayerListHeaderFooter(hooks.apply(p, p, getConfig().getString("tab.header", "")),
                    hooks.apply(p, p, getConfig().getString("tab.footer", "")));
            String fmt = getConfig().getString("tab.name-format", "");
            if (fmt != null && !fmt.isEmpty()) p.setPlayerListName(hooks.apply(p, p, fmt));
        }
    }

    private List<String> lines(Player p) {
        List<String> out = new ArrayList<>();
        boolean hide = getConfig().getBoolean("scoreboard.hide-missing", true);
        for (String l : getConfig().getStringList("scoreboard.lines"))
            if (!hide || hooks.available(l)) out.add(hooks.apply(p, p, l));
        return out;
    }

    private void clear(Player p) {
        if (boards.containsKey(p.getUniqueId())) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        p.setPlayerListHeaderFooter("", "");
        p.setPlayerListName(null);
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (e.getPlayer().isOnline()) update(e.getPlayer());
        }, 2);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        typing.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ editing

    private String prefix() {
        return Util.color("&8[&bScoreboard&8] ");
    }

    private void say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
    }

    private List<String> rawLines() {
        return new ArrayList<>(getConfig().getStringList("scoreboard.lines"));
    }

    public void setLines(List<String> lines) {
        getConfig().set("scoreboard.lines", lines);
        saveConfig();
        updateAll();
    }

    public void set(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
        updateAll();
    }

    /** "\n" typed in chat becomes a new line (for the tab header/footer). */
    private static String typed(String s) {
        return s.replace("\\n", "\n");
    }

    private void ask(Player p, String what, Consumer<String> then) {
        p.closeInventory();
        typing.put(p.getUniqueId(), then);
        say(p, "&7Type " + what + " in chat. &8(& colours, {placeholders}, or &fcancel&8)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncPlayerChatEvent e) {
        Consumer<String> c = typing.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String text = e.getMessage();
        Bukkit.getScheduler().runTask(this, () -> {
            if (text.trim().equalsIgnoreCase("cancel")) say(e.getPlayer(), "&7Nothing changed.");
            else c.accept(text);
            if (e.getPlayer().isOnline()) editor().open(e.getPlayer());
        });
    }

    public Menu editor() {
        Menu m = new Menu(6, "&8" + Util.small("Scoreboard editor"));
        String title = getConfig().getString("scoreboard.title", "");
        m.set(4, Util.item(Material.NAME_TAG, "&fTitle", "&7Now: " + title, "", "&eClick to change"),
                e -> ask((Player) e.getWhoClicked(), "the new title", t -> set("scoreboard.title", t)));
        List<String> lines = rawLines();
        for (int i = 0; i < lines.size() && i < Board.MAX_LINES; i++) {
            int idx = i;
            String raw = lines.get(i);
            m.set(9 + i, Util.item(Material.PAPER, 1, "&fLine " + (i + 1), List.of(
                    raw.isEmpty() ? "&8(empty)" : "&r" + raw, "&8" + raw.replace('&', '#'), "",
                    "&eLeft-click: change", "&eRight-click: move up", "&cShift-click: remove")), e -> {
                Player p = (Player) e.getWhoClicked();
                List<String> cur = rawLines();
                if (idx >= cur.size()) return;
                if (e.isShiftClick()) {
                    cur.remove(idx);
                    setLines(cur);
                    editor().open(p);
                } else if (e.getClick() == ClickType.RIGHT) {
                    if (idx > 0) {
                        cur.add(idx - 1, cur.remove(idx));
                        setLines(cur);
                    }
                    editor().open(p);
                } else {
                    ask(p, "line " + (idx + 1) + " (type &fempty&7 for a blank line)", t -> {
                        List<String> now = rawLines();
                        if (idx < now.size()) now.set(idx, t.equalsIgnoreCase("empty") ? "" : t);
                        setLines(now);
                    });
                }
            });
        }
        if (lines.size() < Board.MAX_LINES)
            m.set(45, Util.item(Material.LIME_DYE, "&aAdd a line", "&7Goes at the bottom", "&7(" + lines.size() + "/" + Board.MAX_LINES + " lines)"),
                    e -> ask((Player) e.getWhoClicked(), "the new line (&fempty&7 for a blank line)", t -> {
                        List<String> now = rawLines();
                        now.add(t.equalsIgnoreCase("empty") ? "" : t);
                        setLines(now);
                    }));
        boolean on = getConfig().getBoolean("scoreboard.enabled", true);
        m.set(47, Util.item(on ? Material.LIME_CONCRETE : Material.RED_CONCRETE, on ? "&aScoreboard: ON" : "&cScoreboard: OFF",
                "&7For everyone", "", "&eClick to switch"), e -> {
            set("scoreboard.enabled", !on);
            editor().open((Player) e.getWhoClicked());
        });
        m.set(48, Util.item(Material.PAINTING, "&fTab list", "&7Header, footer and names", "", "&eClick to edit"),
                e -> tabEditor().open((Player) e.getWhoClicked()));
        List<String> help = new ArrayList<>();
        for (Map.Entry<String, String> h : Hooks.HELP.entrySet()) help.add("&b{" + h.getKey() + "} &7" + h.getValue());
        help.add("&7PlaceholderAPI %placeholders% work too");
        help.add("&7Connected: &f" + String.join(", ", hooks.connected()));
        m.set(49, Util.item(Material.BOOK, 1, "&fPlaceholders", help));
        m.set(51, Util.item(Material.CLOCK, "&fUpdate speed", "&7Every &f" + getConfig().getInt("update-ticks", 20) / 20.0 + "s",
                "", "&eLeft: faster  &eRight: slower"), e -> {
            int t = getConfig().getInt("update-ticks", 20);
            t = e.getClick() == ClickType.RIGHT ? Math.min(200, t + 10) : Math.max(10, t - 10);
            set("update-ticks", t);
            restart();
            editor().open((Player) e.getWhoClicked());
        });
        m.set(53, Util.item(Material.TNT, "&cReset to default", "&7Back to the Test SMP scoreboard", "", "&cShift-click to reset"), e -> {
            if (!e.isShiftClick()) return;
            reset();
            editor().open((Player) e.getWhoClicked());
        });
        m.fill();
        return m;
    }

    public Menu tabEditor() {
        Menu m = new Menu(3, "&8" + Util.small("Tab list editor"));
        m.set(11, Util.item(Material.PAPER, 1, "&fHeader", lore("tab.header")),
                e -> ask((Player) e.getWhoClicked(), "the header (\\n for a new line)", t -> set("tab.header", typed(t))));
        m.set(13, Util.item(Material.NAME_TAG, "&fNames", "&7Now: " + getConfig().getString("tab.name-format", ""), "", "&eClick to change",
                        "&7e.g. &f&a{player} &7{ping}ms"),
                e -> ask((Player) e.getWhoClicked(), "how names look, e.g. &a{player}", t -> set("tab.name-format", t)));
        m.set(15, Util.item(Material.PAPER, 1, "&fFooter", lore("tab.footer")),
                e -> ask((Player) e.getWhoClicked(), "the footer (\\n for a new line)", t -> set("tab.footer", typed(t))));
        boolean on = getConfig().getBoolean("tab.enabled", true);
        m.set(18, Util.item(on ? Material.LIME_CONCRETE : Material.RED_CONCRETE, on ? "&aTab list: ON" : "&cTab list: OFF", "&eClick to switch"), e -> {
            set("tab.enabled", !on);
            if (on) for (Player p : Bukkit.getOnlinePlayers()) {
                p.setPlayerListHeaderFooter("", "");
                p.setPlayerListName(null);
            }
            tabEditor().open((Player) e.getWhoClicked());
        });
        m.set(22, Util.item(Material.ARROW, "&fBack"), e -> editor().open((Player) e.getWhoClicked()));
        m.fill();
        return m;
    }

    private List<String> lore(String path) {
        List<String> l = new ArrayList<>();
        for (String s : getConfig().getString(path, "").split("\n")) l.add("&r" + s);
        l.add("");
        l.add("&eClick to change");
        return l;
    }

    private void reset() {
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                getResource("config.yml"), java.nio.charset.StandardCharsets.UTF_8));
        for (String k : List.of("scoreboard", "tab", "update-ticks")) getConfig().set(k, def.get(k));
        saveConfig();
        restart();
        updateAll();
    }

    private void saveHidden() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (UUID u : hidden) l.add(u.toString());
        y.set("hidden", l);
        try {
            getDataFolder().mkdirs();
            y.save(hiddenFile);
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        String rest = a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : "";
        boolean admin = s.hasPermission("darkscoreboard.edit");
        if (sub.equals("toggle") || sub.isEmpty() && !admin) {
            if (!(s instanceof Player p)) return true;
            if (!hidden.remove(p.getUniqueId())) hidden.add(p.getUniqueId());
            saveHidden();
            update(p);
            say(p, hidden.contains(p.getUniqueId()) ? "&7Scoreboard hidden. &f/dsb toggle &7to show it." : "&aScoreboard shown.");
            return true;
        }
        if (!admin) {
            say(s, "&7Use &f/dsb toggle &7to hide or show your scoreboard.");
            return true;
        }
        switch (sub) {
            case "", "edit" -> {
                if (s instanceof Player p) editor().open(p);
                else help(s);
            }
            case "title" -> {
                set("scoreboard.title", rest);
                say(s, "&aTitle is now " + rest);
            }
            case "add" -> {
                List<String> l = rawLines();
                if (l.size() >= Board.MAX_LINES) {
                    say(s, "&cThe scoreboard can show " + Board.MAX_LINES + " lines at most.");
                    return true;
                }
                l.add(rest);
                setLines(l);
                say(s, "&aAdded line " + l.size() + ".");
            }
            case "set", "remove" -> {
                List<String> l = rawLines();
                int n;
                try {
                    n = Integer.parseInt(a.length > 1 ? a[1] : "") - 1;
                } catch (NumberFormatException e) {
                    n = -1;
                }
                if (n < 0 || n >= l.size()) {
                    say(s, "&cLine numbers go from 1 to " + l.size() + ".");
                    return true;
                }
                if (sub.equals("set")) l.set(n, a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : "");
                else l.remove(n);
                setLines(l);
                say(s, "&aLine " + (n + 1) + (sub.equals("set") ? " changed." : " removed."));
            }
            case "header", "footer" -> {
                set("tab." + sub, typed(rest));
                say(s, "&aTab " + sub + " changed.");
            }
            case "name" -> {
                set("tab.name-format", rest);
                say(s, "&aTab names changed.");
            }
            case "on", "off" -> {
                set("scoreboard.enabled", sub.equals("on"));
                say(s, "&aScoreboard is " + sub + " for everyone.");
            }
            case "reset" -> {
                reset();
                say(s, "&aBack to the default Test SMP scoreboard.");
            }
            case "reload" -> {
                reloadConfig();
                hooks.hook();
                restart();
                updateAll();
                say(s, "&aReloaded. Connected to: " + hooks.connected());
            }
            case "placeholders" -> {
                for (Map.Entry<String, String> h : Hooks.HELP.entrySet()) s.sendMessage(Util.color("&b{" + h.getKey() + "} &7" + h.getValue()));
            }
            case "test" -> test(s);
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        say(s, "&fDark Scoreboard " + getDescription().getVersion() + " by DarkerDawnFR");
        for (String l : List.of("/dsb edit &7- menu to change everything", "/dsb title <text>", "/dsb add <text>",
                "/dsb set <line> <text>", "/dsb remove <line>", "/dsb header|footer <text> &7(\\n = new line)",
                "/dsb name <format>", "/dsb on|off", "/dsb toggle &7- hide your own", "/dsb placeholders", "/dsb reset", "/dsb reload"))
            s.sendMessage(Util.color("&f" + l));
    }

    /** Checks placeholders, the other plugins, the sidebar and editing. Used by MC Hoster's server test. */
    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        String backup = getConfig().saveToString();
        try {
            hooks.hook();
            OfflinePlayer fake = Bukkit.getOfflinePlayer(UUID.nameUUIDFromBytes("darkscoreboard-test".getBytes()));
            String title = ChatColor.stripColor(hooks.apply(fake, null, getConfig().getString("scoreboard.title", "")));
            if (!title.equals("Test SMP")) fails.add("title is " + title);
            if (!ChatColor.stripColor(hooks.apply(fake, null, "{online}/{max} {kills} {deaths} {playtime}")).matches("\\d+/\\d+ 0 0 0m"))
                fails.add("stats: " + hooks.apply(fake, null, "{online}/{max} {kills} {deaths} {playtime}"));
            if (Bukkit.getPluginManager().getPlugin("DarkShop") != null) {
                Object shop = Bukkit.getPluginManager().getPlugin("DarkShop");
                Hooks.call(shop, "give", fake.getUniqueId(), 1500.0);
                String money = ChatColor.stripColor(hooks.apply(fake, null, "{money} {bounty}"));
                boolean builtIn = String.valueOf(Hooks.call(shop, "economyMode")).startsWith("built-in");
                if (builtIn && !money.equals("$1.5K $0")) fails.add("money from Dark Shop: " + money);
                Hooks.call(shop, "take", fake.getUniqueId(), 1500.0);
            }
            if (Bukkit.getPluginManager().getPlugin("DarkRTP") != null && !hooks.apply(fake, null, "{rtp_queue}").equals("0")) fails.add("rtp queue");
            if (Bukkit.getPluginManager().getPlugin("DarkAH") != null && !hooks.apply(fake, null, "{listings}").equals("0")) fails.add("ah listings");
            if (Bukkit.getPluginManager().getPlugin("DarkDiscord") != null && !hooks.apply(fake, null, "{discord}").startsWith("https://"))
                fails.add("discord link");
            List<String> lines = new ArrayList<>();
            for (String l : getConfig().getStringList("scoreboard.lines")) lines.add(hooks.apply(fake, null, l));
            Board b = new Board(true);
            b.update(title, lines);
            if (b.lines() != lines.size()) fails.add("sidebar shows " + b.lines() + " of " + lines.size() + " lines");
            b.update(title, lines.subList(0, 3));
            if (b.lines() != 3) fails.add("sidebar didn't shrink: " + b.lines());
            // editing
            int before = rawLines().size();
            onCommand(Bukkit.getConsoleSender(), getCommand("dsb"), "dsb", new String[]{"add", "&fHello", "{player}"});
            onCommand(Bukkit.getConsoleSender(), getCommand("dsb"), "dsb", new String[]{"set", "1", "&cFirst"});
            onCommand(Bukkit.getConsoleSender(), getCommand("dsb"), "dsb", new String[]{"title", "&aMy", "SMP"});
            onCommand(Bukkit.getConsoleSender(), getCommand("dsb"), "dsb", new String[]{"header", "A\\nB"});
            reloadConfig();
            List<String> now = rawLines();
            if (now.size() != before + 1 || !now.get(now.size() - 1).equals("&fHello {player}") || !now.get(0).equals("&cFirst"))
                fails.add("editing lines: " + now);
            if (!getConfig().getString("scoreboard.title", "").equals("&aMy SMP")) fails.add("editing title");
            if (!getConfig().getString("tab.header", "").equals("A\nB")) fails.add("editing header");
            onCommand(Bukkit.getConsoleSender(), getCommand("dsb"), "dsb", new String[]{"remove", String.valueOf(now.size())});
            if (rawLines().size() != before) fails.add("removing a line");
            editor();
            tabEditor();
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            try {
                java.nio.file.Files.writeString(new File(getDataFolder(), "config.yml").toPath(), backup);
            } catch (IOException ignored) {
            }
            reloadConfig();
            updateAll();
        }
        String result = fails.isEmpty() ? "DARKSCOREBOARD TEST OK (connected: " + hooks.connected() + ")" : "DARKSCOREBOARD TEST FAIL: " + fails;
        getLogger().info(result);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(result);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("toggle");
            if (s.hasPermission("darkscoreboard.edit"))
                out.addAll(List.of("edit", "title", "add", "set", "remove", "header", "footer", "name", "on", "off", "placeholders", "reset", "reload"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.startsWith(last));
        return out;
    }
}
