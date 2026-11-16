package dev.darkerdawnfr.darkduels;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dark Duels by DarkerDawnFR: /duel <player> [bet]. Both go to a free arena (set with /duel arena set <name> 1|2),
 * 3, 2, 1, fight. Nobody loses their items in a duel. The winner gets the bet (Dark Shop money), and both go back
 * where they were. Wins, losses and streaks are kept (/duel stats).
 */
public class DarkDuels extends JavaPlugin implements Listener, TabCompleter {
    public record Request(UUID from, UUID to, double bet, long expires) {}

    public static final class Duel {
        public final UUID a, b;
        public final String arena;
        public final double bet;
        public final Map<UUID, Location> back = new HashMap<>();
        public boolean fighting, over;

        Duel(UUID a, UUID b, String arena, double bet) {
            this.a = a;
            this.b = b;
            this.arena = arena;
            this.bet = bet;
        }

        public UUID other(UUID id) {
            return id.equals(a) ? b : a;
        }
    }

    private final List<Request> requests = new ArrayList<>();
    private final Map<UUID, Duel> inDuel = new HashMap<>();
    private final Set<UUID> preparing = new HashSet<>();
    private final Map<UUID, Location> respawnAt = new HashMap<>();
    private YamlConfiguration stats;
    private File statsFile;
    private Tp tp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tp = new Tp(this);
        statsFile = new File(getDataFolder(), "stats.yml");
        stats = YamlConfiguration.loadConfiguration(statsFile);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("duel");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        for (Duel d : new HashSet<>(inDuel.values())) end(d, null);
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&cDuels&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
    }

    // ------------------------------------------------------------------ arenas

    public List<String> arenas() {
        ConfigurationSection c = getConfig().getConfigurationSection("arenas");
        List<String> out = new ArrayList<>();
        if (c != null) for (String k : c.getKeys(false)) if (spot(k, 1) != null && spot(k, 2) != null) out.add(k);
        return out;
    }

    public Location spot(String arena, int n) {
        ConfigurationSection c = getConfig().getConfigurationSection("arenas." + arena + "." + n);
        if (c == null) return null;
        World w = Bukkit.getWorld(c.getString("world", ""));
        return w == null ? null : new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), (float) c.getDouble("pitch"));
    }

    public void setSpot(String arena, int n, Location l) {
        String p = "arenas." + arena + "." + n + ".";
        getConfig().set(p + "world", l.getWorld().getName());
        getConfig().set(p + "x", l.getX());
        getConfig().set(p + "y", l.getY());
        getConfig().set(p + "z", l.getZ());
        getConfig().set(p + "yaw", l.getYaw());
        getConfig().set(p + "pitch", l.getPitch());
        saveConfig();
    }

    public String freeArena() {
        Set<String> used = new HashSet<>();
        for (Duel d : inDuel.values()) used.add(d.arena);
        for (String a : arenas()) if (!used.contains(a)) return a;
        return null;
    }

    // ------------------------------------------------------------------ money (Dark Shop)

    static Object call(String plugin, String method, Object... args) {
        Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        if (p == null || !p.isEnabled()) return null;
        for (Method m : p.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            try {
                return m.invoke(p, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean take(UUID id, double amount) {
        return amount <= 0 || Boolean.TRUE.equals(call("DarkShop", "take", id, amount));
    }

    private static void give(UUID id, double amount) {
        if (amount > 0) call("DarkShop", "give", id, amount);
    }

    // ------------------------------------------------------------------ requests

    public Request find(UUID to, UUID from) {
        long now = System.currentTimeMillis();
        requests.removeIf(r -> r.expires() < now);
        for (int i = requests.size() - 1; i >= 0; i--) {
            Request r = requests.get(i);
            if (r.to().equals(to) && (from == null || r.from().equals(from))) return r;
        }
        return null;
    }

    public Request request(UUID from, UUID to, double bet) {
        requests.removeIf(r -> r.from().equals(from) && r.to().equals(to));
        Request r = new Request(from, to, bet, System.currentTimeMillis() + 60_000);
        requests.add(r);
        return r;
    }

    private boolean busy(Player p) {
        return inDuel.containsKey(p.getUniqueId()) || preparing.contains(p.getUniqueId()) || Tp.inCombat(p);
    }

    private void accept(Player p, Request r) {
        requests.remove(r);
        Player from = Bukkit.getPlayer(r.from());
        if (from == null) {
            say(p, "&cThey left.");
            return;
        }
        if (busy(p) || busy(from)) {
            say(p, "&cOne of you is busy (in a duel or in combat).");
            return;
        }
        String arena = freeArena();
        if (arena == null) {
            say(p, arenas().isEmpty() ? "&cThere are no arenas yet. Ops: /duel arena set <name> 1 and 2." : "&cEvery arena is in use, try again soon.");
            say(from, "&cNo free arena right now.");
            return;
        }
        if (!take(from.getUniqueId(), r.bet())) {
            say(p, "&c" + from.getName() + " can't pay the bet.");
            say(from, "&cYou don't have the " + Util.money(r.bet()) + " bet.");
            return;
        }
        if (!take(p.getUniqueId(), r.bet())) {
            give(from.getUniqueId(), r.bet());
            say(p, "&cYou don't have the " + Util.money(r.bet()) + " bet.");
            return;
        }
        Duel d = new Duel(from.getUniqueId(), p.getUniqueId(), arena, r.bet());
        say(from, "&a" + p.getName() + " accepted! Stand still...");
        say(p, "&aDuel accepted! Stand still...");
        // the 5-second no-move teleport for both; if either moves, it's off
        int[] arrived = {0};
        boolean[] cancelled = {false};
        for (Player pl : new Player[]{from, p}) {
            preparing.add(pl.getUniqueId());
            d.back.put(pl.getUniqueId(), pl.getLocation());
            int n = pl == from ? 1 : 2;
            tp.start(pl, getConfig().getInt("countdown-seconds", 5), true, () -> cancelled[0] ? null : spot(arena, n), prefix(), () -> {
                if (++arrived[0] == 2) start(d);
            });
        }
        // if someone moved, the Tp countdown stops: refund after it would have ended
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (arrived[0] < 2 && !inDuel.containsKey(d.a)) {
                cancelled[0] = true;
                for (UUID id : new UUID[]{d.a, d.b}) {
                    preparing.remove(id);
                    tp.cancel(id);
                    give(id, d.bet);
                    Player x = Bukkit.getPlayer(id);
                    if (x != null) {
                        if (arrived[0] > 0) x.teleport(d.back.get(id));
                        say(x, "&cThe duel was called off (someone moved or left).");
                    }
                }
            }
        }, 20L * (getConfig().getInt("countdown-seconds", 5) + 2));
    }

    // ------------------------------------------------------------------ the fight

    private void start(Duel d) {
        Player a = Bukkit.getPlayer(d.a), b = Bukkit.getPlayer(d.b);
        preparing.remove(d.a);
        preparing.remove(d.b);
        if (a == null || b == null) return;
        inDuel.put(d.a, d);
        inDuel.put(d.b, d);
        for (Player p : new Player[]{a, b}) {
            heal(p);
            p.setGameMode(GameMode.SURVIVAL);
        }
        int[] n = {3};
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
            if (d.over) {
                cancel();
                return;
            }
            for (Player p : new Player[]{Bukkit.getPlayer(d.a), Bukkit.getPlayer(d.b)}) {
                if (p == null) continue;
                if (n[0] > 0) {
                    p.sendTitle(Util.color("&c" + n[0]), Util.color("&7vs &f" + Bukkit.getOfflinePlayer(d.other(p.getUniqueId())).getName()), 0, 22, 0);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1f);
                } else {
                    p.sendTitle(Util.color("&c&lFIGHT!"), "", 0, 20, 10);
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
                }
            }
            if (n[0]-- <= 0) {
                d.fighting = true;
                cancel();
            }
            }
        }.runTaskTimer(this, 0, 20);
    }

    private static void heal(Player p) {
        try {
            var max = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            p.setHealth(max == null ? 20 : max.getValue());
        } catch (Throwable t) {
            p.setHealth(Math.min(20, p.getHealth() + 20));
        }
        p.setFoodLevel(20);
        p.setSaturation(10);
        p.setFireTicks(0);
    }

    /** Ends a duel. winner null = nobody (server stopping). */
    public void end(Duel d, UUID winner) {
        if (d.over) return;
        d.over = true;
        inDuel.remove(d.a);
        inDuel.remove(d.b);
        if (winner == null) {
            give(d.a, d.bet);
            give(d.b, d.bet);
        } else {
            UUID loser = d.other(winner);
            give(winner, d.bet * 2);
            stat(winner, true);
            stat(loser, false);
            String wn = Bukkit.getOfflinePlayer(winner).getName(), ln = Bukkit.getOfflinePlayer(loser).getName();
            if (getConfig().getBoolean("announce", true))
                Bukkit.broadcastMessage(prefix() + Util.color("&f" + wn + " &7beat &f" + ln + " &7in a duel" + (d.bet > 0 ? " and won &a" + Util.money(d.bet * 2) : "") + "&7!"));
            Player w = Bukkit.getPlayer(winner);
            if (w != null) {
                w.sendTitle(Util.color("&a&lYOU WON"), Util.color("&7Win streak: &f" + stats.getInt(winner + ".streak")), 5, 50, 10);
                Location back = d.back.get(winner);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (w.isOnline() && back != null) {
                        heal(w);
                        w.teleport(back);
                    }
                }, 60);
            }
        }
        saveStats();
    }

    private void stat(UUID id, boolean won) {
        String p = id + ".";
        stats.set(p + (won ? "wins" : "losses"), stats.getInt(p + (won ? "wins" : "losses")) + 1);
        int streak = won ? stats.getInt(p + "streak") + 1 : 0;
        stats.set(p + "streak", streak);
        stats.set(p + "best", Math.max(stats.getInt(p + "best"), streak));
    }

    private void saveStats() {
        try {
            getDataFolder().mkdirs();
            stats.save(statsFile);
        } catch (IOException ignored) {
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void death(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        Duel d = inDuel.get(dead.getUniqueId());
        if (d == null) return;
        // nobody loses their things in a duel
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        respawnAt.put(dead.getUniqueId(), d.back.get(dead.getUniqueId()));
        end(d, d.other(dead.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent e) {
        Location l = respawnAt.remove(e.getPlayer().getUniqueId());
        if (l != null) e.setRespawnLocation(l);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        Entity dmg = e.getDamager();
        if (dmg instanceof Projectile pr && pr.getShooter() instanceof Entity sh) dmg = sh;
        UUID victim = e.getEntity().getUniqueId(), attacker = dmg.getUniqueId();
        Duel dv = inDuel.get(victim), da = inDuel.get(attacker);
        if (dv == null && da == null) return;
        // only the two duelists can hurt each other, and only after "FIGHT!"
        if (dv == null || dv != da || !dv.fighting) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void freeze(PlayerMoveEvent e) {
        Duel d = inDuel.get(e.getPlayer().getUniqueId());
        if (d != null && !d.fighting && e.getTo() != null
                && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ())) {
            Location l = e.getFrom().clone();
            l.setYaw(e.getTo().getYaw());
            l.setPitch(e.getTo().getPitch());
            e.setTo(l);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void commands(PlayerCommandPreprocessEvent e) {
        if (!inDuel.containsKey(e.getPlayer().getUniqueId()) || e.getPlayer().hasPermission("darkduels.admin")) return;
        String c = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (c.contains(":")) c = c.substring(c.indexOf(':') + 1);
        if (getConfig().getStringList("allowed-commands").contains(c)) return;
        e.setCancelled(true);
        say(e.getPlayer(), "&cYou can't use commands in a duel. &8(/duel leave to give up)");
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        requests.removeIf(r -> r.from().equals(id) || r.to().equals(id));
        tp.cancel(id);
        Duel d = inDuel.get(id);
        if (d != null) {
            Location back = d.back.get(id);
            end(d, d.other(id)); // leaving = losing (but you keep your things)
            if (back != null) e.getPlayer().teleport(back);
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "arena" -> {
                if (!s.hasPermission("darkduels.admin")) return say(s, "&cOnly ops can set arenas.");
                String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (what.equals("list")) return say(s, "&7Arenas: &f" + (arenas().isEmpty() ? "none" : String.join(", ", arenas())));
                if (what.equals("remove") && a.length > 2) {
                    getConfig().set("arenas." + a[2], null);
                    saveConfig();
                    return say(s, "&7Arena " + a[2] + " removed.");
                }
                if (!what.equals("set") || a.length < 4 || !(s instanceof Player p))
                    return say(s, "&7Stand where a player starts and type &f/duel arena set <name> 1&7, then the other side &f/duel arena set <name> 2");
                int n = a[3].equals("2") ? 2 : 1;
                setSpot(a[2].toLowerCase(Locale.ROOT), n, p.getLocation());
                return say(p, "&aArena " + a[2] + " spot " + n + " set." + (spot(a[2].toLowerCase(Locale.ROOT), 3 - n) == null ? " &7Now set spot " + (3 - n) + "." : " &7Ready!"));
            }
            case "accept", "deny" -> {
                if (!(s instanceof Player p)) return true;
                Player f = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                Request r = find(p.getUniqueId(), f == null ? null : f.getUniqueId());
                if (r == null) return say(p, "&7You don't have a duel request" + (a.length > 1 ? " from " + a[1] : "") + ".");
                if (sub.equals("accept")) accept(p, r);
                else {
                    requests.remove(r);
                    say(p, "&7Duel denied.");
                    Player x = Bukkit.getPlayer(r.from());
                    if (x != null) say(x, "&c" + p.getName() + " denied your duel.");
                }
            }
            case "leave", "forfeit" -> {
                if (!(s instanceof Player p)) return true;
                Duel d = inDuel.get(p.getUniqueId());
                if (d == null) return say(p, "&7You're not in a duel.");
                Location back = d.back.get(p.getUniqueId());
                end(d, d.other(p.getUniqueId()));
                if (back != null) p.teleport(back);
                say(p, "&7You gave up.");
            }
            case "stats" -> {
                String name = a.length > 1 ? a[1] : s.getName();
                @SuppressWarnings("deprecation") UUID id = Bukkit.getOfflinePlayer(name).getUniqueId();
                say(s, "&f" + name + "&7: &a" + stats.getInt(id + ".wins") + " wins&7, &c" + stats.getInt(id + ".losses") + " losses&7, streak &f"
                        + stats.getInt(id + ".streak") + "&7, best &f" + stats.getInt(id + ".best"));
            }
            case "test" -> {
                if (s.hasPermission("darkduels.admin")) test(s);
            }
            case "" -> {
                say(s, "&f/duel <player> [bet] &8| &f/duel accept &8| &f/duel deny &8| &f/duel stats [player] &8| &f/duel leave");
                if (s.hasPermission("darkduels.admin")) say(s, "&8/duel arena set <name> 1|2  /duel arena list  /duel arena remove <name>");
            }
            default -> {
                if (!(s instanceof Player p)) return true;
                Player t = Bukkit.getPlayer(a[0]);
                if (t == null) return say(p, "&c" + a[0] + " isn't online.");
                if (t.equals(p)) return say(p, "&cYou can't duel yourself.");
                if (busy(p)) return say(p, "&cYou can't start a duel right now.");
                double bet = a.length > 1 ? Util.parseMoney(a[1]) : 0;
                if (Double.isNaN(bet) || bet < 0) return say(p, "&c" + a[1] + " isn't a bet. Try 1000 or 5k.");
                if (bet > 0 && Bukkit.getPluginManager().getPlugin("DarkShop") == null) return say(p, "&cBets need Dark Shop.");
                if (arenas().isEmpty()) return say(p, "&cThere are no duel arenas yet. Ask an op to make one.");
                request(p.getUniqueId(), t.getUniqueId(), bet);
                say(p, "&7Duel request sent to &f" + t.getName() + (bet > 0 ? " &7for &a" + Util.money(bet) : "") + "&7.");
                Chat.buttons(t, prefix() + Util.color("&f" + p.getName() + " &7wants to duel you" + (bet > 0 ? " for &a" + Util.money(bet) : "") + "&7!"),
                        new String[][]{{Util.color("&a&l[ACCEPT]"), "/duel accept " + p.getName(), Util.color("&aClick to fight")},
                                {Util.color("&c&l[DENY]"), "/duel deny " + p.getName(), Util.color("&cClick to say no")}});
            }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        String backup = getConfig().saveToString();
        String statsBackup = stats.saveToString();
        UUID x = UUID.randomUUID(), y = UUID.randomUUID();
        try {
            World w = Bukkit.getWorlds().get(0);
            if (!arenas().isEmpty() || freeArena() != null) fails.add("arena with no arenas");
            setSpot("pit", 1, new Location(w, 10, 100, 0));
            if (!arenas().isEmpty()) fails.add("half arena counted");
            setSpot("pit", 2, new Location(w, -10, 100, 0, 180, 0));
            reloadConfig();
            if (!arenas().contains("pit") || !"pit".equals(freeArena()) || spot("pit", 2).getYaw() != 180) fails.add("arena not saved");
            Request r = request(x, y, 500);
            if (find(y, null) != r || find(y, x) != r || find(x, null) != null) fails.add("requests");
            Duel d = new Duel(x, y, "pit", 0);
            inDuel.put(x, d);
            inDuel.put(y, d);
            if (freeArena() != null) fails.add("busy arena given out");
            end(d, x);
            if (inDuel.containsKey(x) || stats.getInt(x + ".wins") != 1 || stats.getInt(y + ".losses") != 1 || stats.getInt(x + ".streak") != 1)
                fails.add("result/stats");
            if (Bukkit.getPluginManager().getPlugin("DarkShop") != null
                    && String.valueOf(call("DarkShop", "economyMode")).startsWith("built-in")) {
                give(x, 1000);
                give(y, 1000);
                take(x, 300);
                take(y, 300);
                Duel bet = new Duel(x, y, "pit", 300);
                end(bet, y);
                Object bx = call("DarkShop", "balance", x), by = call("DarkShop", "balance", y);
                if (!(bx instanceof Number nx) || nx.doubleValue() != 700 || !(by instanceof Number ny) || ny.doubleValue() != 1300)
                    fails.add("bet payout " + bx + " " + by);
                take(x, 700);
                take(y, 1300);
            }
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            inDuel.remove(x);
            inDuel.remove(y);
            requests.clear();
            try {
                java.nio.file.Files.writeString(new File(getDataFolder(), "config.yml").toPath(), backup);
                stats.loadFromString(statsBackup);
                stats.set(x.toString(), null);
                stats.set(y.toString(), null);
                saveStats();
            } catch (Exception ignored) {
            }
            reloadConfig();
        }
        String r = fails.isEmpty() ? "DARKDUELS TEST OK" : "DARKDUELS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(s)) out.add(p.getName());
            out.addAll(List.of("accept", "deny", "stats", "leave"));
            if (s.hasPermission("darkduels.admin")) out.add("arena");
        } else if (a.length == 2 && a[0].equalsIgnoreCase("arena")) out.addAll(List.of("set", "list", "remove"));
        else if (a.length == 4 && a[0].equalsIgnoreCase("arena")) out.addAll(List.of("1", "2"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
