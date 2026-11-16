package PKG;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Boxes in the world, picked with two corners: "/... set 1" and "/... set 2" (the block you look at, or where you stand),
 * or a wand (left-click = corner 1, right-click = corner 2). Saved in areas.yml.
 */
public final class Areas implements Listener {

    public static final class Area {
        public final String name;
        public final String world;
        public final int x1, y1, z1, x2, y2, z2;
        public boolean on = true;
        public final Set<UUID> allowed = new LinkedHashSet<>();
        public final Map<String, Object> extra = new LinkedHashMap<>();

        public Area(String name, String world, int ax, int ay, int az, int bx, int by, int bz) {
            this.name = name;
            this.world = world;
            x1 = Math.min(ax, bx);
            y1 = Math.min(ay, by);
            z1 = Math.min(az, bz);
            x2 = Math.max(ax, bx);
            y2 = Math.max(ay, by);
            z2 = Math.max(az, bz);
        }

        public boolean contains(Location l) {
            if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }

        public long size() {
            return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        }

        public String box() {
            return (x2 - x1 + 1) + "x" + (y2 - y1 + 1) + "x" + (z2 - z1 + 1);
        }

        public Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, (x1 + x2) / 2.0 + 0.5, y1, (z1 + z2) / 2.0 + 0.5);
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final NamespacedKey wandKey;
    private final String wandName;
    private final Map<String, Area> areas = new LinkedHashMap<>();
    private final Map<UUID, Location[]> picks = new HashMap<>();
    /** Runs when a player has set both corners (by command or wand). */
    public java.util.function.Consumer<Player> onBoth;

    public Areas(JavaPlugin plugin, String wandName) {
        this.plugin = plugin;
        this.wandName = wandName;
        this.file = new File(plugin.getDataFolder(), "areas.yml");
        this.wandKey = new NamespacedKey(plugin, "wand");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public Map<String, Area> all() {
        return areas;
    }

    public Area get(String name) {
        return name == null ? null : areas.get(name.toLowerCase(Locale.ROOT));
    }

    /** The areas a location is in. */
    public List<Area> at(Location l) {
        List<Area> out = new ArrayList<>();
        for (Area a : areas.values()) if (a.contains(l)) out.add(a);
        return out;
    }

    // ---------------------------------------------------------------- picking corners

    public Location[] picks(UUID id) {
        return picks.computeIfAbsent(id, k -> new Location[2]);
    }

    /** Corner n (1 or 2): the block the player looks at (6 blocks), or the block they stand on. */
    public Location pick(Player p, int n) {
        Block b = p.getTargetBlockExact(6);
        Location l = b != null && b.getType() != Material.AIR ? b.getLocation() : p.getLocation().getBlock().getLocation();
        picks(p.getUniqueId())[n - 1] = l;
        return l;
    }

    public ItemStack wand() {
        ItemStack it = Util.item(Material.BLAZE_ROD, wandName, "&7Left-click a block: &fcorner 1", "&7Right-click a block: &fcorner 2");
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    public boolean isWand(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta() != null
                && it.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void wandClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null || !isWand(e.getItem())) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        int n = e.getAction() == Action.LEFT_CLICK_BLOCK ? 1 : 2;
        Location l = e.getClickedBlock().getLocation();
        picks(e.getPlayer().getUniqueId())[n - 1] = l;
        e.getPlayer().sendMessage(Util.color("&7Corner &f" + n + " &7set: &f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ()));
        if (bothSet(e.getPlayer().getUniqueId()) && onBoth != null) onBoth.accept(e.getPlayer());
    }

    public boolean bothSet(UUID id) {
        Location[] c = picks(id);
        return c[0] != null && c[1] != null;
    }

    public void clearPicks(UUID id) {
        picks.remove(id);
    }

    /** Gives an area another name. */
    public boolean rename(String old, String name) {
        Area a = get(old);
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (a == null || id.isEmpty() || areas.containsKey(id)) return false;
        Area n = new Area(id, a.world, a.x1, a.y1, a.z1, a.x2, a.y2, a.z2);
        n.on = a.on;
        n.allowed.addAll(a.allowed);
        n.extra.putAll(a.extra);
        areas.remove(a.name);
        areas.put(id, n);
        save();
        return true;
    }

    /** Makes an area from the player's two corners. Returns null and says why if it can't. */
    public Area create(Player p, String name, String[] why) {
        Location[] c = picks(p.getUniqueId());
        if (c[0] == null || c[1] == null) {
            why[0] = "Set both corners first (set 1 and set 2, or the wand).";
            return null;
        }
        if (c[0].getWorld() != c[1].getWorld()) {
            why[0] = "Both corners have to be in the same world.";
            return null;
        }
        return create(name, c[0], c[1], why);
    }

    public Area create(String name, Location a, Location b, String[] why) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (id.isEmpty()) {
            why[0] = "Use letters or numbers in the name.";
            return null;
        }
        if (areas.containsKey(id)) {
            why[0] = "There's already an area called " + id + ".";
            return null;
        }
        Area ar = new Area(id, a.getWorld().getName(), a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX(), b.getBlockY(), b.getBlockZ());
        areas.put(id, ar);
        save();
        return ar;
    }

    public boolean delete(String name) {
        boolean had = areas.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (had) save();
        return had;
    }

    /** The next free name: area1, area2... */
    public String nextName(String base) {
        int i = 1;
        while (areas.containsKey(base + i)) i++;
        return base + i;
    }

    // ---------------------------------------------------------------- saving

    public void load() {
        areas.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = y.getConfigurationSection("areas");
        if (cs == null) return;
        for (String k : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(k);
            if (c == null) continue;
            Area a = new Area(k, c.getString("world", "world"), c.getInt("x1"), c.getInt("y1"), c.getInt("z1"), c.getInt("x2"), c.getInt("y2"), c.getInt("z2"));
            a.on = c.getBoolean("on", true);
            for (String u : c.getStringList("allowed")) {
                try {
                    a.allowed.add(UUID.fromString(u));
                } catch (IllegalArgumentException ignored) {
                }
            }
            ConfigurationSection ex = c.getConfigurationSection("extra");
            if (ex != null) for (String e : ex.getKeys(false)) a.extra.put(e, ex.get(e));
            areas.put(k, a);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Area a : areas.values()) {
            String k = "areas." + a.name + ".";
            y.set(k + "world", a.world);
            y.set(k + "x1", a.x1);
            y.set(k + "y1", a.y1);
            y.set(k + "z1", a.z1);
            y.set(k + "x2", a.x2);
            y.set(k + "y2", a.y2);
            y.set(k + "z2", a.z2);
            y.set(k + "on", a.on);
            List<String> al = new ArrayList<>();
            for (UUID u : a.allowed) al.add(u.toString());
            y.set(k + "allowed", al);
            for (Map.Entry<String, Object> e : a.extra.entrySet()) y.set(k + "extra." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save areas.yml: " + e.getMessage());
        }
    }
}
