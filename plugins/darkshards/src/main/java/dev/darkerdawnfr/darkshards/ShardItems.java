package dev.darkerdawnfr.darkshards;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shard shop items: each lasts 24 hours (configurable), mines in an area, makes purple particles and amethyst sounds.
 * Abilities: pickaxe and shovel (size x size area), axe (whole tree), hoe (harvest and replant an area of crops).
 */
public class ShardItems implements Listener {
    public record Def(String id, String name, Material material, String ability, int size, long price, double hours,
                      int slot, List<String> lore, float speed, int maxLogs) {}

    private final DarkShards plugin;
    private final NamespacedKey idKey, expiresKey;
    private final Set<UUID> breaking = new HashSet<>();
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();

    public ShardItems(DarkShards plugin) {
        this.plugin = plugin;
        idKey = new NamespacedKey(plugin, "item");
        expiresKey = new NamespacedKey(plugin, "expires");
        reload();
    }

    public void reload() {
        defs.clear();
        problems.clear();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("items");
        if (cs == null) return;
        int auto = 10;
        for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null || !c.getBoolean("enabled", true)) continue;
            Material m = Util.material(c.getString("material", "AMETHYST_SHARD"));
            if (m == null || !m.isItem()) {
                problems.add(id + ": no item called " + c.getString("material"));
                continue;
            }
            defs.put(id, new Def(id, c.getString("name", id), m, c.getString("ability", "pickaxe").toLowerCase(),
                    Math.max(1, c.getInt("size", 3)), Math.max(0, c.getLong("price", 50)), c.getDouble("hours", 24),
                    c.getInt("slot", auto++), c.getStringList("lore"), (float) c.getDouble("speed", 9), c.getInt("max-logs", 128)));
        }
    }

    public Map<String, Def> defs() {
        return defs;
    }

    public List<String> problems() {
        return problems;
    }

    // ------------------------------------------------------------------ making items

    public ItemStack create(Def d) {
        return create(d, System.currentTimeMillis() + (long) (d.hours() * 3_600_000L));
    }

    public ItemStack create(Def d, long expires) {
        ItemStack it = new ItemStack(d.material());
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.setDisplayName(Util.color(d.name()));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, d.id());
        pdc.set(expiresKey, PersistentDataType.LONG, expires);
        meta.setLore(lore(d, expires));
        try {
            Glint.on(meta);
        } catch (Throwable ignored) {
        }
        try {
            ToolPower.apply(meta, d);
        } catch (Throwable ignored) {
            // no tool components before 1.20.6: the item still works, it just mines at its normal speed
        }
        it.setItemMeta(meta);
        return it;
    }

    private List<String> lore(Def d, long expires) {
        List<String> l = new ArrayList<>();
        for (String s : d.lore()) l.add(Util.color(s.replace("{size}", d.size() + "x" + d.size())));
        l.add("");
        l.add(Util.color("&7Expires in: &d" + time(expires - System.currentTimeMillis())));
        l.add(Util.color("&5✦ Shard item"));
        return l;
    }

    public static String time(long ms) {
        if (ms <= 0) return "now";
        long m = ms / 60000, h = m / 60;
        if (h > 0) return h + "h " + (m % 60) + "m";
        return Math.max(1, m) + "m";
    }

    public Def def(ItemStack it) {
        if (it == null || !it.hasItemMeta() || it.getItemMeta() == null) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : defs.getOrDefault(id, new Def(id, id, it.getType(), "none", 1, 0, 24, 0, List.of(), 1, 0));
    }

    public long expires(ItemStack it) {
        Long e = it.getItemMeta().getPersistentDataContainer().get(expiresKey, PersistentDataType.LONG);
        return e == null ? 0 : e;
    }

    public boolean expired(ItemStack it) {
        return def(it) != null && expires(it) <= System.currentTimeMillis();
    }

    /** Removes expired shard items and refreshes the "Expires in" line. Returns how many were removed. */
    public int sweep(Player p) {
        int removed = 0;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            Def d = def(it);
            if (d == null) continue;
            if (expired(it)) {
                p.getInventory().setItem(i, null);
                removed++;
                p.sendMessage(plugin.msg("expired").replace("{item}", Util.color(d.name())));
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 0.6f);
                continue;
            }
            ItemMeta meta = it.getItemMeta();
            List<String> now = lore(defs.getOrDefault(d.id(), d), expires(it));
            if (!now.equals(meta.getLore())) {
                meta.setLore(now);
                it.setItemMeta(meta);
                p.getInventory().setItem(i, it);
            }
        }
        for (int i = 0; i < p.getEnderChest().getSize(); i++) {
            ItemStack it = p.getEnderChest().getItem(i);
            if (def(it) != null && expired(it)) {
                p.getEnderChest().setItem(i, null);
                removed++;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ effects

    private static final Particle.DustOptions PURPLE = new Particle.DustOptions(Color.fromRGB(170, 70, 255), 1.0f);

    public void sparkle(Location l, int count) {
        if (l.getWorld() == null || !plugin.getConfig().getBoolean("effects.particles", true)) return;
        l.getWorld().spawnParticle(Particle.DUST, l, count, 0.35, 0.35, 0.35, 0, PURPLE);
    }

    public void sound(Location l, Sound s, float pitch) {
        if (l.getWorld() == null || !plugin.getConfig().getBoolean("effects.sounds", true)) return;
        l.getWorld().playSound(l, s, 0.7f, pitch);
    }

    /** Little purple particles around the hand of everyone holding a shard item. */
    public void heldParticles() {
        if (!plugin.getConfig().getBoolean("effects.held-particles", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (def(p.getInventory().getItemInMainHand()) == null) continue;
            Location eye = p.getEyeLocation();
            Location hand = eye.add(eye.getDirection().multiply(0.6)).add(rightOf(p).multiply(0.35)).subtract(0, 0.45, 0);
            p.getWorld().spawnParticle(Particle.DUST, hand, 2, 0.08, 0.08, 0.08, 0, PURPLE);
        }
    }

    private static org.bukkit.util.Vector rightOf(Player p) {
        double yaw = Math.toRadians(p.getLocation().getYaw());
        return new org.bukkit.util.Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
    }

    // ------------------------------------------------------------------ abilities

    @EventHandler(ignoreCancelled = true)
    public void startMining(BlockDamageEvent e) {
        ItemStack it = e.getItemInHand();
        if (def(it) == null) return;
        if (expired(it)) {
            sweep(e.getPlayer());
            e.setCancelled(true);
            return;
        }
        Location c = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        sound(c, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.2f);
        sparkle(c, 6);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void mine(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (breaking.contains(p.getUniqueId())) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        Def d = def(tool);
        if (d == null) return;
        if (expired(tool)) {
            e.setCancelled(true);
            sweep(p);
            return;
        }
        Block b = e.getBlock();
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        sound(c, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1f);
        sparkle(c, 12);
        List<Block> extra = switch (d.ability()) {
            case "pickaxe" -> Tag.MINEABLE_PICKAXE.isTagged(b.getType()) ? area(p, b, d.size(), Tag.MINEABLE_PICKAXE) : List.of();
            case "shovel" -> Tag.MINEABLE_SHOVEL.isTagged(b.getType()) ? area(p, b, d.size(), Tag.MINEABLE_SHOVEL) : List.of();
            case "axe" -> Tag.LOGS.isTagged(b.getType()) ? tree(b, d.maxLogs()) : List.of();
            case "hoe" -> crops(b, d.size());
            default -> List.of();
        };
        if (d.ability().equals("hoe") && grown(b)) replantLater(b);
        breaking.add(p.getUniqueId());
        try {
            for (Block x : extra) {
                if (x.equals(b) || x.getType().isAir() || x.getType().getHardness() < 0) continue;
                BlockBreakEvent ev = new BlockBreakEvent(x, p);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) continue;
                boolean replant = d.ability().equals("hoe");
                Material crop = x.getType();
                x.breakNaturally(tool);
                if (replant) replant(x, crop);
                sparkle(x.getLocation().add(0.5, 0.5, 0.5), 4);
            }
        } finally {
            breaking.remove(p.getUniqueId());
        }
        if (!extra.isEmpty()) sound(c, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.8f);
    }

    @EventHandler(ignoreCancelled = false)
    public void use(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (def(it) == null) return;
        if (expired(it)) {
            e.setCancelled(true);
            sweep(e.getPlayer());
            return;
        }
        Location l = e.getClickedBlock() != null ? e.getClickedBlock().getLocation().add(0.5, 1, 0.5) : e.getPlayer().getEyeLocation();
        sound(l, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.3f);
        sparkle(l, 5);
    }

    /** The size x size square facing the player (flat if they look up or down). */
    private static List<Block> area(Player p, Block center, int size, Tag<Material> tag) {
        int r = (size - 1) / 2;
        float pitch = p.getLocation().getPitch();
        double yaw = ((p.getLocation().getYaw() % 360) + 360) % 360;
        boolean flat = Math.abs(pitch) > 50;
        boolean alongX = (yaw > 45 && yaw < 135) || (yaw > 225 && yaw < 315); // looking east/west
        List<Block> out = new ArrayList<>();
        for (int i = -r; i <= r; i++)
            for (int j = -r; j <= r; j++) {
                Block x = flat ? center.getRelative(i, 0, j) : alongX ? center.getRelative(0, j, i) : center.getRelative(i, j, 0);
                if (tag.isTagged(x.getType())) out.add(x);
            }
        return out;
    }

    private static List<Block> tree(Block start, int max) {
        List<Block> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        ArrayDeque<Block> todo = new ArrayDeque<>(List.of(start));
        while (!todo.isEmpty() && out.size() < max) {
            Block b = todo.poll();
            if (!seen.add(b) || !Tag.LOGS.isTagged(b.getType())) continue;
            out.add(b);
            for (int x = -1; x <= 1; x++)
                for (int y = 0; y <= 1; y++)
                    for (int z = -1; z <= 1; z++) if (x != 0 || y != 0 || z != 0) todo.add(b.getRelative(x, y, z));
        }
        return out;
    }

    private static boolean grown(Block b) {
        return b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge() && Tag.CROPS.isTagged(b.getType());
    }

    private static List<Block> crops(Block center, int size) {
        if (!Tag.CROPS.isTagged(center.getType())) return List.of();
        int r = (size - 1) / 2;
        List<Block> out = new ArrayList<>();
        for (int i = -r; i <= r; i++)
            for (int j = -r; j <= r; j++) {
                Block x = center.getRelative(i, 0, j);
                if (grown(x)) out.add(x);
            }
        return out;
    }

    private void replant(Block b, Material crop) {
        if (!b.getType().isAir() || !Tag.CROPS.isTagged(crop)) return;
        b.setType(crop);
        if (b.getBlockData() instanceof Ageable a) {
            a.setAge(0);
            b.setBlockData(a);
        }
    }

    private void replantLater(Block b) {
        Material crop = b.getType();
        Bukkit.getScheduler().runTask(plugin, () -> replant(b, crop));
    }

    // shard items can't be crafted with or renamed
    @EventHandler
    public void craft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (def(it) != null) {
            e.getInventory().setResult(null);
            return;
        }
    }

    @EventHandler
    public void anvil(PrepareAnvilEvent e) {
        for (ItemStack it : e.getInventory().getContents()) if (def(it) != null) {
            e.setResult(null);
            return;
        }
    }

    // ------------------------------------------------------------------ version-specific bits

    private static final class ToolPower {
        /** 1.20.6+: makes any item (like an amethyst shard) mine like a real tool, with the right drops. */
        static void apply(ItemMeta meta, Def d) {
            Tag<Material> tag = switch (d.ability()) {
                case "pickaxe" -> Tag.MINEABLE_PICKAXE;
                case "shovel" -> Tag.MINEABLE_SHOVEL;
                case "axe" -> Tag.MINEABLE_AXE;
                case "hoe" -> Tag.MINEABLE_HOE;
                default -> null;
            };
            if (tag == null) return;
            org.bukkit.inventory.meta.components.ToolComponent t = meta.getTool();
            t.setDefaultMiningSpeed(1f);
            t.addRule(tag, d.speed(), true);
            meta.setTool(t);
        }
    }

    private static final class Glint {
        static void on(ItemMeta meta) {
            meta.setEnchantmentGlintOverride(true);
        }
    }
}
