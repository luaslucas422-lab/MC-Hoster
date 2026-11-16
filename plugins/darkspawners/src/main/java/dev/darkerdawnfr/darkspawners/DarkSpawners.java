package dev.darkerdawnfr.darkspawners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dark Spawners by DarkerDawnFR, like DonutSMP's: placed spawners don't spawn mobs, they fill up with the mobs'
 * loot and XP. Stack more of the same kind on one (right-click it with another). Right-click to open it:
 * collect, sell everything (Dark Shop prices) or take the XP. Bought in the shard shop's Spawners page.
 */
public class DarkSpawners extends JavaPlugin implements Listener, TabCompleter {
    public record Loot(Material item, int min, int max) {}

    public record Type(String id, String name, EntityType entity, List<Loot> loot, int xp, long shardPrice) {}

    public static final class Spawner {
        public final String where, type;
        public final UUID owner;
        public int stack;
        public final Map<Material, Long> items = new EnumMap<>(Material.class);
        public long xp, last;

        Spawner(String where, String type, UUID owner, int stack, long last) {
            this.where = where;
            this.type = type;
            this.owner = owner;
            this.stack = stack;
            this.last = last;
        }

        public long stored() {
            long n = 0;
            for (long v : items.values()) n += v;
            return n;
        }
    }

