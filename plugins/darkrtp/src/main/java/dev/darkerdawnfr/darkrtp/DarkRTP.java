package dev.darkerdawnfr.darkrtp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dark RTP by DarkerDawnFR.
 * /rtp opens a menu (Overworld, Nether, End, Queue), /rtp <world> teleports straight away.
 * /rtpqueue (/rtpq) waits for another player, then sends you both to the same random spot.
 */
public class DarkRTP extends JavaPlugin implements Listener, TabCompleter {
    private Finder finder;
    private final RtpQueue queue = new RtpQueue();
    private final Map<UUID, BukkitTask> pending = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        finder = new Finder(this);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("rtp", "rtpqueue", "darkrtp")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        // remind people in the queue that they're waiting
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID id : queue.all()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) Chat.actionBar(p, Util.color("&dIn the RTP queue &7(" + queue.size() + " waiting) &8- &f/rtpq &7to leave"));
            }
        }, 40, 40);
        getLogger().info("Dark RTP ready. Worlds: " + worlds().keySet());
    }

    /** Used by Dark Scoreboard. */
    public int queueSize() {
        return queue.size();
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&dRTP&8] "));
    }

    private void say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
    }

    /** overworld/nether/end -> the world, for the ones that exist and are turned on. */
    public Map<String, World> worlds() {
        Map<String, World> out = new LinkedHashMap<>();
        for (World w : Bukkit.getWorlds()) {
            String k = Finder.key(w.getEnvironment());
            if (!out.containsKey(k) && getConfig().getBoolean("worlds." + k + ".enabled", true)) out.put(k, w);
        }
        return out;
    }

    // ------------------------------------------------------------------ /rtp

    public Menu menu() {
        Map<String, World> ws = worlds();
        Menu m = new Menu(3, "&8" + Util.small("Random teleport"));
        option(m, 11, ws.get("overworld"), Material.GRASS_BLOCK, "&aOverworld");
        option(m, 13, ws.get("nether"), Material.NETHERRACK, "&cNether");
        option(m, 15, ws.get("end"), Material.END_STONE, "&dEnd");
        m.set(22, Util.item(Material.CLOCK, "&eRTP Queue", "&7Wait for another player,", "&7then you both go to the same spot.",
                "&7Waiting now: &f" + queue.size(), "", "&eClick to join or leave"), e -> {
            Player p = (Player) e.getWhoClicked();
            p.closeInventory();
            toggleQueue(p);
        });
        m.fill();
        return m;
    }

    private void option(Menu m, int slot, World w, Material icon, String name) {
        if (w == null) {
            m.set(slot, Util.item(Material.BARRIER, name, "&cNot available on this server"));
            return;
        }
        m.set(slot, Util.item(icon, name, "&7Players here: &f" + w.getPlayers().size(), "", "&eClick to teleport"), e -> {
            Player p = (Player) e.getWhoClicked();
            p.closeInventory();
            rtp(p, w);
        });
    }

    /** True if Dark Combat says this player is in combat. */
    public static boolean inCombat(Player p) {
        org.bukkit.plugin.Plugin c = Bukkit.getPluginManager().getPlugin("DarkCombat");
        if (c == null || !c.isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(c.getClass().getMethod("inCombat", UUID.class).invoke(c, p.getUniqueId()));
        } catch (Throwable t) {
            return false;
        }
    }

    public void rtp(Player p, World w) {
        if (inCombat(p)) {
            say(p, "&cYou can't teleport while you're in combat.");
            return;
        }
        if (pending.containsKey(p.getUniqueId())) {
            say(p, "&cYou're already teleporting.");
            return;
        }
        if (!p.hasPermission("darkrtp.bypass")) {
            long left = cooldown.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
            if (left > 0) {
                say(p, "&cWait &f" + (left / 1000 + 1) + "s &cbefore you /rtp again.");
                return;
            }
        }
        say(p, "&7Finding a safe spot...");
        pending.put(p.getUniqueId(), null);
        finder.find(w, loc -> {
            if (!p.isOnline()) {
                pending.remove(p.getUniqueId());
                return;
            }
            if (loc == null) {
                pending.remove(p.getUniqueId());
                say(p, "&cCouldn't find a safe spot, try again.");
                return;
            }
            int secs = Math.max(0, getConfig().getInt("countdown-seconds", 5)); // everyone waits, ops too
            countdown(p, secs, true, () -> {
                go(p, loc);
                cooldown.put(p.getUniqueId(), System.currentTimeMillis() + getConfig().getLong("cooldown-seconds", 20) * 1000);
            });
        });
    }

    /** Titles counting down; if cancelOnMove and they walk away, it stops. */
    private void countdown(Player p, int secs, boolean cancelOnMove, Runnable then) {
        Location start = p.getLocation();
        AtomicInteger left = new AtomicInteger(secs);
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline()) {
                stop(p, task[0]);
                return;
            }
            Location now = p.getLocation();
            if (cancelOnMove && (now.getWorld() != start.getWorld() || now.getBlockX() != start.getBlockX()
                    || now.getBlockY() != start.getBlockY() || now.getBlockZ() != start.getBlockZ())) {
                stop(p, task[0]);
                p.sendTitle(Util.color("&cCancelled"), Util.color("&7You moved"), 0, 30, 10);
                say(p, "&cTeleport cancelled because you moved.");
                return;
            }
            int n = left.getAndDecrement();
            if (n <= 0) {
                stop(p, task[0]);
                then.run();
                return;
            }
            p.sendTitle(Util.color("&dTeleporting in " + n), Util.color(cancelOnMove ? "&7Don't move" : ""), 0, 25, 0);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1f);
        }, 0, 20);
        pending.put(p.getUniqueId(), task[0]);
    }

    private void stop(Player p, BukkitTask t) {
        if (t != null) t.cancel();
        pending.remove(p.getUniqueId());
    }

    private void go(Player p, Location loc) {
        if (inCombat(p)) {
            say(p, "&cTeleport cancelled, you're in combat.");
            return;
        }
        Location to = loc.clone();
        to.setYaw(p.getLocation().getYaw());
        to.setPitch(p.getLocation().getPitch());
        p.setFallDistance(0);
        p.teleport(to);
        p.sendTitle(Util.color("&dTeleported"), Util.color("&f" + to.getBlockX() + ", " + to.getBlockY() + ", " + to.getBlockZ()), 0, 40, 10);
        p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1f);
        say(p, "&7Teleported to &f" + to.getBlockX() + ", " + to.getBlockY() + ", " + to.getBlockZ()
                + " &7in the &f" + Finder.key(to.getWorld().getEnvironment()) + "&7.");
    }

    // ------------------------------------------------------------------ /rtpqueue

    public void toggleQueue(Player p) {
        if (queue.leave(p.getUniqueId())) {
            say(p, "&7You left the RTP queue.");
            return;
        }
        if (pending.containsKey(p.getUniqueId())) {
            say(p, "&cYou're already teleporting.");
            return;
        }
        World w = worlds().get(getConfig().getString("queue-world", "overworld"));
        if (w == null) w = worlds().values().stream().findFirst().orElse(null);
        if (w == null) {
            say(p, "&cThere is no world to teleport to.");
            return;
        }
        queue.join(p.getUniqueId());
        say(p, "&aYou joined the RTP queue &7(" + queue.size() + " waiting). &7When someone else joins, you both go to the same spot. &f/rtpq &7to leave.");
        UUID[] pair = queue.pair();
        if (pair != null) match(pair, w);
    }

    private void match(UUID[] pair, World w) {
        Player a = Bukkit.getPlayer(pair[0]), b = Bukkit.getPlayer(pair[1]);
        if (a == null || b == null) {
            // someone left: the other one waits again
            for (Player p : new Player[]{a, b}) if (p != null) queue.join(p.getUniqueId());
            return;
        }
        for (Player[] ab : new Player[][]{{a, b}, {b, a}}) {
            pending.put(ab[0].getUniqueId(), null);
            ab[0].sendTitle(Util.color("&aMatch found!"), Util.color("&fYou're going with " + ab[1].getName()), 0, 40, 10);
            say(ab[0], "&aMatch found! &7You and &f" + ab[1].getName() + " &7are going to the same random spot.");
        }
        finder.find(w, loc -> {
            if (loc == null) {
                for (Player p : new Player[]{a, b}) {
                    pending.remove(p.getUniqueId());
                    if (p.isOnline()) say(p, "&cCouldn't find a safe spot. Join again with /rtpq.");
                }
                return;
            }
            int secs = Math.max(0, getConfig().getInt("queue-countdown-seconds", 3));
            for (Player p : new Player[]{a, b}) countdown(p, secs, false, () -> {
                if (p.isOnline()) go(p, loc);
            });
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        queue.leave(id);
        BukkitTask t = pending.remove(id);
        if (t != null) t.cancel();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("darkrtp")) {
            String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
            if (sub.equals("reload")) {
                reloadConfig();
                say(s, "&aReloaded. Worlds: " + worlds().keySet());
            } else if (sub.equals("test")) {
                test(s);
            } else {
                say(s, "&7Dark RTP " + getDescription().getVersion() + " by DarkerDawnFR. Worlds: " + worlds().keySet());
                say(s, "&7/darkrtp reload  -  /darkrtp test");
            }
            return true;
        }
        if (!(s instanceof Player p)) {
            say(s, "&cOnly players can teleport.");
            return true;
        }
        if (name.equals("rtpqueue")) {
            if (a.length > 0 && a[0].equalsIgnoreCase("leave")) {
                if (!queue.leave(p.getUniqueId())) say(p, "&7You're not in the queue.");
                else say(p, "&7You left the RTP queue.");
            } else {
                toggleQueue(p);
            }
            return true;
        }
        if (a.length == 0) {
            menu().open(p);
            return true;
        }
        String k = switch (a[0].toLowerCase(Locale.ROOT)) {
            case "overworld", "world", "o" -> "overworld";
            case "nether", "n" -> "nether";
            case "end", "the_end", "e" -> "end";
            default -> "";
        };
        World w = worlds().get(k);
        if (w == null) say(p, "&cUse /rtp overworld, /rtp nether or /rtp end" + (k.isEmpty() ? "" : " (that one is off here)") + ".");
        else rtp(p, w);
        return true;
    }

    /** Finds a spot in every world and checks the queue. Used by MC Hoster's server test. */
    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        RtpQueue q = new RtpQueue();
        UUID x = UUID.randomUUID(), y = UUID.randomUUID(), z = UUID.randomUUID();
        q.join(x);
        if (q.pair() != null) fails.add("paired one player");
        q.join(y);
        q.join(z);
        UUID[] pr = q.pair();
        if (pr == null || !pr[0].equals(x) || !pr[1].equals(y) || q.size() != 1) fails.add("queue order");
        if (!q.leave(z) || q.size() != 0) fails.add("queue leave");
        Menu m = menu();
        if (m.getInventory().getItem(11) == null || m.getInventory().getItem(22) == null) fails.add("menu");
        Map<String, World> ws = worlds();
        if (!ws.containsKey("overworld")) fails.add("no overworld");
        Map<String, String> found = new LinkedHashMap<>();
        AtomicInteger left = new AtomicInteger(ws.size());
        long t0 = System.currentTimeMillis();
        Runnable finish = () -> {
            String result = fails.isEmpty() ? "DARKRTP TEST OK " + found + " in " + (System.currentTimeMillis() - t0) + "ms"
                    : "DARKRTP TEST FAIL: " + fails + " " + found;
            getLogger().info(result);
            if (!(s instanceof ConsoleCommandSender)) s.sendMessage(result);
        };
        if (ws.isEmpty()) finish.run();
        for (Map.Entry<String, World> e : ws.entrySet()) {
            finder.find(e.getValue(), loc -> {
                if (loc == null) fails.add("no spot in " + e.getKey());
                else {
                    found.put(e.getKey(), loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
                    if (!Finder.safe(loc.getBlock().getRelative(0, -1, 0))) fails.add(e.getKey() + " spot is not safe");
                }
                if (left.decrementAndGet() == 0) finish.run();
            });
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            if (n.equals("rtp")) out.addAll(worlds().keySet());
            else if (n.equals("rtpqueue")) out.add("leave");
            else if (n.equals("darkrtp")) out.addAll(List.of("reload", "test"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.startsWith(last));
        return out;
    }
}
