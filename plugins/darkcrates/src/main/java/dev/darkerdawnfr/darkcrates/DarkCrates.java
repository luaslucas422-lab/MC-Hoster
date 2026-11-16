package dev.darkerdawnfr.darkcrates;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dark Crates by DarkerDawnFR. Crates are chests, ender chests or shulker boxes you pick with /crate look set.
 * Right-click one with a key: "Use 1 key?" then a spinning roll from bad loot to great loot.
 * Keys come from the shard shop's Keys page and from the Key All every hour (shown on the scoreboard).
 */
public class DarkCrates extends JavaPlugin implements Listener, TabCompleter {
    public record Reward(String name, Material icon, Material item, int amount, double money, long shards, String command, double weight,
                         ItemStack stack, int index) {
        public Reward(String name, Material icon, Material item, int amount, double money, long shards, String command, double weight) {
            this(name, icon, item, amount, money, shards, command, weight, null, -1);
        }
    }

    public record Crate(String id, String name, String keyName, Material keyItem, long shardPrice, List<Reward> rewards, double total) {}

    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<String, String> placed = new HashMap<>(); // "world;x;y;z" -> crate id
    private final Map<UUID, Runnable> rolling = new HashMap<>();
    private NamespacedKey keyTag;
    private YamlConfiguration data;
    private File dataFile;
    private long nextKeyAll;
    private Ask ask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyTag = new NamespacedKey(this, "key");
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : data.getStringList("crates")) {
            int i = s.lastIndexOf(';');
            if (i > 0) placed.put(s.substring(0, i), s.substring(i + 1));
        }
        load();
        ask = new Ask(this);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("crate");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        nextKeyAll = data.getLong("next-key-all", 0);
        if (nextKeyAll < System.currentTimeMillis()) scheduleKeyAll();
        Bukkit.getScheduler().runTaskTimer(this, this::second, 20, 20);
        Bukkit.getScheduler().runTask(this, this::allHolograms);
    }

    @Override
    public void onDisable() {
        for (Runnable r : new ArrayList<>(rolling.values())) r.run();
        removeHolograms();
        data.set("next-key-all", nextKeyAll);
        saveData();
    }

    private void saveData() {
        List<String> l = new ArrayList<>();
        placed.forEach((k, v) -> l.add(k + ";" + v));
        data.set("crates", l);
        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e);
        }
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&6Crates&8] "));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
        return true;
    }

    // ------------------------------------------------------------------ crates and rewards

    public void load() {
        crates.clear();
        ConfigurationSection cs = getConfig().getConfigurationSection("crates");
        if (cs == null) return;
        for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null) continue;
            List<Reward> rs = new ArrayList<>();
            double total = 0;
            int index = -1;
            for (Map<?, ?> m : c.getMapList("rewards")) {
                index++;
                String item = str(m, "item");
                Material mat = null;
                int amount = 1;
                ItemStack stack = m.get("stack") instanceof ItemStack is && is.getType() != Material.AIR ? is.clone() : null;
                if (stack != null) {
                    mat = stack.getType();
                    amount = stack.getAmount();
                } else if (!item.isEmpty()) {
                    String[] p = item.split(":");
                    mat = Util.material(p[0]);
                    if (mat == null || !mat.isItem()) continue;
                    if (p.length > 1) amount = Integer.parseInt(p[1].trim());
                }
                double money = num(m, "money");
                long shards = (long) num(m, "shards");
                String command = str(m, "command");
                Material icon = Util.material(str(m, "icon"));
                if (icon == null) icon = mat != null ? mat : money > 0 ? Material.SUNFLOWER : shards > 0 ? Material.AMETHYST_SHARD : Material.PAPER;
                String name = str(m, "name");
                if (name.isEmpty() && stack != null && stack.hasItemMeta() && stack.getItemMeta().hasDisplayName())
                    name = stack.getItemMeta().getDisplayName() + (amount > 1 ? " &7x" + amount : "");
                if (name.isEmpty())
                    name = mat != null ? "&f" + amount + "x " + Util.nice(mat) : money > 0 ? "&a" + Util.money(money) : shards > 0 ? "&d✦ " + shards + " shards" : "&fA surprise";
                double weight = Math.max(0, num(m, "weight") == 0 ? 1 : num(m, "weight"));
                rs.add(new Reward(name, icon, mat, amount, money, shards, command, weight, stack, index));
                total += weight;
            }
            Material key = Util.material(c.getString("key-item", "TRIPWIRE_HOOK"));
            crates.put(id, new Crate(id, c.getString("name", id), c.getString("key-name", id + " Key"),
                    key == null ? Material.TRIPWIRE_HOOK : key, c.getLong("shard-price", 10), rs, total));
        }
    }

    private static String str(Map<?, ?> m, String k) {
        Object o = m.get(k);
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Map<?, ?> m, String k) {
        Object o = m.get(k);
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? 0 : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Map<String, Crate> crates() {
        return crates;
    }

    public Reward pick(Crate c) {
        if (c.rewards().isEmpty() || c.total() <= 0) return null;
        double r = ThreadLocalRandom.current().nextDouble(c.total());
        for (Reward x : c.rewards()) {
            r -= x.weight();
            if (r < 0) return x;
        }
        return c.rewards().get(c.rewards().size() - 1);
    }

    public double chance(Crate c, Reward r) {
        return c.total() <= 0 ? 0 : r.weight() * 100 / c.total();
    }

    public ItemStack icon(Crate c, Reward r, boolean withChance) {
        List<String> lore = new ArrayList<>();
        if (withChance) lore.add("&7Chance: &f" + String.format(Locale.ROOT, "%.1f", chance(c, r)) + "%");
        int amount = r.item() != null ? Math.max(1, Math.min(r.amount(), r.icon().getMaxStackSize())) : 1;
        if (r.stack() != null) {
            ItemStack it = r.stack().clone();
            it.setAmount(amount);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                List<String> l = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                for (String x : lore) l.add(Util.color(x));
                meta.setLore(l);
                it.setItemMeta(meta);
            }
            return it;
        }
        return Util.item(r.icon(), amount, r.name(), lore);
    }

    /** Gives a reward. Money and shards work for offline players too; items need them online. */
    public void give(UUID who, Crate c, Reward r) {
        Player p = Bukkit.getPlayer(who);
        if (r.stack() != null && p != null) {
            int left = r.amount();
            while (left > 0) {
                ItemStack it = r.stack().clone();
                int n = Math.min(left, it.getMaxStackSize());
                it.setAmount(n);
                for (ItemStack rest : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
                left -= n;
            }
        } else if (r.item() != null && p != null) {
            int left = r.amount();
            while (left > 0) {
                int n = Math.min(left, r.item().getMaxStackSize());
                for (ItemStack rest : p.getInventory().addItem(new ItemStack(r.item(), n)).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), rest);
                left -= n;
            }
        }
        if (r.money() > 0) call("DarkShop", "give", who, r.money());
        if (r.shards() > 0) call("DarkShards", "giveShards", who, r.shards());
        if (!r.command().isEmpty()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(who);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), r.command().replace("{player}", op.getName() == null ? "" : op.getName()));
        }
    }

    static Object call(String plugin, String method, Object... args) {
        Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        if (p == null || !p.isEnabled()) return null;
        for (Method m : p.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            try {
                Object[] a = args.clone();
                for (int i = 0; i < a.length; i++) {
                    Class<?> t = m.getParameterTypes()[i];
                    if (t == long.class && a[i] instanceof Number n) a[i] = n.longValue();
                    if (t == double.class && a[i] instanceof Number n) a[i] = n.doubleValue();
                }
                return m.invoke(p, a);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ keys

    public ItemStack key(Crate c, int amount) {
        ItemStack it = Util.item(c.keyItem(), amount, c.keyName(), List.of("&eRight-click to use it&7: it goes to your", c.name() + " &7(sneak: use them all).", "&7Crates are at the hubs."));
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, c.id());
        try {
            m.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
        }
        it.setItemMeta(m);
        return it;
    }

    public String keyId(ItemStack it) {
        if (it == null || !it.hasItemMeta() || it.getItemMeta() == null) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING);
    }

    /** Keys this player used (clicked) for this crate: the ones a crate can open with. */
    public int keys(Player p, Crate c) {
        return stored(p.getUniqueId(), c.id());
    }

    public int stored(UUID id, String crate) {
        return Math.max(0, data.getInt("keys." + id + "." + crate, 0));
    }

    public void addStored(UUID id, String crate, int n) {
        int v = Math.max(0, stored(id, crate) + n);
        data.set("keys." + id + "." + crate, v == 0 ? null : v);
        saveData();
    }

    /** Key items still in the inventory (not used yet). */
    public int unused(Player p, Crate c) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (c.id().equals(keyId(it))) n += it.getAmount();
        return n;
    }

    private boolean takeKey(Player p, Crate c) {
        if (stored(p.getUniqueId(), c.id()) <= 0) return false;
        addStored(p.getUniqueId(), c.id(), -1);
        return true;
    }

    /** Uses the key in that hand: it disappears and counts for its crate. all = the whole stack. Returns how many. */
    public int redeem(Player p, EquipmentSlot hand, boolean all) {
        ItemStack it = hand == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        String id = keyId(it);
        if (id == null) return 0;
        Crate c = crates.get(id);
        if (c == null) {
            say(p, "&cThis key's crate doesn't exist anymore.");
            return 0;
        }
        int n = all ? it.getAmount() : 1;
        if (it.getAmount() > n) it.setAmount(it.getAmount() - n);
        else if (hand == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(null);
        else p.getInventory().setItemInMainHand(null);
        addStored(p.getUniqueId(), id, n);
        say(p, "&a+" + n + " " + c.keyName() + "&a! &7You have &f" + stored(p.getUniqueId(), id) + " &7for the " + c.name() + "&7.");
        p.playSound(p.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 1f, 1.4f);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
        return n;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void useKey(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (keyId(e.getItem()) == null) return;
        e.setCancelled(true); // a key is never placed as a tripwire hook
        if (e.getClickedBlock() != null && crateAt(e.getClickedBlock()) != null) return; // the crate click does it
        redeem(e.getPlayer(), e.getHand() == null ? EquipmentSlot.HAND : e.getHand(), e.getPlayer().isSneaking());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void placeKey(org.bukkit.event.block.BlockPlaceEvent e) {
        if (keyId(e.getItemInHand()) != null) e.setCancelled(true);
    }

    public void giveKeys(Player p, Crate c, int n) {
        for (ItemStack rest : p.getInventory().addItem(key(c, n)).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    // Dark Shards' Crate Keys page: {id, item to show, price in shards}
    public List<Object[]> shardOffers() {
        List<Object[]> out = new ArrayList<>();
        for (Crate c : crates.values()) if (c.shardPrice() > 0) out.add(new Object[]{c.id(), key(c, 1), c.shardPrice()});
        return out;
    }

    public boolean buyOffer(Player p, String id, int amount) {
        Crate c = crates.get(id);
        if (c == null) return false;
        giveKeys(p, c, amount);
        return true;
    }

    public void giveKeys(Player p, String crate, int n) {
        Crate c = crates.get(crate);
        if (c != null) giveKeys(p, c, n);
    }

    // ------------------------------------------------------------------ placing crates

    private static String where(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public static boolean canBeCrate(Material m) {
        return m == Material.CHEST || m == Material.ENDER_CHEST || m == Material.TRAPPED_CHEST || m.name().endsWith("SHULKER_BOX");
    }

    public Crate crateAt(Block b) {
        String id = placed.get(where(b));
        return id == null ? null : crates.get(id);
    }

    public void setCrate(Block b, String id) {
        placed.put(where(b), id);
        saveData();
        hologram(b);
    }

    public boolean removeCrate(Block b) {
        boolean had = placed.remove(where(b)) != null;
        saveData();
        removeHolograms();
        allHolograms();
        return had;
    }

    private static final String HOLO = "darkcrates_holo";

    private void hologram(Block b) {
        Crate c = crateAt(b);
        if (c == null || !getConfig().getBoolean("holograms", true)) return;
        Location top = b.getLocation().add(0.5, 1.15, 0.5);
        String[] lines = {c.name(), "&7Right-click with a key", "&8Left-click to see the loot"};
        for (int i = 0; i < lines.length; i++) {
            Location l = top.clone().add(0, 0.6 - i * 0.27, 0);
            ArmorStand a = b.getWorld().spawn(l, ArmorStand.class);
            a.setVisible(false);
            a.setMarker(true);
            a.setGravity(false);
            a.setSmall(true);
            a.setInvulnerable(true);
            a.setPersistent(false);
            a.setCustomName(Util.color(lines[i]));
            a.setCustomNameVisible(true);
            a.addScoreboardTag(HOLO);
        }
    }

    private void removeHolograms() {
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntitiesByClass(ArmorStand.class)) if (e.getScoreboardTags().contains(HOLO)) e.remove();
    }

    private void allHolograms() {
        for (String k : placed.keySet()) {
            String[] p = k.split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) continue;
            int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[3]);
            if (w.isChunkLoaded(x >> 4, z >> 4)) hologram(w.getBlockAt(x, Integer.parseInt(p[2]), z));
        }
    }

    @EventHandler
    public void chunk(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        for (String k : placed.keySet()) {
            String[] p = k.split(";");
            if (!p[0].equals(c.getWorld().getName())) continue;
            int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[3]);
            if (x >> 4 == c.getX() && z >> 4 == c.getZ()) hologram(c.getWorld().getBlockAt(x, Integer.parseInt(p[2]), z));
        }
    }

    // ------------------------------------------------------------------ using a crate

    @EventHandler(priority = EventPriority.LOWEST)
    public void click(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Crate c = crateAt(e.getClickedBlock());
        if (c == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && keyId(e.getItem()) != null)
            redeem(p, e.getHand() == null ? EquipmentSlot.HAND : e.getHand(), p.isSneaking());
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) preview(c, null).open(p);
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) confirm(p, c, e.getClickedBlock().getLocation()).open(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void breakCrate(BlockBreakEvent e) {
        if (crateAt(e.getBlock()) == null) return;
        e.setCancelled(true);
        if (e.getPlayer().hasPermission("darkcrates.admin")) say(e.getPlayer(), "&7That's a crate. Look at it and type &f/crate look remove &7first.");
    }

    public Menu preview(Crate c, Runnable back) {
        int rows = Math.min(6, Math.max(3, (c.rewards().size() + 8) / 9 + 2));
        Menu m = new Menu(rows, "&8" + Util.small(org.bukkit.ChatColor.stripColor(Util.color(c.name()))) + " - loot");
        List<Reward> sorted = new ArrayList<>(c.rewards());
        sorted.sort((a, b) -> Double.compare(a.weight(), b.weight())); // rarest first
        int slot = 10;
        for (Reward r : sorted) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++;
            if (slot >= rows * 9 - 9) break;
            m.set(slot++, icon(c, r, true));
        }
        if (back != null) m.set(rows * 9 - 5, Util.item(Material.ARROW, "&fBack"), e -> back.run());
        m.border();
        return m;
    }

    public Menu confirm(Player p, Crate c, Location at) {
        int have = keys(p, c), unused = unused(p, c);
        Menu m = new Menu(3, "&8" + Util.small("Open " + org.bukkit.ChatColor.stripColor(Util.color(c.name())) + "?"));
        List<String> info = new ArrayList<>(List.of("&7You have &f" + have + " &7" + org.bukkit.ChatColor.stripColor(Util.color(c.keyName())) + (have == 1 ? "" : "s")));
        if (unused > 0) info.addAll(List.of("", "&e" + unused + " more in your inventory:", "&eright-click them to use them"));
        m.set(13, Util.item(Material.CHEST, 1, c.name(), info));
        if (c.rewards().isEmpty())
            m.set(11, Util.item(Material.GRAY_STAINED_GLASS_PANE, "&7This crate has no loot yet"));
        else if (have > 0)
            m.set(11, Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lUse 1 key", "&7Roll for loot"), e -> {
                if (rolling.containsKey(p.getUniqueId())) return;
                if (!takeKey(p, c)) {
                    say(p, "&cYou don't have a " + c.keyName() + "&c.");
                    p.closeInventory();
                    return;
                }
                roll(p, c, at);
            });
        else
            m.set(11, Util.item(Material.GRAY_STAINED_GLASS_PANE, "&7You have no keys", "&7Get them in &f/shards &7(Keys page)", "&7or from the &bKey All &7every hour.",
                    "&7Right-click a key to use it.", unused > 0 ? "&eYou have " + unused + " in your inventory!" : ""));
        m.set(15, Util.item(Material.RED_STAINED_GLASS_PANE, "&c&lCancel"), e -> p.closeInventory());
        m.set(22, Util.item(Material.BOOK, "&fSee the loot"), e -> preview(c, () -> confirm(p, c, at).open(p)).open(p));
        m.fill();
        return m;
    }

    private static final int STEPS = 34;

    /** The spinning row: random rewards with the real one landing in the middle on the last step. */
    public List<Reward> sequence(Crate c, Reward chosen) {
        List<Reward> seq = new ArrayList<>();
        for (int i = 0; i < STEPS + 9; i++) seq.add(pick(c));
        seq.set(STEPS - 1 + 4, chosen);
        return seq;
    }

    private void roll(Player p, Crate c, Location at) {
        Reward chosen = pick(c);
        if (chosen == null) {
            addStored(p.getUniqueId(), c.id(), 1); // give the key back
            p.closeInventory();
            return;
        }
        List<Reward> seq = sequence(c, chosen);
        Menu m = new Menu(3, "&8" + Util.small("Rolling..."));
        for (int i = 0; i < 9; i++) {
            m.set(i, Util.item(i == 4 ? Material.HOPPER : Material.PURPLE_STAINED_GLASS_PANE, " "));
            m.set(18 + i, Util.item(i == 4 ? Material.HOPPER : Material.PURPLE_STAINED_GLASS_PANE, " "));
        }
        m.open(p);
        boolean[] done = {false};
        BukkitTask[] task = {null};
        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            if (task[0] != null) task[0].cancel();
            rolling.remove(p.getUniqueId());
            give(p.getUniqueId(), c, chosen);
            if (p.isOnline()) {
                say(p, "&7You won " + chosen.name() + " &7from the " + c.name() + "&7!");
                p.playSound(p.getLocation(), chance(c, chosen) < 10 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            }
            if (at != null) at.getWorld().spawnParticle(Particle.END_ROD, at.clone().add(0.5, 1.2, 0.5), 30, 0.3, 0.4, 0.3, 0.05);
            if (chance(c, chosen) < getConfig().getDouble("announce-below-percent", 5))
                Bukkit.broadcastMessage(prefix() + Util.color("&f" + p.getName() + " &7won " + chosen.name() + " &7from the " + c.name()
                        + "&7! &8(" + String.format(Locale.ROOT, "%.1f", chance(c, chosen)) + "%)"));
        };
        rolling.put(p.getUniqueId(), finish);
        int[] step = {0};
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (done[0]) return;
            int k = step[0];
            for (int i = 0; i < 9; i++) m.getInventory().setItem(9 + i, icon(c, seq.get(k + i), false));
            if (p.isOnline()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f + k / 60f);
            if (k >= STEPS - 1) {
                finish.run();
                m.getInventory().setItem(4, Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lYou won!"));
                m.getInventory().setItem(22, Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lYou won!"));
                return;
            }
            step[0]++;
            long delay = 1 + (k * k) / 120;
            task[0] = Bukkit.getScheduler().runTaskLater(this, tick[0], delay);
        };
        tick[0].run();
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        Runnable r = rolling.remove(e.getPlayer().getUniqueId());
        if (r != null) r.run(); // still gets the loot
    }

    // ------------------------------------------------------------------ Key All

    private void scheduleKeyAll() {
        nextKeyAll = System.currentTimeMillis() + Math.max(1, getConfig().getLong("key-all.every-minutes", 60)) * 60_000L;
        data.set("next-key-all", nextKeyAll);
        saveData();
    }

    /** Time until the next Key All, like 34:12 (for the scoreboard: {keyall}). */
    public String keyAllIn() {
        if (!getConfig().getBoolean("key-all.enabled", true)) return "off";
        long s = Math.max(0, (nextKeyAll - System.currentTimeMillis()) / 1000);
        return s >= 3600 ? s / 3600 + ":" + String.format("%02d:%02d", (s / 60) % 60, s % 60) : (s / 60) + ":" + String.format("%02d", s % 60);
    }

    public Crate randomKeyAllCrate() {
        ConfigurationSection w = getConfig().getConfigurationSection("key-all.weights");
        double total = 0;
        Map<Crate, Double> weights = new LinkedHashMap<>();
        for (Crate c : crates.values()) {
            double x = w == null ? 1 : w.getDouble(c.id(), 0);
            if (x > 0) {
                weights.put(c, x);
                total += x;
            }
        }
        if (weights.isEmpty()) return crates.values().stream().findFirst().orElse(null);
        double r = ThreadLocalRandom.current().nextDouble(total);
        for (Map.Entry<Crate, Double> e : weights.entrySet()) {
            r -= e.getValue();
            if (r < 0) return e.getKey();
        }
        return weights.keySet().iterator().next();
    }

    public void keyAll() {
        Crate c = randomKeyAllCrate();
        if (c == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            giveKeys(p, c, 1);
            p.sendTitle(Util.color("&b&lKEY ALL"), Util.color("&7You got a " + c.keyName()), 10, 60, 20);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
        }
        Bukkit.broadcastMessage(prefix() + Util.color(getConfig().getString("key-all.message", "&b&lKEY ALL! &7Everyone online got a {key}&7!")
                .replace("{key}", c.keyName())));
    }

    private void second() {
        if (!getConfig().getBoolean("key-all.enabled", true)) return;
        long left = nextKeyAll - System.currentTimeMillis();
        if (left <= 60_000 && left > 59_000) Bukkit.broadcastMessage(prefix() + Util.color("&bKey All in 1 minute!"));
        if (left <= 0) {
            scheduleKeyAll();
            if (!Bukkit.getOnlinePlayers().isEmpty()) keyAll();
        }
    }

    // ------------------------------------------------------------------ the Keys page (opened from /shards)

    public void openKeyShop(Player p) {
        if (Bukkit.getPluginManager().getPlugin("DarkShards") != null) p.performCommand("shards keys");
        else keyShop(p).open(p);
    }

    public Menu keyShop(Player p) {
        Object bal = p == null ? null : call("DarkShards", "shards", p.getUniqueId());
        long shards = bal instanceof Number n ? n.longValue() : 0;
        boolean hasShards = Bukkit.getPluginManager().getPlugin("DarkShards") != null;
        Menu m = new Menu(3, "&8" + Util.small("Shard shop - keys"));
        m.set(4, Util.item(Material.AMETHYST_CLUSTER, "&d✦ " + shards + " shards", hasShards ? "&7Spend shards on crate keys" : "&cNeeds Dark Shards"));
        int count = crates.size(), start = 13 - Math.min(count, 7) / 2, i = 0;
        for (Crate c : crates.values()) {
            if (i >= 7) break;
            ItemStack show = key(c, 1);
            ItemMeta meta = show.getItemMeta();
            meta.setLore(List.of(Util.color("&7Price: &d✦ " + c.shardPrice() + " shards"), "",
                    Util.color("&eLeft-click: buy 1"), Util.color("&eRight-click: buy 5"), Util.color("&8Shift-click: see the loot")));
            show.setItemMeta(meta);
            m.set(start + i++, show, e -> {
                Player pl = (Player) e.getWhoClicked();
                if (e.isShiftClick()) {
                    preview(c, () -> keyShop(pl).open(pl)).open(pl);
                    return;
                }
                int n = e.isRightClick() ? 5 : 1;
                long cost = c.shardPrice() * n;
                if (!Boolean.TRUE.equals(call("DarkShards", "takeShards", pl.getUniqueId(), cost))) {
                    say(pl, "&cYou need &d✦ " + cost + " shards &cfor that.");
                    pl.playSound(pl.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                    return;
                }
                giveKeys(pl, c, n);
                say(pl, "&aYou bought " + n + "x " + c.keyName() + "&a.");
                pl.playSound(pl.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
                keyShop(pl).open(pl);
            });
        }
        m.set(18, Util.item(Material.ARROW, "&fBack to the shard shop"), e -> ((Player) e.getWhoClicked()).performCommand("shards"));
        m.fill();
        return m;
    }

    // ------------------------------------------------------------------ editing crates (/crate add, /crate edit)

    public static String crateId(String name) {
        return org.bukkit.ChatColor.stripColor(Util.color(name)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    /** Makes a new crate with no loot yet. Returns its id, or null with the reason in why[0]. */
    public String addCrate(String name, String[] why) {
        String id = crateId(name);
        if (id.isEmpty()) {
            why[0] = "Use letters or numbers in the name.";
            return null;
        }
        if (crates.containsKey(id)) {
            why[0] = "There's already a crate called " + id + ".";
            return null;
        }
        String plain = org.bukkit.ChatColor.stripColor(Util.color(name)).trim();
        String shown = name.contains("&") ? name : "&e&l" + plain + (plain.toLowerCase(Locale.ROOT).endsWith("crate") ? "" : " Crate");
        String base = "crates." + id + ".";
        getConfig().set(base + "name", shown);
        getConfig().set(base + "key-name", "&e" + plain.replaceAll("(?i)\\s*crate$", "") + " Key");
        getConfig().set(base + "key-item", "TRIPWIRE_HOOK");
        getConfig().set(base + "shard-price", 50);
        getConfig().set(base + "rewards", new ArrayList<>());
        saveConfig();
        load();
        return id;
    }

    public void deleteCrate(String id) {
        getConfig().set("crates." + id, null);
        getConfig().set("key-all.weights." + id, null);
        saveConfig();
        placed.values().removeIf(v -> v.equals(id));
        saveData();
        load();
        removeHolograms();
        allHolograms();
    }

    private List<Map<String, Object>> rewardMaps(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> m : getConfig().getMapList("crates." + id + ".rewards")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            out.add(copy);
        }
        return out;
    }

    private void saveRewards(String id, List<Map<String, Object>> list) {
        getConfig().set("crates." + id + ".rewards", list);
        saveConfig();
        load();
    }

    /** Adds an item as loot: plain items are written as "NAME:amount", named/enchanted ones are kept whole. */
    public void addItemReward(String id, ItemStack it, double weight) {
        List<Map<String, Object>> l = rewardMaps(id);
        Map<String, Object> m = new LinkedHashMap<>();
        ItemStack plain = new ItemStack(it.getType(), it.getAmount());
        if (it.isSimilar(plain)) m.put("item", it.getType().name() + ":" + it.getAmount());
        else m.put("stack", it.clone());
        m.put("weight", weight);
        l.add(m);
        saveRewards(id, l);
    }

    public void addReward(String id, String type, double amount, double weight) {
        List<Map<String, Object>> l = rewardMaps(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(type, type.equals("shards") ? (Object) (long) amount : (Object) amount);
        m.put("weight", weight);
        l.add(m);
        saveRewards(id, l);
    }

    public void setRewardWeight(String id, int index, double weight) {
        List<Map<String, Object>> l = rewardMaps(id);
        if (index < 0 || index >= l.size()) return;
        l.get(index).put("weight", weight);
        saveRewards(id, l);
    }

    public void removeReward(String id, int index) {
        List<Map<String, Object>> l = rewardMaps(id);
        if (index < 0 || index >= l.size()) return;
        l.remove(index);
        saveRewards(id, l);
    }

    private static double number(String t) {
        try {
            return Util.parseMoney(t.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    public Menu editor(Player p) {
        int rows = Math.min(6, Math.max(3, (crates.size() + 8) / 9 + 2));
        Menu m = new Menu(rows, "&8" + Util.small("Crate editor"));
        int slot = 10;
        for (Crate c : crates.values()) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++;
            if (slot >= rows * 9 - 9) break;
            m.set(slot++, Util.item(Material.CHEST, 1, c.name(), List.of("&8id: " + c.id(), "&7Loot: &f" + c.rewards().size(),
                    "&7Key price: &d✦ " + c.shardPrice() + " shards", "", "&eClick to edit")), e -> crateEditor(p, c.id()).open(p));
        }
        m.set(rows * 9 - 5, Util.item(Material.LIME_DYE, "&a&lAdd a crate", "&7Or: /crate add <name>"), e ->
                ask.ask(p, "the new crate's name", t -> {
                    String[] why = {""};
                    String id = addCrate(t.trim(), why);
                    if (id == null) say(p, "&c" + why[0]);
                    else {
                        say(p, "&aMade the " + crates.get(id).name() + "&a. Add loot by clicking items in your inventory.");
                        crateEditor(p, id).open(p);
                    }
                }, null));
        m.fill();
        return m;
    }

    public Menu crateEditor(Player p, String id) {
        Crate c = crates.get(id);
        if (c == null) return editor(p);
        Menu m = new Menu(6, "&8" + Util.small("Edit") + " " + org.bukkit.ChatColor.stripColor(Util.color(c.name())));
        int slot = 0;
        for (Reward rw : c.rewards()) {
            if (slot >= 45) break;
            ItemStack show = icon(c, rw, true);
            ItemMeta meta = show.getItemMeta();
            if (meta != null) {
                List<String> l = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                l.add(Util.color("&7Weight: &f" + (rw.weight() == Math.floor(rw.weight()) ? String.valueOf((long) rw.weight()) : String.valueOf(rw.weight()))));
                l.add("");
                l.add(Util.color("&eLeft-click: change the chance"));
                l.add(Util.color("&cShift + right-click: remove"));
                meta.setLore(l);
                show.setItemMeta(meta);
            }
            int idx = rw.index();
            m.set(slot++, show, e -> {
                if (e.isShiftClick() && e.isRightClick()) {
                    removeReward(id, idx);
                    crateEditor(p, id).open(p);
                } else if (e.isLeftClick())
                    ask.ask(p, "the weight (bigger = more often, now " + rw.weight() + ")", t -> {
                        double w = number(t);
                        if (w <= 0) say(p, "&cThat isn't a number above 0.");
                        else setRewardWeight(id, idx, w);
                    }, () -> crateEditor(p, id).open(p));
            });
        }
        m.onBottomClick = e -> {
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType() == Material.AIR) return;
            addItemReward(id, it, 10);
            say(p, "&aAdded " + (it.hasItemMeta() && it.getItemMeta().hasDisplayName() ? it.getItemMeta().getDisplayName() : it.getAmount() + "x " + Util.nice(it.getType()))
                    + " &ato the loot (weight 10, left-click it to change). You keep your item.");
            crateEditor(p, id).open(p);
        };
        m.set(45, Util.item(Material.ARROW, "&fBack"), e -> editor(p).open(p));
        m.set(46, Util.item(Material.NAME_TAG, "&eRename the crate", "&7Now: " + c.name(), "&7& colours work"), e ->
                ask.ask(p, "the crate's new name", t -> setCfg(id, "name", t.trim()), () -> crateEditor(p, id).open(p)));
        m.set(47, Util.item(c.keyItem(), "&eRename the key", "&7Now: " + c.keyName()), e ->
                ask.ask(p, "the key's new name", t -> setCfg(id, "key-name", t.trim()), () -> crateEditor(p, id).open(p)));
        m.set(48, Util.item(Material.AMETHYST_SHARD, "&eKey price in /shards", "&7Now: &d✦ " + c.shardPrice(), "&70 = not sold"), e ->
                ask.ask(p, "the price in shards", t -> {
                    double v = number(t);
                    if (v < 0) say(p, "&cThat isn't a number.");
                    else setCfg(id, "shard-price", (long) v);
                }, () -> crateEditor(p, id).open(p)));
        m.set(49, Util.item(Material.BOOK, "&fAdd loot", "&7Click any item in &fyour inventory", "&7to add it (name, enchants and all).",
                "&7Or use the buttons for money / shards."));
        m.set(50, Util.item(Material.SUNFLOWER, "&aAdd money", "&7Needs Dark Shop"), e ->
                ask.ask(p, "the amount of money (like 5000 or 2.5k)", t -> {
                    double v = number(t);
                    if (v <= 0) say(p, "&cThat isn't an amount.");
                    else addReward(id, "money", v, 10);
                }, () -> crateEditor(p, id).open(p)));
        m.set(51, Util.item(Material.AMETHYST_CLUSTER, "&dAdd shards", "&7Needs Dark Shards"), e ->
                ask.ask(p, "the number of shards", t -> {
                    double v = number(t);
                    if (v <= 0) say(p, "&cThat isn't a number.");
                    else addReward(id, "shards", Math.round(v), 10);
                }, () -> crateEditor(p, id).open(p)));
        double kw = getConfig().getDouble("key-all.weights." + id, 0);
        m.set(52, Util.item(Material.CLOCK, "&bKey All weight", "&7Now: &f" + kw + " &8(0 = never in the Key All)"), e ->
                ask.ask(p, "the Key All weight", t -> {
                    double v = number(t);
                    if (v < 0) say(p, "&cThat isn't a number.");
                    else {
                        getConfig().set("key-all.weights." + id, v);
                        saveConfig();
                    }
                }, () -> crateEditor(p, id).open(p)));
        m.set(53, Util.item(Material.BARRIER, "&cDelete this crate", "&7Shift-click to delete it for good"), e -> {
            if (!e.isShiftClick()) return;
            deleteCrate(id);
            say(p, "&7Crate deleted.");
            editor(p).open(p);
        });
        for (int i = 45; i < 54; i++) if (m.getInventory().getItem(i) == null) m.set(i, Util.glass());
        return m;
    }

    private void setCfg(String id, String key, Object value) {
        getConfig().set("crates." + id + "." + key, value);
        saveConfig();
        load();
        removeHolograms();
        allHolograms();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission("darkcrates.admin");
        if (label.equalsIgnoreCase("keyall") && a.length == 0) return say(s, "&7Next &bKey All &7in &b" + keyAllIn() + "&7.");
        switch (sub) {
            case "look" -> {
                if (!admin) return say(s, "&cOnly ops can place crates.");
                if (!(s instanceof Player p)) return true;
                Block b = p.getTargetBlockExact(6);
                if (b == null) return say(p, "&cLook at a chest, ender chest or shulker box (closer than 6 blocks).");
                String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (what.equals("remove")) {
                    return say(p, removeCrate(b) ? "&7Crate removed." : "&7That isn't a crate.");
                }
                if (!what.equals("set") || a.length < 3) return say(p, "&7Usage: /crate look set <" + String.join("|", crates.keySet()) + ">  or  /crate look remove");
                Crate c = crates.get(a[2].toLowerCase(Locale.ROOT));
                if (c == null) return say(p, "&cNo crate called " + a[2] + ". Crates: " + String.join(", ", crates.keySet()));
                if (!canBeCrate(b.getType())) return say(p, "&cA crate has to be a chest, ender chest or shulker box. That's " + Util.nice(b.getType()) + ".");
                if (crateAt(b) != null) removeCrate(b);
                setCrate(b, c.id());
                say(p, "&aThis " + Util.nice(b.getType()) + " is now a " + c.name() + "&a.");
            }
            case "add", "create" -> {
                if (!admin) return say(s, "&cOnly ops can make crates.");
                if (a.length < 2) return say(s, "&7Usage: /crate add <name>");
                String[] why = {""};
                String id = addCrate(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)), why);
                if (id == null) return say(s, "&c" + why[0]);
                say(s, "&aMade the " + crates.get(id).name() + " &a(id " + id + "). Add its loot in &f/crate edit " + id
                        + "&a, then place it with &f/crate look set " + id + "&a.");
                if (s instanceof Player p) crateEditor(p, id).open(p);
            }
            case "edit", "editor" -> {
                if (!admin) return say(s, "&cOnly ops can edit crates.");
                if (!(s instanceof Player p)) return true;
                Crate c = a.length > 1 ? crates.get(a[1].toLowerCase(Locale.ROOT)) : null;
                (c == null ? editor(p) : crateEditor(p, c.id())).open(p);
            }
            case "delete", "remove" -> {
                if (!admin) return true;
                Crate c = a.length > 1 ? crates.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (c == null) return say(s, "&7Usage: /crate delete <" + String.join("|", crates.keySet()) + ">");
                deleteCrate(c.id());
                say(s, "&7Deleted " + c.name() + "&7.");
            }
            case "give" -> {
                if (!admin) return true;
                if (a.length < 3) return say(s, "&7Usage: /crate give <player> <crate> [amount]");
                Player t = Bukkit.getPlayerExact(a[1]);
                Crate c = crates.get(a[2].toLowerCase(Locale.ROOT));
                if (t == null || c == null) return say(s, "&cPlayer not online or no crate called " + a[2] + ".");
                int n = a.length > 3 ? Math.max(1, Integer.parseInt(a[3])) : 1;
                giveKeys(t, c, n);
                say(s, "&7Gave &f" + t.getName() + " " + n + "x " + c.keyName() + "&7.");
            }
            case "giveall" -> {
                if (!admin) return true;
                Crate c = a.length > 1 ? crates.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (c == null) return say(s, "&7Usage: /crate giveall <crate> [amount]");
                int n = a.length > 2 ? Math.max(1, Integer.parseInt(a[2])) : 1;
                for (Player p : Bukkit.getOnlinePlayers()) giveKeys(p, c, n);
                Bukkit.broadcastMessage(prefix() + Util.color("&7Everyone got " + n + "x " + c.keyName() + "&7!"));
            }
            case "keyall" -> {
                if (!admin) return say(s, "&7Next Key All in &b" + keyAllIn() + "&7.");
                keyAll();
                scheduleKeyAll();
            }
            case "preview" -> {
                Crate c = a.length > 1 ? crates.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (c == null || !(s instanceof Player p)) return say(s, "&7Usage: /crate preview <" + String.join("|", crates.keySet()) + ">");
                preview(c, null).open(p);
            }
            case "keys" -> {
                if (s instanceof Player p) openKeyShop(p);
            }
            case "reload" -> {
                if (!admin) return true;
                reloadConfig();
                load();
                removeHolograms();
                allHolograms();
                say(s, "&aReloaded. Crates: " + crates.keySet());
            }
            case "test" -> {
                if (admin) test(s);
            }
            default -> {
                say(s, "&fCrates: " + String.join("&7, ", crates.values().stream().map(Crate::name).toList()));
                say(s, "&7Key All in &b" + keyAllIn() + "&7. Buy keys in &f/shards&7 (Keys page). Crates are at the hubs.");
                if (s instanceof Player p) {
                    List<String> mine = new ArrayList<>();
                    for (Crate c : crates.values()) if (keys(p, c) > 0) mine.add(c.keyName() + " &7x" + keys(p, c));
                    say(s, "&7Your keys: " + (mine.isEmpty() ? "&fnone &8(right-click a key item to use it)" : String.join("&7, ", mine)));
                }
                if (admin) say(s, "&8/crate add <name> | edit [crate] | delete <crate> | look set <crate> | look remove | give <player> <crate> [n] | giveall <crate> | keyall | preview <crate> | reload");
            }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID fake = UUID.nameUUIDFromBytes("darkcrates-test".getBytes());
        Block b = null;
        Material was = null;
        try {
            for (String need : List.of("common", "rare", "epic", "legendary"))
                if (!crates.containsKey(need) || crates.get(need).rewards().size() < 5) fails.add("crate " + need);
            Crate common = crates.get("common");
            if (!"common".equals(keyId(key(common, 1)))) fails.add("key not recognised");
            Map<Reward, Integer> counts = new HashMap<>();
            for (int i = 0; i < 4000; i++) counts.merge(pick(common), 1, Integer::sum);
            Reward most = common.rewards().stream().max((x, y) -> Double.compare(x.weight(), y.weight())).orElseThrow();
            Reward least = common.rewards().stream().min((x, y) -> Double.compare(x.weight(), y.weight())).orElseThrow();
            if (counts.getOrDefault(most, 0) <= counts.getOrDefault(least, 0)) fails.add("weights ignored " + counts.values());
            Reward chosen = common.rewards().get(2);
            if (sequence(common, chosen).get(STEPS - 1 + 4) != chosen) fails.add("roll doesn't land on the prize");
            preview(common, null);
            keyShop(null);
            if (!keyAllIn().matches("\\d+:\\d\\d(:\\d\\d)?")) fails.add("key all timer " + keyAllIn());
            if (randomKeyAllCrate() == null) fails.add("key all crate");
            // place a crate on a real chest
            World w = Bukkit.getWorlds().get(0);
            Location l = w.getSpawnLocation().clone().add(3, 0, 3);
            b = w.getHighestBlockAt(l).getRelative(0, 1, 0);
            was = b.getType();
            b.setType(Material.ENDER_CHEST);
            setCrate(b, "rare");
            if (crateAt(b) == null || !crateAt(b).id().equals("rare")) fails.add("crate not placed");
            long holos = w.getNearbyEntities(b.getLocation(), 3, 3, 3).stream().filter(e -> e.getScoreboardTags().contains(HOLO)).count();
            if (holos < 1) fails.add("no hologram");
            if (!removeCrate(b) || crateAt(b) != null) fails.add("crate not removed");
            // keys are used (clicked) and then counted for their crate
            UUID kid = UUID.nameUUIDFromBytes("darkcrates-keys".getBytes());
            addStored(kid, "rare", 3);
            addStored(kid, "rare", -1);
            if (stored(kid, "rare") != 2 || stored(kid, "epic") != 0) fails.add("stored keys " + stored(kid, "rare"));
            addStored(kid, "rare", -5);
            if (stored(kid, "rare") != 0) fails.add("keys below 0");
            // /crate add, loot added in the editor (a named item is kept whole), then delete
            String[] why = {""};
            String nid = addCrate("Test Mythic", why);
            if (!"testmythic".equals(nid) || !crates.get(nid).name().contains("Test Mythic Crate")) fails.add("crate add " + nid + " " + why[0]);
            if (addCrate("Test Mythic", why) != null) fails.add("same crate twice");
            if (nid != null) {
                if (pick(crates.get(nid)) != null) fails.add("empty crate gave loot");
                ItemStack named = new ItemStack(Material.DIAMOND_SWORD);
                ItemMeta nm = named.getItemMeta();
                nm.setDisplayName("Test Blade");
                named.setItemMeta(nm);
                addItemReward(nid, named, 5);
                addItemReward(nid, new ItemStack(Material.COBBLESTONE, 32), 20);
                addReward(nid, "money", 1500, 10);
                reloadConfig();
                load();
                Crate t = crates.get(nid);
                if (t == null || t.rewards().size() != 3) fails.add("editor loot not saved");
                else {
                    Reward blade = t.rewards().get(0);
                    if (blade.stack() == null || !"Test Blade".equals(blade.stack().getItemMeta().getDisplayName())) fails.add("named item not kept");
                    if (t.rewards().get(1).item() != Material.COBBLESTONE || t.rewards().get(1).amount() != 32) fails.add("plain item");
                    setRewardWeight(nid, 1, 99);
                    removeReward(nid, 0);
                    t = crates.get(nid);
                    if (t.rewards().size() != 2 || t.rewards().get(0).weight() != 99) fails.add("change / remove loot");
                }
                deleteCrate(nid);
                if (crates.containsKey(nid) || getConfig().isSet("crates." + nid)) fails.add("crate delete");
            }
            // money and shards rewards work for offline players
            Object before = call("DarkShards", "shards", fake);
            give(fake, common, new Reward("t", Material.PAPER, null, 1, 0, 7, "", 1));
            Object after = call("DarkShards", "shards", fake);
            if (before instanceof Number x && (!(after instanceof Number y) || y.longValue() - x.longValue() != 7)) fails.add("shards reward");
            call("DarkShards", "takeShards", fake, 7L);
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            if (b != null && was != null) b.setType(was);
        }
        String r = fails.isEmpty() ? "DARKCRATES TEST OK (crates: " + crates.keySet() + ", key all in " + keyAllIn() + ")" : "DARKCRATES TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("darkcrates.admin");
        if (a.length == 1) {
            out.addAll(List.of("preview", "keys"));
            if (admin) out.addAll(List.of("add", "edit", "delete", "look", "give", "giveall", "keyall", "reload"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("look")) out.addAll(List.of("set", "remove"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if ((a.length == 3 && (a[0].equalsIgnoreCase("look") || a[0].equalsIgnoreCase("give")))
                || (a.length == 2 && (a[0].equalsIgnoreCase("preview") || a[0].equalsIgnoreCase("giveall") || a[0].equalsIgnoreCase("edit")
                || a[0].equalsIgnoreCase("delete")))) out.addAll(crates.keySet());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
