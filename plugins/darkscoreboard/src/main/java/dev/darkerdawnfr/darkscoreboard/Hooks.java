package dev.darkerdawnfr.darkscoreboard;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fills in {placeholders} using the other Dark plugins (money, bounty, baltop rank, auction listings,
 * RTP queue, Discord link), Vault, plain Minecraft stats, and PlaceholderAPI (%...%) when it's installed.
 * Everything is found by reflection, so none of them is needed.
 */
public class Hooks {
    /** {name} -> what it shows, for the editor's help book. */
    public static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put("player", "your name");
        HELP.put("displayname", "your nickname");
        HELP.put("money", "your money, like $1.5K");
        HELP.put("money_raw", "your money, like 1500.00");
        HELP.put("baltop", "your /baltop rank");
        HELP.put("bounty", "the bounty on you");
        HELP.put("kills", "players you killed");
        HELP.put("deaths", "times you died");
        HELP.put("kdr", "kills / deaths");
        HELP.put("mobkills", "mobs you killed");
        HELP.put("playtime", "time played, like 3h 12m");
        HELP.put("ping", "your ping in ms");
        HELP.put("online", "players online");
        HELP.put("max", "max players");
        HELP.put("world", "overworld / nether / end");
        HELP.put("x", "your X (also {y} {z})");
        HELP.put("health", "your health");
        HELP.put("level", "your XP level");
        HELP.put("listings", "your /ah listings");
        HELP.put("ah_total", "all /ah listings");
        HELP.put("rtp_queue", "players in /rtpq");
        HELP.put("discord", "the /discord link");
        HELP.put("date", "today's date");
        HELP.put("time", "the time now");
    }

    private Plugin shop, ah, rtp, discord, papi;
    private Object vault;

    public void hook() {
        shop = enabled("DarkShop");
        ah = enabled("DarkAH");
        rtp = enabled("DarkRTP");
        discord = enabled("DarkDiscord");
        papi = enabled("PlaceholderAPI");
        vault = null;
        if (enabled("Vault") != null || enabled("VaultUnlocked") != null) {
            try {
                Class<?> econ = Class.forName("net.milkbowl.vault.economy.Economy");
                RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(econ);
                vault = rsp == null ? null : rsp.getProvider();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Plugin enabled(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled() ? p : null;
    }

    public List<String> connected() {
        List<String> out = new ArrayList<>();
        if (shop != null) out.add("Dark Shop");
        if (ah != null) out.add("Dark AH");
        if (rtp != null) out.add("Dark RTP");
        if (discord != null) out.add("Dark Discord");
        if (vault != null) out.add("Vault");
        if (papi != null) out.add("PlaceholderAPI");
        return out;
    }

    // ------------------------------------------------------------------ values

    public double money(OfflinePlayer p) {
        Object r = shop != null ? call(shop, "balance", p.getUniqueId()) : vault != null ? call(vault, "getBalance", p) : null;
        return r instanceof Number n ? n.doubleValue() : 0;
    }

    private String num(Plugin pl, String method, Object... args) {
        if (pl == null) return "0";
        Object r = call(pl, method, args);
        return r instanceof Number n ? String.valueOf(n.longValue()) : "0";
    }

    /** Replaces every {placeholder} (and %placeholder% with PlaceholderAPI). online may be null (offline / tests). */
    public String apply(OfflinePlayer p, Player online, String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf('{') >= 0) {
            UUID id = p.getUniqueId();
            text = rep(text, "player", p.getName() == null ? "?" : p.getName());
            if (text.contains("{displayname}")) text = rep(text, "displayname", online != null ? online.getDisplayName() : String.valueOf(p.getName()));
            if (text.contains("{money")) {
                double m = money(p);
                text = rep(rep(text, "money", Util.money(m)), "money_raw", new DecimalFormat("0.00").format(m));
            }
            if (text.contains("{bounty}")) {
                Object b = shop != null ? call(shop, "bounty", id) : null;
                text = rep(text, "bounty", Util.money(b instanceof Number n ? n.doubleValue() : 0));
            }
            if (text.contains("{baltop}")) {
                String r = num(shop, "baltopRank", id);
                text = rep(text, "baltop", r.equals("0") ? "-" : "#" + r);
            }
            if (text.contains("{kills}") || text.contains("{deaths}") || text.contains("{kdr}") || text.contains("{mobkills}") || text.contains("{playtime}")) {
                int kills = stat(p, Statistic.PLAYER_KILLS), deaths = stat(p, Statistic.DEATHS);
                text = rep(text, "kills", String.valueOf(kills));
                text = rep(text, "deaths", String.valueOf(deaths));
                text = rep(text, "kdr", new DecimalFormat("0.##").format(deaths == 0 ? kills : (double) kills / deaths));
                text = rep(text, "mobkills", String.valueOf(stat(p, Statistic.MOB_KILLS)));
                text = rep(text, "playtime", time(stat(p, Statistic.PLAY_ONE_MINUTE) / 20L));
            }
            if (online != null) {
                text = rep(text, "ping", String.valueOf(online.getPing()));
                text = rep(text, "world", switch (online.getWorld().getEnvironment()) {
                    case NETHER -> "Nether";
                    case THE_END -> "End";
                    default -> "Overworld";
                });
                text = rep(text, "x", String.valueOf(online.getLocation().getBlockX()));
                text = rep(text, "y", String.valueOf(online.getLocation().getBlockY()));
                text = rep(text, "z", String.valueOf(online.getLocation().getBlockZ()));
                text = rep(text, "health", String.valueOf((int) Math.ceil(online.getHealth())));
                text = rep(text, "level", String.valueOf(online.getLevel()));
            } else {
                for (String k : List.of("ping", "world", "x", "y", "z", "health", "level")) text = rep(text, k, "-");
            }
            text = rep(text, "online", String.valueOf(Bukkit.getOnlinePlayers().size()));
            text = rep(text, "max", String.valueOf(Bukkit.getMaxPlayers()));
            if (text.contains("{listings}")) text = rep(text, "listings", num(ah, "listingCount", id));
            if (text.contains("{ah_total}")) text = rep(text, "ah_total", num(ah, "listingTotal"));
            if (text.contains("{rtp_queue}")) text = rep(text, "rtp_queue", num(rtp, "queueSize"));
            if (text.contains("{discord}")) {
                Object l = discord != null ? call(discord, "link") : null;
                text = rep(text, "discord", l == null ? "" : String.valueOf(l));
            }
            LocalDateTime now = LocalDateTime.now();
            text = rep(text, "date", now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            text = rep(text, "time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        if (papi != null && text.indexOf('%') >= 0) {
            try {
                Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
                Object r = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class).invoke(null, p, text);
                if (r instanceof String s) text = s;
            } catch (Throwable ignored) {
            }
        }
        return Util.color(text);
    }

    private static String rep(String text, String key, String value) {
        return text.replace("{" + key + "}", value);
    }

    private static int stat(OfflinePlayer p, Statistic s) {
        try {
            return p.getStatistic(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String time(long seconds) {
        long m = seconds / 60, h = m / 60, d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (m % 60) + "m";
        return m + "m";
    }

    static Object call(Object target, String name, Object... args) {
        for (Class<?> type : publicTypes(target.getClass())) {
            for (Method m : type.getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                Class<?>[] types = m.getParameterTypes();
                boolean fits = true;
                for (int i = 0; i < args.length; i++) {
                    Class<?> t = types[i] == double.class ? Double.class : types[i];
                    if (args[i] != null && !t.isInstance(args[i])) {
                        fits = false;
                        break;
                    }
                }
                if (!fits) continue;
                try {
                    return m.invoke(target, args);
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    private static List<Class<?>> publicTypes(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        ArrayDeque<Class<?>> todo = new ArrayDeque<>();
        todo.add(c);
        while (!todo.isEmpty()) {
            Class<?> k = todo.poll();
            if (k == null || out.contains(k)) continue;
            if (Modifier.isPublic(k.getModifiers())) out.add(k);
            todo.addAll(Arrays.asList(k.getInterfaces()));
            if (k.getSuperclass() != null && k.getSuperclass() != Object.class) todo.add(k.getSuperclass());
        }
        return out;
    }
}
