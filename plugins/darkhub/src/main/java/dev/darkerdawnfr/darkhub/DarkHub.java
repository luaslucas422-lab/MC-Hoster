package dev.darkerdawnfr.darkhub;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dark Hub by DarkerDawnFR: /hub opens a menu of beds, one per hub, each with its own name and a player limit
 * (100 by default), and a Random Hub button at the end. Ops set them with /hubset <number> [name].
 */
public class DarkHub extends JavaPlugin implements Listener, TabCompleter {
    public record Hub(int number, String name, Location loc, Material bed) {}

    private static final Material[] BEDS = {Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED, Material.LIGHT_BLUE_BED,
            Material.YELLOW_BED, Material.LIME_BED, Material.PINK_BED, Material.GRAY_BED, Material.LIGHT_GRAY_BED, Material.CYAN_BED,
            Material.PURPLE_BED, Material.BLUE_BED, Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED, Material.BLACK_BED};
    private static final int MAX_HUBS = 44;

    private final Map<UUID, Integer> lastHub = new HashMap<>();
    private Tp tp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tp = new Tp(this);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("hub", "hubset", "hubdel", "darkhub")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&eHub&8] "));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
        return true;
    }

    // ------------------------------------------------------------------ hubs

    public TreeMap<Integer, Hub> hubs() {
        TreeMap<Integer, Hub> out = new TreeMap<>();
        ConfigurationSection cs = getConfig().getConfigurationSection("hubs");
        if (cs == null) return out;
        for (String k : cs.getKeys(false)) {
            ConfigurationSection h = cs.getConfigurationSection(k);
            if (h == null) continue;
            try {
                int n = Integer.parseInt(k);
                World w = Bukkit.getWorld(h.getString("world", ""));
                if (w == null) continue;
                Location l = new Location(w, h.getDouble("x"), h.getDouble("y"), h.getDouble("z"), (float) h.getDouble("yaw"), (float) h.getDouble("pitch"));
                Material bed = Util.material(h.getString("bed", ""));
                if (bed == null || !bed.name().endsWith("_BED")) bed = BEDS[(n - 1) % BEDS.length];
                out.put(n, new Hub(n, h.getString("name", "Hub " + n), l, bed));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public void setHub(int n, Location l, String name) {
        String p = "hubs." + n + ".";
        getConfig().set(p + "world", l.getWorld().getName());
        getConfig().set(p + "x", l.getX());
        getConfig().set(p + "y", l.getY());
        getConfig().set(p + "z", l.getZ());
        getConfig().set(p + "yaw", l.getYaw());
        getConfig().set(p + "pitch", l.getPitch());
        if (name != null && !name.isEmpty()) getConfig().set(p + "name", name);
        else if (!getConfig().isSet(p + "name")) getConfig().set(p + "name", "Hub " + n);
        saveConfig();
    }

    public int max() {
        return Math.max(1, getConfig().getInt("max-players", 100));
    }

    /** Players at a hub: everyone within radius blocks of it. */
    public int players(Hub h) {
        double r = getConfig().getDouble("radius", 150);
        int n = 0;
        for (Player p : h.loc().getWorld().getPlayers())
            if (p.getLocation().distanceSquared(h.loc()) <= r * r) n++;
        return n;
    }

    public Hub random() {
        List<Hub> open = new ArrayList<>();
        for (Hub h : hubs().values()) if (players(h) < max()) open.add(h);
        return open.isEmpty() ? null : open.get(ThreadLocalRandom.current().nextInt(open.size()));
    }

    // ------------------------------------------------------------------ menu

    public Menu menu() {
        TreeMap<Integer, Hub> hubs = hubs();
        int count = Math.min(MAX_HUBS, hubs.size()) + 1;
        int rows = Math.max(3, Math.min(6, (count + 8) / 9 + 2));
        Menu m = new Menu(rows, "&8" + Util.small("Hubs"));
        int slot = 10;
        int shown = 0;
        for (Hub h : hubs.values()) {
            if (shown++ >= MAX_HUBS) break;
            while (slot % 9 == 0 || slot % 9 == 8) slot++;
            if (slot >= rows * 9 - 9) break;
            int players = players(h);
            boolean full = players >= max();
            m.set(slot++, Util.item(h.bed(), Math.max(1, Math.min(64, players)), "&e" + h.name(), List.of(
                    "&7Players: " + (full ? "&c" : "&a") + players + "&7/&f" + max(), "",
                    full ? "&cThis hub is full" : "&eClick to teleport")), e -> {
                Player p = (Player) e.getWhoClicked();
                p.closeInventory();
                go(p, h);
            });
        }
        while (slot % 9 == 0 || slot % 9 == 8) slot++;
        if (hubs.isEmpty()) {
            m.set(13, Util.item(Material.PAPER, "&7No hubs yet", "&7Ops: stand where a hub should be", "&7and type &f/hubset 1 [name]"));
        } else if (slot < rows * 9 - 9) {
            m.set(slot, Util.item(Material.ENDER_EYE, "&d&lRandom Hub", "&7Sends you to a random hub", "&7that isn't full.", "", "&eClick to teleport"), e -> {
                Player p = (Player) e.getWhoClicked();
                p.closeInventory();
                Hub h = random();
                if (h == null) say(p, "&cEvery hub is full right now.");
                else go(p, h);
            });
        }
        m.border();
        return m;
    }

    public void go(Player p, Hub h) {
        if (players(h) >= max() && !p.hasPermission("darkhub.bypassfull")) {
            say(p, "&c" + h.name() + " is full (" + max() + " players). Try another one.");
            return;
        }
        if (tp.busy(p.getUniqueId())) {
            say(p, "&cYou're already teleporting.");
            return;
        }
        tp.start(p, getConfig().getInt("countdown-seconds", 5), true, () -> h.loc(), prefix(), () -> {
            lastHub.put(p.getUniqueId(), h.number());
            say(p, "&aWelcome to &e" + h.name() + "&a.");
        });
    }

    // ------------------------------------------------------------------ respawning at your hub

    @EventHandler(priority = EventPriority.HIGH)
    public void respawn(PlayerRespawnEvent e) {
        if (!getConfig().getBoolean("respawn-at-hub", true)) return;
        try {
            if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        } catch (Throwable ignored) {
        }
        Hub h = hubs().get(lastHub.getOrDefault(e.getPlayer().getUniqueId(), -1));
        if (h == null) h = random();
        if (h != null) e.setRespawnLocation(h.loc());
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        if (!e.getPlayer().hasPlayedBefore() && getConfig().getBoolean("new-players-to-hub", false)) {
            Hub h = random();
            if (h != null) Bukkit.getScheduler().runTaskLater(this, () -> e.getPlayer().teleport(h.loc()), 5);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        tp.cancel(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "hub" -> {
                if (!(s instanceof Player p)) return say(s, "&cOnly players can go to a hub.");
                if (a.length > 0) {
                    if (a[0].equalsIgnoreCase("random")) {
                        Hub h = random();
                        if (h == null) return say(p, "&cThere's no hub with space.");
                        go(p, h);
                        return true;
                    }
                    try {
                        Hub h = hubs().get(Integer.parseInt(a[0]));
                        if (h == null) return say(p, "&cThere's no hub " + a[0] + ".");
                        go(p, h);
                        return true;
                    } catch (NumberFormatException ignored) {
                    }
                }
                menu().open(p);
            }
            case "hubset" -> {
                if (!(s instanceof Player p)) return say(s, "&cStand where the hub should be and type /hubset <number>.");
                int n;
                try {
                    n = Integer.parseInt(a.length > 0 ? a[0] : "");
                } catch (NumberFormatException e) {
                    return say(p, "&7Usage: /hubset <number> [name]  e.g. /hubset 1 Spawn Hub");
                }
                if (n < 1 || n > MAX_HUBS) return say(p, "&cHub numbers go from 1 to " + MAX_HUBS + ".");
                String hubName = a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : null;
                setHub(n, p.getLocation(), hubName);
                say(p, "&aHub " + n + " (&e" + hubs().get(n).name() + "&a) is set where you stand.");
            }
            case "hubdel" -> {
                if (a.length < 1) return say(s, "&7Usage: /hubdel <number>");
                if (!getConfig().isSet("hubs." + a[0])) return say(s, "&cThere's no hub " + a[0] + ".");
                getConfig().set("hubs." + a[0], null);
                saveConfig();
                say(s, "&7Hub " + a[0] + " deleted.");
            }
            case "darkhub" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("test")) test(s);
                else if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    say(s, "&aReloaded. " + hubs().size() + " hubs.");
                } else say(s, "&7Dark Hub " + getDescription().getVersion() + " by DarkerDawnFR. " + hubs().size() + " hubs. /darkhub reload|test");
            }
            default -> { return false; }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        String backup = getConfig().saveToString();
        try {
            World w = Bukkit.getWorlds().get(0);
            if (!menu().getInventory().contains(Material.PAPER)) fails.add("empty menu has no help");
            setHub(1, new Location(w, 0.5, 100, 0.5), "Spawn Hub");
            setHub(2, new Location(w, 500.5, 100, 500.5), null);
            setHub(3, new Location(w, -500.5, 100, 0.5), "Third");
            reloadConfig();
            TreeMap<Integer, Hub> hs = hubs();
            if (hs.size() != 3 || !hs.get(1).name().equals("Spawn Hub") || !hs.get(2).name().equals("Hub 2")) fails.add("hubs " + hs);
            Menu m = menu();
            // three beds, then the random button right after the last one
            if (m.getInventory().getItem(10) == null || m.getInventory().getItem(10).getType() != Material.WHITE_BED) fails.add("hub 1 bed");
            if (m.getInventory().getItem(13) == null || m.getInventory().getItem(13).getType() != Material.ENDER_EYE) fails.add("random button not at the end");
            if (random() == null) fails.add("no random hub");
            if (players(hs.get(1)) != 0) fails.add("player count");
            if (max() != 100) fails.add("max players is " + max());
            getConfig().set("max-players", 250);
            if (max() != 250) fails.add("max players setting ignored");
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            try {
                java.nio.file.Files.writeString(new java.io.File(getDataFolder(), "config.yml").toPath(), backup);
            } catch (java.io.IOException ignored) {
            }
            reloadConfig();
        }
        String r = fails.isEmpty() ? "DARKHUB TEST OK" : "DARKHUB TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            if (cmd.getName().equalsIgnoreCase("hubset")) for (int i = 1; i <= hubs().size() + 1; i++) out.add(String.valueOf(i));
            else if (!cmd.getName().equalsIgnoreCase("darkhub")) {
                for (int n : hubs().keySet()) out.add(String.valueOf(n));
                if (cmd.getName().equalsIgnoreCase("hub")) out.add("random");
            }
        }
        return out;
    }
}
