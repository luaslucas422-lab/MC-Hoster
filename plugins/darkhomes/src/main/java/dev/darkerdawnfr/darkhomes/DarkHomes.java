package dev.darkerdawnfr.darkhomes;

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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dark Homes by DarkerDawnFR: the modern DonutSMP /home menu.
 * Beds for each home slot (click to teleport or set), a delete button under each, 5-second no-move teleport.
 */
public class DarkHomes extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, Map<Integer, Location>> homes = new HashMap<>();
    private File file;
    private Tp tp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tp = new Tp(this);
        file = new File(getDataFolder(), "homes.yml");
        load();
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("home", "sethome", "delhome", "darkhomes")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&bHomes&8] "));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
        return true;
    }

    /** How many homes a player can have: darkhomes.homes.<n> permissions raise the default. */
    public int max(Player p) {
        int max = getConfig().getInt("default-homes", 5);
        for (int n = 1; n <= 27; n++) if (p != null && p.hasPermission("darkhomes.homes." + n)) max = Math.max(max, n);
        return Math.max(1, Math.min(27, max));
    }

    public Map<Integer, Location> of(UUID id) {
        return homes.computeIfAbsent(id, k -> new HashMap<>());
    }

    public void set(UUID id, int n, Location l) {
        of(id).put(n, l.clone());
        save();
    }

    public boolean delete(UUID id, int n) {
        boolean had = of(id).remove(n) != null;
        if (had) save();
        return had;
    }

    // ------------------------------------------------------------------ menu

    private static final Material[] BEDS = {Material.LIGHT_BLUE_BED, Material.LIME_BED, Material.YELLOW_BED, Material.ORANGE_BED,
            Material.MAGENTA_BED, Material.PINK_BED, Material.CYAN_BED, Material.PURPLE_BED, Material.RED_BED};

    /** Coordinates as shown to this player: random ones if they turned Random Coords on in /settings. */
    private static String coords(Player p, Location l) {
        if (p != null && Settings.on(p.getUniqueId(), "randomcoords", false)) {
            java.util.Random r = new java.util.Random(l.hashCode());
            return (r.nextInt(20000) - 10000) + ", " + (r.nextInt(200) - 10) + ", " + (r.nextInt(20000) - 10000);
        }
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    public Menu menu(Player p) {
        int max = max(p);
        int groups = (max + 8) / 9; // a row of beds and a row of delete buttons for every 9 homes
        Menu m = new Menu(Math.min(6, Math.max(4, groups * 2 + (groups < 3 ? 2 : 0))), "&8" + Util.small("Homes"));
        Map<Integer, Location> mine = of(p.getUniqueId());
        for (int i = 0; i < max; i++) {
            int n = i + 1;
            Location l = mine.get(n);
            int g = i / 9, inRow = Math.min(9, max - g * 9), start = (9 - inRow) / 2;
            int top = groups < 3 ? 9 + g * 18 : g * 18;
            int bedSlot = top + start + i % 9, delSlot = bedSlot + 9;
            if (l != null) {
                m.set(bedSlot, Util.item(BEDS[i % BEDS.length], "&bHome " + n,
                        "&7" + world(l) + " &f" + coords(p, l), "", "&eClick to teleport"), e -> {
                    p.closeInventory();
                    go(p, n);
                });
                m.set(delSlot, Util.item(Material.RED_DYE, "&cDelete Home " + n, "&7Click to delete"),
                        e -> confirmDelete(p, n).open(p));
            } else {
                m.set(bedSlot, Util.item(Material.GRAY_BED, "&7Home " + n, "&7No home set", "", "&eClick to set it here"), e -> {
                    p.closeInventory();
                    sethome(p, n);
                });
                m.set(delSlot, Util.item(Material.GRAY_DYE, "&8Home " + n, "&7Nothing to delete"));
            }
        }
        m.fill();
        return m;
    }

    private Menu confirmDelete(Player p, int n) {
        Menu m = new Menu(3, "&8" + Util.small("Delete Home " + n + "?"));
        m.set(11, Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lConfirm", "&7Delete Home " + n), e -> {
            delete(p.getUniqueId(), n);
            say(p, "&7Home " + n + " deleted.");
            menu(p).open(p);
        });
        m.set(13, Util.item(Material.RED_BED, "&cHome " + n));
        m.set(15, Util.item(Material.RED_STAINED_GLASS_PANE, "&c&lCancel"), e -> menu(p).open(p));
        m.fill();
        return m;
    }

    private static String world(Location l) {
        World w = l.getWorld();
        if (w == null) return "?";
        return switch (w.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }

    // ------------------------------------------------------------------ actions

    private void sethome(Player p, int n) {
        if (n < 1 || n > max(p)) {
            say(p, "&cYou can have " + max(p) + " homes, use a number from 1 to " + max(p) + ".");
            return;
        }
        for (String w : getConfig().getStringList("blocked-worlds"))
            if (p.getWorld().getName().equalsIgnoreCase(w)) {
                say(p, "&cYou can't set a home in this world.");
                return;
            }
        set(p.getUniqueId(), n, p.getLocation());
        say(p, "&aHome " + n + " set here.");
    }

    private void go(Player p, int n) {
        Location l = of(p.getUniqueId()).get(n);
        if (l == null || l.getWorld() == null) {
            say(p, "&cHome " + n + " isn't set. Use /sethome " + n + " or /home.");
            return;
        }
        if (tp.busy(p.getUniqueId())) {
            say(p, "&cYou're already teleporting.");
            return;
        }
        tp.start(p, getConfig().getInt("countdown-seconds", 5), true, () -> l, prefix(), () -> say(p, "&aTeleported to Home " + n + "."));
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        tp.cancel(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("darkhomes")) {
            if (a.length > 0 && a[0].equalsIgnoreCase("test")) test(s);
            else if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                say(s, "&aReloaded.");
            } else say(s, "&7Dark Homes " + getDescription().getVersion() + " by DarkerDawnFR. /darkhomes reload|test");
            return true;
        }
        if (!(s instanceof Player p)) return say(s, "&cOnly players have homes.");
        int n = 0;
        if (a.length > 0) {
            try {
                n = Integer.parseInt(a[0].replaceAll("(?i)home", "").trim());
            } catch (NumberFormatException e) {
                return say(p, "&cUse a home number, e.g. /" + name + " 1");
            }
        }
        switch (name) {
            case "home" -> {
                if (n == 0) menu(p).open(p);
                else go(p, n);
            }
            case "sethome" -> {
                if (n == 0) {
                    for (int i = 1; i <= max(p); i++) if (!of(p.getUniqueId()).containsKey(i)) {
                        n = i;
                        break;
                    }
                    if (n == 0) return say(p, "&cAll your homes are set. Use /sethome <number> to move one.");
                }
                sethome(p, n);
            }
            case "delhome" -> {
                if (n == 0) return say(p, "&7Usage: /delhome <number>");
                if (delete(p.getUniqueId(), n)) say(p, "&7Home " + n + " deleted.");
                else say(p, "&cHome " + n + " isn't set.");
            }
            default -> { return false; }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID id = UUID.nameUUIDFromBytes("darkhomes-test".getBytes());
        try {
            World w = Bukkit.getWorlds().get(0);
            set(id, 2, new Location(w, 10.5, 70, -20.5, 90, 0));
            homes.clear();
            load();
            Location l = of(id).get(2);
            if (l == null || l.getBlockX() != 10 || l.getBlockZ() != -21 || l.getYaw() != 90) fails.add("home not saved: " + l);
            if (!delete(id, 2) || delete(id, 2)) fails.add("delete");
            if (max(null) != getConfig().getInt("default-homes", 5)) fails.add("default homes");
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            homes.remove(id);
            save();
        }
        String r = fails.isEmpty() ? "DARKHOMES TEST OK" : "DARKHOMES TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection c = y.getConfigurationSection(k);
            if (c == null) continue;
            try {
                UUID id = UUID.fromString(k);
                for (String n : c.getKeys(false)) {
                    ConfigurationSection h = c.getConfigurationSection(n);
                    World w = h == null ? null : Bukkit.getWorld(h.getString("world", ""));
                    if (w == null) continue;
                    of(id).put(Integer.parseInt(n), new Location(w, h.getDouble("x"), h.getDouble("y"), h.getDouble("z"),
                            (float) h.getDouble("yaw"), (float) h.getDouble("pitch")));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Map<Integer, Location>> e : homes.entrySet())
            for (Map.Entry<Integer, Location> h : e.getValue().entrySet()) {
                Location l = h.getValue();
                if (l.getWorld() == null) continue;
                String p = e.getKey() + "." + h.getKey() + ".";
                y.set(p + "world", l.getWorld().getName());
                y.set(p + "x", l.getX());
                y.set(p + "y", l.getY());
                y.set(p + "z", l.getZ());
                y.set(p + "yaw", l.getYaw());
                y.set(p + "pitch", l.getPitch());
            }
        try {
            getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            getLogger().warning("Could not save homes: " + e);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1 && s instanceof Player p && !cmd.getName().equalsIgnoreCase("darkhomes"))
            for (int i = 1; i <= max(p); i++) out.add(String.valueOf(i));
        return out;
    }
}
