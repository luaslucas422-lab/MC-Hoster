package dev.darkerdawnfr.darkchat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dark Chat by DarkerDawnFR: a better chat.
 * Format with rank prefixes (LuckPerms through Vault), @mentions with a ping, [item] to show what you hold,
 * anti-spam / anti-caps / word filter, /msg and /r, /ignore, /mute, /mutechat, /clearchat, /socialspy.
 */
public class DarkChat extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, Long> lastMsgTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMsg = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> replyTo = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignores = new ConcurrentHashMap<>();
    private final Map<UUID, Long> muted = new ConcurrentHashMap<>();
    private final Set<UUID> spies = ConcurrentHashMap.newKeySet();
    private volatile boolean chatMuted;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String k : y.getConfigurationSection("ignores") == null ? Set.<String>of() : y.getConfigurationSection("ignores").getKeys(false)) {
            Set<UUID> set = ConcurrentHashMap.newKeySet();
            for (String s : y.getStringList("ignores." + k)) set.add(UUID.fromString(s));
            ignores.put(UUID.fromString(k), set);
        }
        for (String k : y.getConfigurationSection("muted") == null ? Set.<String>of() : y.getConfigurationSection("muted").getKeys(false))
            muted.put(UUID.fromString(k), y.getLong("muted." + k));
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("msg", "reply", "ignore", "mute", "unmute", "mutechat", "clearchat", "socialspy", "darkchat")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Set<UUID>> e : ignores.entrySet()) {
            List<String> l = new ArrayList<>();
            for (UUID u : e.getValue()) l.add(u.toString());
            if (!l.isEmpty()) y.set("ignores." + e.getKey(), l);
        }
        for (Map.Entry<UUID, Long> e : muted.entrySet()) y.set("muted." + e.getKey(), e.getValue());
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ignored) {
        }
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&bChat&8] "));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
        return true;
    }

    // ------------------------------------------------------------------ rank prefixes (LuckPerms etc. through Vault)

    public String rankPrefix(Player p) {
        try {
            Class<?> chat = Class.forName("net.milkbowl.vault.chat.Chat");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(chat);
            if (rsp == null) return "";
            Object r = chat.getMethod("getPlayerPrefix", Player.class).invoke(rsp.getProvider(), p);
            return r == null ? "" : String.valueOf(r);
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ cleaning a message

    /** What's wrong with a message (null = fine). Also used by the test. */
    public String blocked(UUID who, String msg, long now) {
        Long until = muted.get(who);
        if (until != null && (until == 0 || until > now)) return "&cYou are muted" + (until == 0 ? "." : " for " + time(until - now) + ".");
        if (until != null) muted.remove(who);
        long delay = getConfig().getLong("anti-spam.delay-ms", 1000);
        Long last = lastMsgTime.get(who);
        if (last != null && now - last < delay) return "&cSlow down a bit.";
        String prev = lastMsg.get(who);
        if (getConfig().getBoolean("anti-spam.no-repeats", true) && prev != null && prev.equalsIgnoreCase(msg)
                && last != null && now - last < getConfig().getLong("anti-spam.repeat-seconds", 10) * 1000)
            return "&cDon't send the same message twice.";
        if (getConfig().getBoolean("block-links", false) && Pattern.compile("(?i)(https?://|www\\.|\\w+\\.(com|net|org|gg|io|me)\\b)").matcher(msg).find())
            return "&cLinks aren't allowed in chat.";
        return null;
    }

    public String clean(String msg) {
        int letters = 0, caps = 0;
        for (char c : msg.toCharArray())
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) caps++;
            }
        if (getConfig().getBoolean("anti-caps", true) && letters >= 6 && caps * 100 / letters > 70) msg = msg.toLowerCase(Locale.ROOT);
        for (String bad : getConfig().getStringList("filter")) {
            if (bad.isBlank()) continue;
            msg = msg.replaceAll("(?i)" + Pattern.quote(bad), "*".repeat(bad.length()));
        }
        return msg;
    }

    /** Online players mentioned by name (with or without @). */
    public List<Player> mentions(String msg, Player from) {
        List<Player> out = new ArrayList<>();
        String low = msg.toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(from)) continue;
            Matcher m = Pattern.compile("(?i)(?<![A-Za-z0-9_])@?" + Pattern.quote(p.getName()) + "(?![A-Za-z0-9_])").matcher(low);
            if (m.find()) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ chat

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void chat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        if (chatMuted && !p.hasPermission("darkchat.admin")) {
            e.setCancelled(true);
            say(p, "&cChat is muted right now.");
            return;
        }
        String problem = p.hasPermission("darkchat.bypass") ? null : blocked(p.getUniqueId(), e.getMessage(), now);
        if (problem != null) {
            e.setCancelled(true);
            say(p, problem);
            return;
        }
        lastMsgTime.put(p.getUniqueId(), now);
        lastMsg.put(p.getUniqueId(), e.getMessage());
        String msg = p.hasPermission("darkchat.bypass") ? e.getMessage() : clean(e.getMessage());
        if (p.hasPermission("darkchat.color")) msg = Util.color(msg);
        // ignored players don't see it
        e.getRecipients().removeIf(r -> ignores.getOrDefault(r.getUniqueId(), Set.of()).contains(p.getUniqueId()));
        List<Player> pinged = getConfig().getBoolean("mentions.enabled", true) ? mentions(msg, p) : List.of();
        for (Player m : pinged) {
            msg = msg.replaceAll("(?i)(?<![A-Za-z0-9_])@?" + Pattern.quote(m.getName()) + "(?![A-Za-z0-9_])",
                    Matcher.quoteReplacement(Util.color(getConfig().getString("mentions.color", "&e&l")) + "@" + m.getName() + ChatColor.RESET));
            Bukkit.getScheduler().runTask(this, () -> {
                m.playSound(m.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
                m.sendTitle("", Util.color("&e" + p.getName() + " &7mentioned you"), 0, 30, 10);
            });
        }
        String fmt = Util.color(getConfig().getString("format", "{rank}&f{player} &8» &7{message}")
                .replace("{rank}", rankPrefix(p))).replace("%", "%%");
        fmt = fmt.replace("{player}", "%1$s").replace("{message}", "%2$s").replace("{world}", p.getWorld().getName());
        String hold = "[item]", hold2 = "[i]";
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (getConfig().getBoolean("item-in-chat", true) && (msg.toLowerCase(Locale.ROOT).contains(hold) || msg.toLowerCase(Locale.ROOT).contains(hold2))
                && hand.getType() != Material.AIR) {
            // [item]: we send it ourselves so the item can be hovered
            e.setCancelled(true);
            String line = String.format(fmt, p.getDisplayName(), "\u0000");
            String[] parts = line.split("\u0000", -1);
            String before = parts[0], text = msg, after = parts.length > 1 ? parts[1] : "";
            String label = Util.color("&b[" + (hand.getAmount() > 1 ? hand.getAmount() + "x " : "") + itemName(hand) + "&b]") + ChatColor.RESET;
            String[] split = text.split("(?i)\\[item\\]|\\[i\\]", 2);
            Set<Player> to = new HashSet<>(e.getRecipients());
            for (Player r : to) Hover.send(r, before + split[0], label, hand, (split.length > 1 ? split[1] : "") + after);
            Bukkit.getConsoleSender().sendMessage(before + split[0] + label + (split.length > 1 ? split[1] : "") + after);
            return;
        }
        e.setFormat(fmt);
        e.setMessage(msg);
    }

    private static String itemName(ItemStack it) {
        if (it.hasItemMeta() && it.getItemMeta() != null && it.getItemMeta().hasDisplayName()) return it.getItemMeta().getDisplayName();
        return Util.nice(it.getType());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        lastMsg.remove(e.getPlayer().getUniqueId());
        lastMsgTime.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    private static String time(long ms) {
        long m = ms / 60000, h = m / 60;
        return h > 0 ? h + "h " + (m % 60) + "m" : Math.max(1, m) + "m";
    }

    private void whisper(CommandSender from, Player to, String text) {
        UUID fromId = from instanceof Player fp ? fp.getUniqueId() : null;
        if (fromId != null && ignores.getOrDefault(to.getUniqueId(), Set.of()).contains(fromId)) {
            say(from, "&c" + to.getName() + " is ignoring you.");
            return;
        }
        if (fromId != null && !from.hasPermission("darkchat.bypass")) {
            String problem = blocked(fromId, "/msg " + text, System.currentTimeMillis());
            if (problem != null && problem.contains("muted")) {
                say(from, problem);
                return;
            }
            text = clean(text);
        }
        from.sendMessage(Util.color(getConfig().getString("msg.to", "&d[me → {player}] &f").replace("{player}", to.getName())) + text);
        to.sendMessage(Util.color(getConfig().getString("msg.from", "&d[{player} → me] &f").replace("{player}", from.getName())) + text);
        to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.5f);
        if (fromId != null) {
            replyTo.put(fromId, to.getUniqueId());
            replyTo.put(to.getUniqueId(), fromId);
        }
        for (UUID s : spies) {
            Player sp = Bukkit.getPlayer(s);
            if (sp != null && !sp.equals(from) && !sp.equals(to))
                sp.sendMessage(Util.color("&8[spy] &7" + from.getName() + " → " + to.getName() + ": &f") + text);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "msg" -> {
                if (a.length < 2) return say(s, "&7Usage: /msg <player> <message>");
                Player t = Bukkit.getPlayer(a[0]);
                if (t == null) return say(s, "&c" + a[0] + " isn't online.");
                whisper(s, t, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
            }
            case "reply" -> {
                if (!(s instanceof Player p) || a.length < 1) return say(s, "&7Usage: /r <message>");
                UUID to = replyTo.get(p.getUniqueId());
                Player t = to == null ? null : Bukkit.getPlayer(to);
                if (t == null) return say(s, "&7Nobody to reply to.");
                whisper(s, t, String.join(" ", a));
            }
            case "ignore" -> {
                if (!(s instanceof Player p) || a.length < 1) return say(s, "&7Usage: /ignore <player>");
                Player t = Bukkit.getPlayer(a[0]);
                UUID id = t != null ? t.getUniqueId() : Bukkit.getOfflinePlayer(a[0]).getUniqueId();
                Set<UUID> set = ignores.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
                if (set.remove(id)) say(s, "&7You see " + a[0] + "'s messages again.");
                else {
                    set.add(id);
                    say(s, "&7You won't see " + a[0] + "'s messages. &8(/ignore " + a[0] + " again to undo)");
                }
                saveData();
            }
            case "mute" -> {
                if (a.length < 1) return say(s, "&7Usage: /mute <player> [minutes]");
                Player t = Bukkit.getPlayer(a[0]);
                UUID id = t != null ? t.getUniqueId() : Bukkit.getOfflinePlayer(a[0]).getUniqueId();
                long mins = 0;
                try {
                    if (a.length > 1) mins = Long.parseLong(a[1]);
                } catch (NumberFormatException ignored) {
                }
                muted.put(id, mins <= 0 ? 0 : System.currentTimeMillis() + mins * 60_000);
                saveData();
                say(s, "&7Muted " + a[0] + (mins > 0 ? " for " + mins + " minutes." : "."));
                if (t != null) say(t, "&cYou were muted" + (mins > 0 ? " for " + mins + " minutes." : "."));
            }
            case "unmute" -> {
                if (a.length < 1) return say(s, "&7Usage: /unmute <player>");
                Player t = Bukkit.getPlayer(a[0]);
                UUID id = t != null ? t.getUniqueId() : Bukkit.getOfflinePlayer(a[0]).getUniqueId();
                muted.remove(id);
                saveData();
                say(s, "&7Unmuted " + a[0] + ".");
            }
            case "mutechat" -> {
                chatMuted = !chatMuted;
                Bukkit.broadcastMessage(prefix() + Util.color(chatMuted ? "&cChat has been muted." : "&aChat is open again."));
            }
            case "clearchat" -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("darkchat.admin")) continue;
                    for (int i = 0; i < 100; i++) p.sendMessage("");
                }
                Bukkit.broadcastMessage(prefix() + Util.color("&7Chat was cleared by " + s.getName() + "."));
            }
            case "socialspy" -> {
                if (!(s instanceof Player p)) return true;
                if (spies.remove(p.getUniqueId())) say(s, "&7Social spy off.");
                else {
                    spies.add(p.getUniqueId());
                    say(s, "&7Social spy on: you see every /msg.");
                }
            }
            case "darkchat" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("test")) test(s);
                else if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    say(s, "&aReloaded.");
                } else say(s, "&7Dark Chat " + getDescription().getVersion() + " by DarkerDawnFR. /darkchat reload|test");
            }
            default -> { return false; }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID id = UUID.randomUUID();
        long t = 1_000_000;
        try {
            if (blocked(id, "hello", t) != null) fails.add("first message blocked");
            lastMsgTime.put(id, t);
            lastMsg.put(id, "hello");
            if (blocked(id, "again", t + 200) == null) fails.add("spam not slowed");
            if (blocked(id, "hello", t + 3000) == null) fails.add("repeat allowed");
            if (blocked(id, "something new", t + 3000) != null) fails.add("normal message blocked");
            if (!clean("HELLO EVERYONE HI").equals("hello everyone hi")) fails.add("caps: " + clean("HELLO EVERYONE HI"));
            if (!clean("Hi there").equals("Hi there")) fails.add("normal caps changed");
            List<String> filter = getConfig().getStringList("filter");
            getConfig().set("filter", List.of("badword"));
            if (!clean("you BadWord").equals("you *******")) fails.add("filter: " + clean("you BadWord"));
            getConfig().set("filter", filter);
            muted.put(id, 0L);
            if (blocked(id, "x", t + 9000) == null) fails.add("muted can chat");
            muted.put(id, t - 1);
            if (blocked(id, "y y y", t + 9000) != null) fails.add("mute didn't run out");
            String fmt = getConfig().getString("format", "");
            if (!fmt.contains("{player}") || !fmt.contains("{message}")) fails.add("format");
        } catch (Throwable ex) {
            fails.add(ex.toString());
        } finally {
            lastMsg.remove(id);
            lastMsgTime.remove(id);
            muted.remove(id);
        }
        String r = fails.isEmpty() ? "DARKCHAT TEST OK" : "DARKCHAT TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (a.length == 1 && List.of("msg", "ignore", "mute", "unmute").contains(n))
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