    private final Map<String, Type> types = new LinkedHashMap<>();
    private final Map<String, Spawner> spawners = new HashMap<>();
    private NamespacedKey typeKey;
    private File file;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "type");
        file = new File(getDataFolder(), "spawners.yml");
        loadTypes();
        load();
        ask = new Ask(this);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("spawners");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::save, 20 * 120, 20 * 120);
        // fix spawners placed before (they showed no mob)
        Bukkit.getScheduler().runTask(this, () -> {
            for (org.bukkit.World w : Bukkit.getWorlds()) for (org.bukkit.Chunk c : w.getLoadedChunks()) showMobs(c);
        });
    }

    @Override
    public void onDisable() {
        save();
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&eSpawners&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
    }

    // ------------------------------------------------------------------ types

    private void loadTypes() {
        types.clear();
        ConfigurationSection cs = getConfig().getConfigurationSection("types");
        if (cs == null) return;
        for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null) continue;
            EntityType e;
            try {
                e = EntityType.valueOf(id.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            List<Loot> loot = new ArrayList<>();
            for (String l : c.getStringList("loot")) {
                String[] p = l.split(":");
                Material m = Util.material(p[0]);
                if (m == null || !m.isItem()) continue;
                int min = 1, max = 1;
                if (p.length > 1) {
                    String[] r = p[1].split("-");
                    min = Integer.parseInt(r[0].trim());
                    max = r.length > 1 ? Integer.parseInt(r[1].trim()) : min;
                }
                loot.add(new Loot(m, min, Math.max(min, max)));
            }
            types.put(id.toLowerCase(Locale.ROOT), new Type(id.toLowerCase(Locale.ROOT), c.getString("name", "&e&l" + id + " Spawner"), e, loot,
                    c.getInt("xp", 2), c.getLong("shard-price", 100)));
        }
    }

    private Ask ask;

    // ------------------------------------------------------------------ /spawners edit (the Spawners page of /shards)

    private void setType(String id, String key, Object v) {
        getConfig().set("types." + id + "." + key, v);
        saveConfig();
        loadTypes();
    }

    private static long num(String t) {
        try {
            return Long.parseLong(t.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public Menu editor(Player p) {
        Menu m = new Menu(4, "&8" + Util.small("Spawner editor"));
        int slot = 10;
        for (Type t : types.values()) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 26) break;
            List<String> lore = new ArrayList<>(List.of("&8id: " + t.id(), "&7Price: &d✦ " + t.shardPrice() + " shards" + (t.shardPrice() <= 0 ? " &8(not sold)" : ""),
                    "&7XP per drop: &f" + t.xp(), "&7Loot: &f" + String.join(", ", getConfig().getStringList("types." + t.id() + ".loot")), "", "&eClick to edit"));
            m.set(slot++, Util.item(Material.SPAWNER, 1, t.name(), lore), e -> typeEditor(p, t.id()).open(p));
        }
        m.set(31, Util.item(Material.LIME_DYE, "&aAdd a spawner", "&7Type the mob, like zombie or iron_golem"), e ->
                ask.ask(p, "the mob (like zombie)", t -> {
                    String id = t.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
                    EntityType et;
                    try {
                        et = EntityType.valueOf(id.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ex) {
                        say(p, "&cThere's no mob called " + t + ".");
                        return;
                    }
                    if (!et.isAlive() || types.containsKey(id)) {
                        say(p, "&cThat can't be a spawner, or it's already one.");
                        return;
                    }
                    setType(id, "name", "&f&l" + capital(id) + " Spawner");
                    setType(id, "shard-price", 100);
                    setType(id, "xp", 3);
                    setType(id, "loot", List.of("BONE:1-2"));
                    typeEditor(p, id).open(p);
                }, null));
        m.set(27, Util.item(Material.ARROW, "&fBack to the shard shop"), e -> p.performCommand("shards edit"));
        m.fill();
        return m;
    }

    private static String capital(String id) {
        StringBuilder b = new StringBuilder();
        for (String w : id.split("_")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return b.toString().trim();
    }

    public Menu typeEditor(Player p, String id) {
        Type t = types.get(id);
        if (t == null) return editor(p);
        Menu m = new Menu(3, "&8" + Util.small("Edit") + " " + org.bukkit.ChatColor.stripColor(Util.color(t.name())));
        m.set(4, item(t, 1));
        m.set(10, Util.item(Material.NAME_TAG, "&eName", "&7Now: " + t.name(), "&7& colours work"), e ->
                ask.ask(p, "the new name", x -> setType(id, "name", x.trim()), () -> typeEditor(p, id).open(p)));
        m.set(12, Util.item(Material.AMETHYST_SHARD, "&ePrice in /shards", "&7Now: &d✦ " + t.shardPrice(), "&70 = not sold"), e ->
                ask.ask(p, "the price in shards", x -> {
                    long v = num(x);
                    if (v < 0) say(p, "&cThat isn't a number.");
                    else setType(id, "shard-price", v);
                }, () -> typeEditor(p, id).open(p)));
        m.set(14, Util.item(Material.EXPERIENCE_BOTTLE, "&eXP per drop", "&7Now: &f" + t.xp()), e ->
                ask.ask(p, "the XP per drop", x -> {
                    long v = num(x);
                    if (v < 0) say(p, "&cThat isn't a number.");
                    else setType(id, "xp", v);
                }, () -> typeEditor(p, id).open(p)));
        List<String> loot = getConfig().getStringList("types." + id + ".loot");
        m.set(16, Util.item(Material.CHEST, "&eLoot", "&7Now: &f" + String.join(", ", loot), "&7Type it like: &fBONE:1-3, ARROW:0-2"), e ->
                ask.ask(p, "the loot (like BONE:1-3, ARROW:0-2)", x -> {
                    List<String> l = new ArrayList<>();
                    for (String part : x.split(",")) {
                        String q = part.trim().toUpperCase(Locale.ROOT);
                        Material mat = Util.material(q.split(":")[0]);
                        if (mat == null || !q.matches("[A-Z0-9_]+(:\\d+(-\\d+)?)?")) {
                            say(p, "&c" + part.trim() + " isn't an item like BONE:1-3.");
                            return;
                        }
                        l.add(q);
                    }
                    if (!l.isEmpty()) setType(id, "loot", l);
                }, () -> typeEditor(p, id).open(p)));
        m.set(18, Util.item(Material.ARROW, "&fBack"), e -> editor(p).open(p));
        m.set(26, Util.item(Material.BARRIER, "&cDelete this spawner type", "&7Shift-click. Placed ones stop making loot."), e -> {
            if (!e.isShiftClick()) return;
            getConfig().set("types." + id, null);
            saveConfig();
            loadTypes();
            editor(p).open(p);
        });
        m.fill();
        return m;
    }

    public Map<String, Type> types() {
        return types;
    }

    public ItemStack item(Type t, int amount) {
        ItemStack it = Util.item(Material.SPAWNER, amount, t.name(), List.of("&7Doesn't spawn mobs: it fills up with", "&7their loot and XP. Right-click it to open.",
                "&7Right-click it with another to stack them."));
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, t.id());
        if (m instanceof BlockStateMeta bsm) {
            try {
                BlockState st = bsm.getBlockState();
                if (st instanceof CreatureSpawner cs) {
                    cs.setSpawnedType(t.entity());
                    bsm.setBlockState(cs);
                }
            } catch (Throwable ignored) {
            }
        }
        it.setItemMeta(m);
        return it;
    }

    public Type typeOf(ItemStack it) {
        if (it == null || it.getType() != Material.SPAWNER || !it.hasItemMeta() || it.getItemMeta() == null) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? null : types.get(id);
    }

    // used by Dark Shards' Spawners page: {id, item to show, price in shards}
    public List<Object[]> shardOffers() {
        List<Object[]> out = new ArrayList<>();
        for (Type t : types.values()) if (t.shardPrice() > 0) out.add(new Object[]{t.id(), item(t, 1), t.shardPrice()});
        return out;
    }

    public boolean buyOffer(Player p, String id, int amount) {
        Type t = types.get(id);
        if (t == null) return false;
        for (ItemStack rest : p.getInventory().addItem(item(t, amount)).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
        return true;
    }

    // ------------------------------------------------------------------ placed spawners

    private static String where(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public Spawner at(Block b) {
        return spawners.get(where(b));
    }

    public Spawner register(Block b, Type t, UUID owner, int stack) {
        Spawner s = new Spawner(where(b), t.id(), owner, stack, System.currentTimeMillis());
        spawners.put(s.where, s);
        BlockState st = b.getState();
        if (st instanceof CreatureSpawner cs) {
            cs.setSpawnedType(t.entity());
            cs.update(true, false);
        }
        save();
        return s;
    }

    /** Makes the spinning mob show inside the cage (spawners placed empty look invisible). */
    public void showMob(Block b, Type t) {
        if (b.getType() != Material.SPAWNER || t == null) return;
        BlockState st = b.getState();
        if (st instanceof CreatureSpawner cs && cs.getSpawnedType() != t.entity()) {
            cs.setSpawnedType(t.entity());
            cs.update(true, false);
        }
    }

    private void showMobs(org.bukkit.Chunk c) {
        String pre = c.getWorld().getName() + ";";
        for (Spawner s : spawners.values()) {
            if (!s.where.startsWith(pre)) continue;
            String[] p = s.where.split(";");
            int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[3]);
            if (x >> 4 == c.getX() && z >> 4 == c.getZ()) showMob(c.getWorld().getBlockAt(x, Integer.parseInt(p[2]), z), types.get(s.type));
        }
    }

    @EventHandler
    public void chunkLoad(org.bukkit.event.world.ChunkLoadEvent e) {
        showMobs(e.getChunk());
    }

    private long period() {
        return Math.max(5, getConfig().getLong("seconds-per-drop", 60)) * 1000L;
    }

    /** Adds the loot made since last time (up to the storage limit). */
    public void generate(Spawner s) {
        Type t = types.get(s.type);
        if (t == null) return;
        long cycles = (System.currentTimeMillis() - s.last) / period();
        if (cycles <= 0) return;
        s.last += cycles * period();
        long cap = getConfig().getLong("storage-per-spawner", 1728) * s.stack;
        long runs = Math.min(cycles * s.stack, 1_000_000);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Loot l : t.loot()) {
            long got;
            if (runs <= 2000) {
                got = 0;
                for (long i = 0; i < runs; i++) got += r.nextInt(l.min(), l.max() + 1);
            } else got = Math.round(runs * (l.min() + l.max()) / 2.0);
            if (got <= 0) continue;
            long room = Math.max(0, cap - s.stored());
            s.items.merge(l.item(), Math.min(got, room), Long::sum);
        }
        s.xp = Math.min(s.xp + runs * t.xp(), getConfig().getLong("xp-per-spawner", 5000) * s.stack);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Type t = typeOf(e.getItemInHand());
        if (t == null) return;
        Block placed = e.getBlockPlaced();
        register(placed, t, e.getPlayer().getUniqueId(), 1);
        // Minecraft drops a spawner item's mob for players who aren't op, after this event: set it again a tick later
        Bukkit.getScheduler().runTask(this, () -> showMob(placed, t));
        say(e.getPlayer(), "&7Placed a " + t.name() + "&7. It fills with loot: right-click it to open.");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void click(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Spawner s = at(e.getClickedBlock());
        if (s == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        Type t = typeOf(hand);
        if (t != null) {
            if (!t.id().equals(s.type)) {
                say(p, "&cYou can only stack the same kind of spawner here.");
                return;
            }
            int add = p.isSneaking() ? hand.getAmount() : 1;
            generate(s);
            s.stack += add;
            hand.setAmount(hand.getAmount() - add);
            save();
            say(p, "&aStacked: this spawner is now &fx" + s.stack + "&a.");
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.6f);
            return;
        }
        menu(p, s).open(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void noMobs(SpawnerSpawnEvent e) {
        if (e.getSpawner() != null && spawners.containsKey(where(e.getSpawner().getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakIt(BlockBreakEvent e) {
        Spawner s = at(e.getBlock());
        if (s == null) return;
        Player p = e.getPlayer();
        if (!s.owner.equals(p.getUniqueId()) && !p.hasPermission("darkspawners.admin")) {
            e.setCancelled(true);
            say(p, "&cThis isn't your spawner.");
            return;
        }
        generate(s);
        e.setDropItems(false);
        e.setExpToDrop(0);
        Type t = types.get(s.type);
        spawners.remove(s.where);
        Location l = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        if (t != null) give(p, item(t, s.stack), l);
        s.items.forEach((m, n) -> giveMany(p, m, n, l));
        if (s.xp > 0) p.giveExp((int) Math.min(Integer.MAX_VALUE, s.xp));
        save();
        say(p, "&7You picked up &fx" + s.stack + " " + (t == null ? "spawner" : t.name()) + "&7 and everything in it.");
    }

    @EventHandler(ignoreCancelled = true)
    public void boom(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> spawners.containsKey(where(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void boom2(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> spawners.containsKey(where(b)));
    }

    private static void give(Player p, ItemStack it, Location fallback) {
        for (ItemStack rest : p.getInventory().addItem(it).values()) fallback.getWorld().dropItemNaturally(fallback, rest);
    }

    private static void giveMany(Player p, Material m, long n, Location l) {
        while (n > 0) {
            int s = (int) Math.min(n, m.getMaxStackSize());
            give(p, new ItemStack(m, s), l);
            n -= s;
        }
    }

    // ------------------------------------------------------------------ the spawner menu

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

    public double worth(Material m) {
        Object w = call("DarkShop", "worth", m.name());
        return w instanceof Number n ? n.doubleValue() : 0;
    }

    /** Sells everything that has a /sell price. Returns the money (0 if Dark Shop isn't installed). */
    public double sellAll(UUID who, Spawner s) {
        if (Bukkit.getPluginManager().getPlugin("DarkShop") == null) return 0;
        double total = 0;
        for (Map.Entry<Material, Long> e : new ArrayList<>(s.items.entrySet())) {
            double w = worth(e.getKey());
            if (w <= 0) continue;
            total += w * e.getValue();
            s.items.remove(e.getKey());
        }
        total = Math.round(total * 100) / 100.0;
        if (total > 0) call("DarkShop", "give", who, total);
        save();
        return total;
    }

    public Menu menu(Player p, Spawner s) {
        generate(s);
        Type t = types.get(s.type);
        String name = t == null ? "Spawner" : org.bukkit.ChatColor.stripColor(Util.color(t.name()));
        Menu m = new Menu(6, "&8" + Util.small(name) + " &8x" + s.stack);
        int slot = 0;
        for (Map.Entry<Material, Long> e : s.items.entrySet()) {
            long left = e.getValue();
            while (left > 0 && slot < 45) {
                int n = (int) Math.min(left, e.getKey().getMaxStackSize());
                Material mat = e.getKey();
                List<String> lore = new ArrayList<>();
                Object vl = call("DarkShop", "valueLore", mat.name(), n);
                if (vl instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
                lore.add("&7Click to take");
                m.set(slot++, Util.item(mat, n, null, lore), ev -> {
                    long have = s.items.getOrDefault(mat, 0L);
                    int take = (int) Math.min(have, mat.getMaxStackSize());
                    if (take <= 0) return;
                    if (p.getInventory().firstEmpty() == -1) {
                        say(p, "&cYour inventory is full.");
                        return;
                    }
                    s.items.put(mat, have - take);
                    if (have - take <= 0) s.items.remove(mat);
                    p.getInventory().addItem(new ItemStack(mat, take));
                    menu(p, s).open(p);
                });
                left -= n;
            }
        }
        for (int i = 45; i < 54; i++) m.set(i, Util.glass());
        double value = 0;
        for (Map.Entry<Material, Long> e : s.items.entrySet()) value += worth(e.getKey()) * e.getValue();
        m.set(45, Util.item(Material.SUNFLOWER, "&a&lSell all", "&7Sells everything for &a" + Util.money(value),
                Bukkit.getPluginManager().getPlugin("DarkShop") == null ? "&cNeeds Dark Shop" : "&eClick to sell"), e -> {
            double got = sellAll(p.getUniqueId(), s);
            if (got > 0) {
                say(p, "&aSold the loot for &f" + Util.money(got) + "&a.");
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
            }
            menu(p, s).open(p);
        });
        m.set(47, Util.item(Material.CHEST, "&fCollect all", "&7Into your inventory"), e -> {
            for (Map.Entry<Material, Long> en : new ArrayList<>(s.items.entrySet())) {
                long n = en.getValue();
                while (n > 0 && p.getInventory().firstEmpty() != -1) {
                    int take = (int) Math.min(n, en.getKey().getMaxStackSize());
                    p.getInventory().addItem(new ItemStack(en.getKey(), take));
                    n -= take;
                }
                if (n <= 0) s.items.remove(en.getKey());
                else s.items.put(en.getKey(), n);
            }
            save();
            menu(p, s).open(p);
        });
        long perHour = 3_600_000L / period();
        List<String> info = new ArrayList<>(List.of("&7Stacked: &fx" + s.stack, "&7Stored: &f" + s.stored() + "&7/&f"
                + getConfig().getLong("storage-per-spawner", 1728) * s.stack, "&7Every hour it makes:"));
        if (t != null) for (Loot l : t.loot()) info.add("&8 - &f" + (perHour * s.stack * (l.min() + l.max()) / 2) + " " + Util.nice(l.item()));
        info.add("");
        info.add("&7Right-click it with another to stack.");
        m.set(49, Util.item(Material.SPAWNER, 1, t == null ? "&eSpawner" : t.name(), info));
        m.set(51, Util.item(Material.EXPERIENCE_BOTTLE, "&b&lCollect XP", "&7Stored: &b" + s.xp + " XP", "&eClick to take it"), e -> {
            if (s.xp > 0) {
                p.giveExp((int) Math.min(Integer.MAX_VALUE, s.xp));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                s.xp = 0;
                save();
            }
            menu(p, s).open(p);
        });
        return m;
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection c = y.getConfigurationSection(k);
            if (c == null) continue;
            try {
                Spawner s = new Spawner(k.replace('|', ';'), c.getString("type", ""), UUID.fromString(c.getString("owner", "")),
                        c.getInt("stack", 1), c.getLong("last"));
                s.xp = c.getLong("xp");
                ConfigurationSection it = c.getConfigurationSection("items");
                if (it != null) for (String m : it.getKeys(false)) {
                    Material mat = Material.matchMaterial(m);
                    if (mat != null) s.items.put(mat, it.getLong(m));
                }
                spawners.put(s.where, s);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Spawner s : spawners.values()) {
            String p = s.where.replace(';', '|').replace('.', '_') + ".";
            y.set(p + "type", s.type);
            y.set(p + "owner", s.owner.toString());
            y.set(p + "stack", s.stack);
            y.set(p + "last", s.last);
            y.set(p + "xp", s.xp);
            for (Map.Entry<Material, Long> e : s.items.entrySet()) y.set(p + "items." + e.getKey().name(), e.getValue());
        }
        try {
            getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            getLogger().warning("Could not save spawners: " + e);
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission("darkspawners.admin");
        switch (sub) {
            case "edit", "editor" -> {
                if (!admin) return say(s, "&cOnly ops can edit spawners.");
                if (s instanceof Player p) editor(p).open(p);
            }
            case "give" -> {
                if (!admin) return true;
                if (a.length < 3) return say(s, "&7Usage: /spawners give <player> <" + String.join("|", types.keySet()) + "> [amount]");
                Player t = Bukkit.getPlayerExact(a[1]);
                Type type = types.get(a[2].toLowerCase(Locale.ROOT));
                if (t == null || type == null) return say(s, "&cPlayer not online or no spawner called " + a[2] + ".");
                int n = a.length > 3 ? Math.max(1, Integer.parseInt(a[3])) : 1;
                buyOffer(t, type.id(), n);
                say(s, "&7Gave " + n + "x " + type.name() + " &7to &f" + t.getName() + "&7.");
            }
            case "reload" -> {
                if (!admin) return true;
                reloadConfig();
                loadTypes();
                say(s, "&aReloaded. Spawners: " + types.keySet());
            }
            case "test" -> {
                if (admin) test(s);
            }
            default -> {
                say(s, "&fSpawners don't spawn mobs here: they fill with loot and XP. Buy them in &d/shards &f(Spawners page).");
                for (Type t : types.values())
                    s.sendMessage(Util.color("&8 - " + t.name() + " &7(&d✦ " + t.shardPrice() + "&7)"));
            }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        Block b = null;
        Material was = null;
        UUID owner = UUID.nameUUIDFromBytes("darkspawners-test".getBytes());
        try {
            if (types.size() < 8) fails.add("only " + types.size() + " spawner types");
            Type sk = types.get("skeleton");
            if (sk == null) throw new IllegalStateException("no skeleton spawner");
            if (typeOf(item(sk, 1)) != sk) fails.add("spawner item not recognised");
            World w = Bukkit.getWorlds().get(0);
            b = w.getHighestBlockAt(w.getSpawnLocation().clone().add(-4, 0, 4)).getRelative(0, 1, 0);
            was = b.getType();
            b.setType(Material.SPAWNER);
            Spawner sp = register(b, sk, owner, 2);
            if (!(b.getState() instanceof CreatureSpawner cs) || cs.getSpawnedType() != EntityType.SKELETON) fails.add("block not a skeleton spawner");
            if (b.getState() instanceof CreatureSpawner cs2) {
                cs2.setSpawnedType(EntityType.PIG); // as if Minecraft dropped the item's mob
                cs2.update(true, false);
                showMob(b, types.get("skeleton"));
                if (!(b.getState() instanceof CreatureSpawner cs3) || cs3.getSpawnedType() != EntityType.SKELETON) fails.add("mob not shown again");
            }
            sp.last = System.currentTimeMillis() - period() * 10;
            generate(sp);
            if (sp.items.getOrDefault(Material.BONE, 0L) < 20 || sp.xp <= 0) fails.add("loot " + sp.items + " xp " + sp.xp);
            sp.last = System.currentTimeMillis() - period() * 100_000;
            generate(sp);
            if (sp.stored() > getConfig().getLong("storage-per-spawner", 1728) * 2) fails.add("storage limit ignored: " + sp.stored());
            if (Bukkit.getPluginManager().getPlugin("DarkShop") != null) {
                double got = sellAll(owner, sp);
                if (got <= 0 || sp.items.containsKey(Material.BONE)) fails.add("sell all: " + got);
                call("DarkShop", "take", owner, got);
            }
            if (shardOffers().size() != types.values().stream().filter(t -> t.shardPrice() > 0).count()) fails.add("shard offers");
            spawners.clear();
            load();
            if (at(b) == null || at(b).stack != 2) fails.add("not saved");
            spawners.remove(where(b));
            save();
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            if (b != null && was != null) b.setType(was);
        }
        String r = fails.isEmpty() ? "DARKSPAWNERS TEST OK (" + types.size() + " kinds)" : "DARKSPAWNERS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1 && s.hasPermission("darkspawners.admin")) out.addAll(List.of("edit", "give", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3 && a[0].equalsIgnoreCase("give")) out.addAll(types.keySet());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
