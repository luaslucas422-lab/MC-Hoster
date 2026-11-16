package dev.darkerdawnfr.darkfishing;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

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
 * Dark Fishing by DarkerDawnFR. Fishing catches custom fish (a rarity, weight, length and price; sell them with /sell),
 * money and shard vouchers, crate keys and fishing books. With the Lava Line book you can fish in lava, with Void Line in
 * the void (in the End, or under any world). /fish shows your collection.
 */
public class DarkFishing extends JavaPlugin implements Listener, TabCompleter {

    public enum Rarity {
        COMMON("Common", "&7", 55), UNCOMMON("Uncommon", "&a", 25), RARE("Rare", "&9", 12), EPIC("Epic", "&5", 5.5),
        LEGENDARY("Legendary", "&6", 2), MYTHIC("Mythic", "&d", 0.5);
        public final String name, color;
        public final double weight;

        Rarity(String name, String color, double weight) {
            this.name = name;
            this.color = color;
            this.weight = weight;
        }
    }

    /** where = water, lava or void. price is per kg. */
    public record Fish(String id, String name, String where, Rarity rarity, Material icon, double minKg, double maxKg, double pricePerKg) {}

    public static final List<Fish> FISH = new ArrayList<>();

    static {
        // water
        f("sardine", "Sardine", "water", Rarity.COMMON, Material.COD, 0.1, 0.4, 20);
        f("perch", "Perch", "water", Rarity.COMMON, Material.COD, 0.3, 1.2, 18);
        f("trout", "Rainbow Trout", "water", Rarity.COMMON, Material.SALMON, 0.5, 2.5, 16);
        f("bass", "Sea Bass", "water", Rarity.UNCOMMON, Material.COD, 1, 5, 25);
        f("koi", "Golden Koi", "water", Rarity.UNCOMMON, Material.TROPICAL_FISH, 0.8, 4, 35);
        f("puffer", "Spiky Puffer", "water", Rarity.RARE, Material.PUFFERFISH, 0.5, 3, 80);
        f("moon_jelly", "Moon Jellyfish", "water", Rarity.RARE, Material.SLIME_BALL, 0.5, 5, 70);
        f("swordfish", "Swordfish", "water", Rarity.EPIC, Material.SALMON, 20, 90, 30);
        f("anglerfish", "Glow Anglerfish", "water", Rarity.EPIC, Material.GLOW_INK_SAC, 3, 15, 120);
        f("kraken", "Baby Kraken", "water", Rarity.LEGENDARY, Material.INK_SAC, 30, 150, 60);
        f("prism", "Prismarine Leviathan", "water", Rarity.MYTHIC, Material.PRISMARINE_CRYSTALS, 200, 600, 40);
        // lava
        f("ember_carp", "Ember Carp", "lava", Rarity.COMMON, Material.COOKED_COD, 0.5, 3, 40);
        f("magma_eel", "Magma Eel", "lava", Rarity.COMMON, Material.MAGMA_CREAM, 1, 4, 35);
        f("cinder_minnow", "Cinder Minnow", "lava", Rarity.UNCOMMON, Material.COOKED_SALMON, 0.1, 0.6, 140);
        f("blaze_ray", "Blaze Ray", "lava", Rarity.UNCOMMON, Material.BLAZE_ROD, 2, 8, 45);
        f("infernal_jelly", "Infernal Moon Jellyfish", "lava", Rarity.RARE, Material.MAGMA_BLOCK, 2, 6, 40);
        f("obsidian_pike", "Obsidian Pike", "lava", Rarity.EPIC, Material.OBSIDIAN, 10, 40, 60);
        f("phoenix_koi", "Phoenix Koi", "lava", Rarity.LEGENDARY, Material.FIRE_CHARGE, 5, 25, 400);
        f("netherite_shark", "Netherite Shark", "lava", Rarity.MYTHIC, Material.NETHERITE_SCRAP, 150, 500, 90);
        // void
        f("ender_eel", "Ender Eel", "void", Rarity.COMMON, Material.ENDER_PEARL, 1, 4, 60);
        f("chorus_cod", "Chorus Cod", "void", Rarity.COMMON, Material.CHORUS_FRUIT, 0.5, 3, 55);
        f("star_minnow", "Star Minnow", "void", Rarity.UNCOMMON, Material.NETHER_STAR, 0.1, 0.5, 400);
        f("shulker_snapper", "Shulker Snapper", "void", Rarity.RARE, Material.SHULKER_SHELL, 3, 12, 70);
        f("void_tuna", "Void Tuna", "void", Rarity.LEGENDARY, Material.COD, 60, 120, 120);
        f("dragonfish", "Dragonfish", "void", Rarity.EPIC, Material.DRAGON_BREATH, 8, 30, 90);
        f("cosmic_whale", "Cosmic Whale", "void", Rarity.MYTHIC, Material.END_CRYSTAL, 500, 1500, 30);
    }

    private static void f(String id, String name, String where, Rarity r, Material icon, double minKg, double maxKg, double pricePerKg) {
        FISH.add(new Fish(id, name, where, r, icon, minKg, maxKg, pricePerKg));
    }

    private NamespacedKey valueKey, fishKey, voucherKey, bookKey, lavaKey, voidKey;
    private File dataFile;
    private YamlConfiguration data;
    /** hooks fishing in lava or the void: hook -> state */
    private final Map<UUID, HookState> hooks = new HashMap<>();

    static final class HookState {
        final UUID player;
        String where; // lava or void ("" until the hook lands in one)
        long biteAt, biteUntil;
        boolean held;

        HookState(UUID player, String where) {
            this.player = player;
            this.where = where;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        valueKey = new NamespacedKey(this, "value");
        fishKey = new NamespacedKey(this, "fish");
        voucherKey = new NamespacedKey(this, "voucher");
        bookKey = new NamespacedKey(this, "book");
        lavaKey = new NamespacedKey(this, "lava");
        voidKey = new NamespacedKey(this, "void");
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("fish");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::hookTick, 2, 2);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void saveData() {
        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Couldn't save data.yml: " + e.getMessage());
        }
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&bFishing&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
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
                    if (t == int.class && a[i] instanceof Number n) a[i] = n.intValue();
                }
                return m.invoke(p, a);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ catching fish

    public static List<Fish> fishIn(String where) {
        List<Fish> out = new ArrayList<>();
        for (Fish f : FISH) if (f.where().equals(where)) out.add(f);
        return out;
    }

    /** A random fish from a place; luck (Luck of the Sea level) makes rare ones likelier. */
    public Fish pickFish(String where, int luck) {
        List<Fish> list = fishIn(where);
        double total = 0;
        double[] w = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Rarity r = list.get(i).rarity();
            w[i] = r.weight * (r.ordinal() >= 2 ? 1 + luck * 0.35 : 1);
            total += w[i];
        }
        double x = ThreadLocalRandom.current().nextDouble(total);
        for (int i = 0; i < list.size(); i++) {
            x -= w[i];
            if (x < 0) return list.get(i);
        }
        return list.get(list.size() - 1);
    }

    /** The caught fish as an item: rarity, weight, length and what it sells for. */
    public ItemStack fishItem(Fish f, boolean forceShiny) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double kg = f.minKg() + Math.pow(r.nextDouble(), 1.6) * (f.maxKg() - f.minKg());
        String mod = "";
        double mult = getConfig().getDouble("fish-price-multiplier", 1);
        double roll = r.nextDouble();
        if (forceShiny || roll < 0.04) {
            mod = "&b✦ Shiny ";
            mult *= 2;
        } else if (roll < 0.06) {
            mod = "&e⬆ Giant ";
            kg *= 2.5;
        } else if (f.where().equals("void") && roll < 0.16) {
            mod = "&d✦ Void-Touched ";
            mult *= 2;
        } else if (f.where().equals("lava") && roll < 0.16) {
            mod = "&c✦ Molten ";
            mult *= 1.8;
        }
        kg = Math.round(kg * 100) / 100.0;
        double cm = Math.round((Math.cbrt(kg) * 46 + r.nextDouble() * 6) * 100) / 100.0;
        double value = Math.round(kg * f.pricePerKg() * mult * 100) / 100.0;
        String title = mod + f.rarity().color + f.rarity().name + " &f" + f.name();
        ItemStack it = Util.item(f.icon(), 1, title, List.of("&7" + kg + " kg  &8·  &7" + cm + " cm",
                "&7Caught in: &f" + switch (f.where()) { case "lava" -> "&6lava"; case "void" -> "&5the void"; default -> "&bwater"; },
                "", "&aSells for &f" + Util.money(value) + " &8(/sell)"));
        ItemMeta m = it.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(valueKey, PersistentDataType.DOUBLE, value);
        pdc.set(fishKey, PersistentDataType.STRING, f.id() + ";" + kg + ";" + cm);
        try {
            if (f.rarity().ordinal() >= Rarity.EPIC.ordinal()) m.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
        }
        it.setItemMeta(m);
        return it;
    }

    public static double valueOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Double v = it.getItemMeta().getPersistentDataContainer().get(new NamespacedKey("darkfishing", "value"), PersistentDataType.DOUBLE);
        return v == null ? 0 : v;
    }

    /** What a catch gives: a fish most of the time, sometimes a voucher, a crate key or a book. */
    public ItemStack roll(String where, int luck) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double fish = getConfig().getDouble("chances.fish", 72), money = getConfig().getDouble("chances.money-voucher", 9),
                shards = getConfig().getDouble("chances.shard-voucher", 7), key = getConfig().getDouble("chances.crate-key", 4),
                book = getConfig().getDouble("chances.book", 2), plain = getConfig().getDouble("chances.normal-loot", 6);
        double x = r.nextDouble(fish + money + shards + key + book + plain);
        if ((x -= fish) < 0) return fishItem(pickFish(where, luck), false);
        if ((x -= money) < 0) return moneyVoucher(round(randBetween("vouchers.money", 500, 5000) * (where.equals("water") ? 1 : 2.5)));
        if ((x -= shards) < 0) return shardVoucher(Math.round(randBetween("vouchers.shards", 2, 15) * (where.equals("water") ? 1 : 2)));
        if ((x -= key) < 0) {
            ItemStack k = crateKey(where);
            if (k != null) return k;
        }
        if ((x -= book) < 0) return book(r.nextBoolean() ? "lava" : "void");
        return null; // the normal Minecraft catch
    }

    private static double round(double v) {
        return Math.round(v / 50.0) * 50.0;
    }

    private double randBetween(String path, double min, double max) {
        double a = getConfig().getDouble(path + "-min", min), b = getConfig().getDouble(path + "-max", max);
        return a + Math.pow(ThreadLocalRandom.current().nextDouble(), 2) * Math.max(0, b - a);
    }

    private ItemStack crateKey(String where) {
        Object crates = call("DarkCrates", "crates");
        if (!(crates instanceof Map<?, ?> map) || map.isEmpty()) return null;
        List<String> ids = new ArrayList<>();
        for (Object k : map.keySet()) ids.add(String.valueOf(k));
        String id = where.equals("water") ? (ids.contains("common") && ThreadLocalRandom.current().nextInt(3) > 0 ? "common" : ids.get(0))
                : ids.get(Math.min(ids.size() - 1, ThreadLocalRandom.current().nextInt(Math.max(1, ids.size()))));
        Object crate = map.get(id);
        Object key = crate == null ? null : call("DarkCrates", "key", crate, 1);
        return key instanceof ItemStack is ? is : null;
    }

    public ItemStack moneyVoucher(double amount) {
        ItemStack it = Util.item(Material.PAPER, 1, "&a&l" + Util.money(amount) + " Money Voucher",
                List.of("&7Right-click to get &a" + Util.money(amount) + "&7.", "&8Caught while fishing"));
        return tag(it, "money:" + amount);
    }

    public ItemStack shardVoucher(long amount) {
        ItemStack it = Util.item(Material.AMETHYST_SHARD, 1, "&d&l✦ " + amount + " Shard Voucher",
                List.of("&7Right-click to get &d" + amount + " shards&7.", "&8Caught while fishing"));
        return tag(it, "shards:" + amount);
    }

    private ItemStack tag(ItemStack it, String v) {
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(voucherKey, PersistentDataType.STRING, v);
        try {
            m.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
        }
        it.setItemMeta(m);
        return it;
    }

    /** A fishing book: put it on a fishing rod (click the book onto the rod in your inventory). */
    public ItemStack book(String type) {
        boolean lava = type.equals("lava");
        ItemStack it = Util.item(Material.ENCHANTED_BOOK, 1, lava ? "&6&lLava Line" : "&5&lVoid Line",
                List.of(lava ? "&7Your fishing rod can fish in &6lava&7." : "&7Your fishing rod can fish in &5the void&7.",
                        "", "&eClick this book onto a fishing rod", "&ein your inventory to add it."));
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, type);
        it.setItemMeta(m);
        return it;
    }

    public boolean hasLine(ItemStack rod, String type) {
        if (rod == null || rod.getType() != Material.FISHING_ROD || !rod.hasItemMeta()) return false;
        return rod.getItemMeta().getPersistentDataContainer().has(type.equals("lava") ? lavaKey : voidKey, PersistentDataType.BYTE);
    }

    /** Puts a fishing book on a rod. False if it already has it. */
    public boolean addLine(ItemStack rod, String type) {
        if (rod == null || rod.getType() != Material.FISHING_ROD || hasLine(rod, type)) return false;
        ItemMeta m = rod.getItemMeta();
        m.getPersistentDataContainer().set(type.equals("lava") ? lavaKey : voidKey, PersistentDataType.BYTE, (byte) 1);
        List<String> lore = m.hasLore() && m.getLore() != null ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        lore.add(Util.color(type.equals("lava") ? "&6Lava Line" : "&5Void Line"));
        m.setLore(lore);
        try {
            m.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
        }
        rod.setItemMeta(m);
        return true;
    }

    /** Tells the player and everyone (rare ones), and remembers new kinds for the collection. */
    private void announce(Player p, ItemStack it) {
        String id = it.hasItemMeta() ? it.getItemMeta().getPersistentDataContainer().get(fishKey, PersistentDataType.STRING) : null;
        String name = it.hasItemMeta() && it.getItemMeta().hasDisplayName() ? it.getItemMeta().getDisplayName() : Util.nice(it.getType());
        if (id == null) {
            p.sendTitle("", Util.color("&7You caught " + name), 5, 40, 10);
            say(p, "&7You caught " + name + "&7!");
            return;
        }
        String[] parts = id.split(";");
        Fish f = FISH.stream().filter(x -> x.id().equals(parts[0])).findFirst().orElse(null);
        if (f == null) return;
        double value = valueOf(it);
        p.sendTitle(Util.color(name), Util.color("&7" + parts[1] + " kg  &8·  &7" + parts[2] + " cm  &8·  &a" + Util.money(value)), 5, 50, 15);
        say(p, "&7You caught " + name + "&7! &a+" + Util.money(value) + " &8(sell it with /sell)");
        Sound s = f.rarity().ordinal() >= Rarity.LEGENDARY.ordinal() ? Sound.UI_TOAST_CHALLENGE_COMPLETE
                : f.rarity().ordinal() >= Rarity.RARE.ordinal() ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        p.playSound(p.getLocation(), s, 0.8f, 1.2f);
        String path = "found." + p.getUniqueId() + "." + f.id();
        double best = data.getDouble(path, -1);
        if (best < 0) say(p, "&eYou've discovered " + f.rarity().color + f.rarity().name + " &f" + f.name() + " &efor the first time!");
        if (Double.parseDouble(parts[1]) > best) data.set(path, Double.parseDouble(parts[1]));
        data.set("caught." + p.getUniqueId(), data.getInt("caught." + p.getUniqueId()) + 1);
        saveData();
        if (f.rarity().ordinal() >= Rarity.LEGENDARY.ordinal())
            Bukkit.broadcastMessage(prefix() + Util.color("&f" + p.getName() + " &7caught " + name + "&7! &8(" + Util.money(value) + ")"));
    }

    private int luck(ItemStack rod) {
        try {
            Enchantment luck = org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft("luck_of_the_sea"));
            return luck == null || rod == null ? 0 : rod.getEnchantmentLevel(luck);
        } catch (Throwable t) {
            return 0;
        }
    }

    private String place(FishHook h) {
        return "water";
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void fish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        ItemStack rod = p.getInventory().getItemInMainHand().getType() == Material.FISHING_ROD ? p.getInventory().getItemInMainHand()
                : p.getInventory().getItemInOffHand();
        FishHook hook = e.getHook();
        switch (e.getState()) {
            case FISHING -> { // cast: a lava or void rod is watched
                if (hasLine(rod, "lava") || hasLine(rod, "void")) hooks.put(hook.getUniqueId(), new HookState(p.getUniqueId(), ""));
            }
            case CAUGHT_FISH -> { // normal water fishing
                if (!(e.getCaught() instanceof Item item)) return;
                ItemStack got = roll(place(hook), luck(rod));
                if (got != null) item.setItemStack(got);
                announce(p, got != null ? got : item.getItemStack());
            }
            case REEL_IN, FAILED_ATTEMPT, IN_GROUND -> {
                HookState st = hooks.remove(hook.getUniqueId());
                if (st == null || st.where.isEmpty()) return;
                long now = System.currentTimeMillis();
                if (now < st.biteAt || now > st.biteUntil) {
                    if (now > st.biteUntil && st.biteUntil > 0) say(p, "&7Too slow, it got away!");
                    return;
                }
                ItemStack got = roll(st.where, luck(rod));
                if (got == null) got = fishItem(pickFish(st.where, luck(rod)), false);
                Location from = hook.getLocation();
                Item drop = p.getWorld().dropItem(from, got);
                Vector v = p.getLocation().toVector().subtract(from.toVector()).multiply(0.12).setY(0.45);
                drop.setVelocity(v);
                drop.setInvulnerable(true); // lava doesn't burn it
                p.giveExp(ThreadLocalRandom.current().nextInt(3, 9));
                announce(p, got);
            }
            default -> {
            }
        }
    }

    /** Lava and void fishing: the hook floats on lava / waits in the void, then a fish bites (be quick!). */
    private void hookTick() {
        long now = System.currentTimeMillis();
        hooks.entrySet().removeIf(en -> {
            org.bukkit.entity.Entity ent = Bukkit.getEntity(en.getKey());
            if (!(ent instanceof FishHook h) || !h.isValid()) return true;
            HookState st = en.getValue();
            Player p = Bukkit.getPlayer(st.player);
            if (p == null) return true;
            ItemStack rod = p.getInventory().getItemInMainHand();
            if (rod.getType() != Material.FISHING_ROD) rod = p.getInventory().getItemInOffHand();
            Location l = h.getLocation();
            World w = h.getWorld();
            if (st.where.isEmpty()) {
                Block b = l.getBlock();
                if (hasLine(rod, "lava") && (b.getType() == Material.LAVA || b.getRelative(0, -1, 0).getType() == Material.LAVA)) st.where = "lava";
                else if (hasLine(rod, "void") && l.getY() < w.getMinHeight() + 1) st.where = "void";
                else return false;
                st.biteAt = now + ThreadLocalRandom.current().nextLong(4000, 12000) - luck(rod) * 600L;
            }
            // keep the hook where it is (on top of the lava, or just under the world)
            h.setFireTicks(0);
            h.setInvulnerable(true);
            if (!st.held) {
                st.held = true;
                h.setGravity(false);
                Location hold = l.clone();
                if (st.where.equals("lava")) {
                    Block b = l.getBlock().getType() == Material.LAVA ? l.getBlock() : l.getBlock().getRelative(0, -1, 0);
                    while (b.getRelative(0, 1, 0).getType() == Material.LAVA) b = b.getRelative(0, 1, 0);
                    hold.setY(b.getY() + 0.9);
                } else hold.setY(w.getMinHeight() - 2);
                h.teleport(hold);
            }
            h.setVelocity(new Vector(0, 0, 0));
            if (st.where.equals("lava") && ThreadLocalRandom.current().nextInt(6) == 0)
                w.spawnParticle(Particle.LAVA, l, 1, 0.1, 0, 0.1, 0);
            if (st.where.equals("void") && ThreadLocalRandom.current().nextInt(4) == 0)
                w.spawnParticle(Particle.PORTAL, l, 4, 0.2, 0.2, 0.2, 0.1);
            if (st.biteUntil == 0 && now >= st.biteAt) { // a bite!
                st.biteUntil = now + 1600;
                w.playSound(l, st.where.equals("lava") ? Sound.BLOCK_LAVA_POP : Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
                w.spawnParticle(st.where.equals("lava") ? Particle.FLAME : Particle.REVERSE_PORTAL, l, 15, 0.2, 0.2, 0.2, 0.05);
                p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 1f);
                h.teleport(l.clone().add(0, -0.25, 0));
            } else if (st.biteUntil > 0 && now > st.biteUntil) { // missed it: wait for the next one
                st.biteUntil = 0;
                st.biteAt = now + ThreadLocalRandom.current().nextLong(4000, 10000);
                h.teleport(l.clone().add(0, 0.25, 0));
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ vouchers and books

    @EventHandler(priority = EventPriority.LOW)
    public void useVoucher(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String v = it.getItemMeta().getPersistentDataContainer().get(voucherKey, PersistentDataType.STRING);
        if (v == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String[] parts = v.split(":");
        double amount = Double.parseDouble(parts[1]);
        boolean money = parts[0].equals("money");
        Plugin need = Bukkit.getPluginManager().getPlugin(money ? "DarkShop" : "DarkShards");
        if (need == null || !need.isEnabled()) {
            say(p, "&cThis voucher needs " + (money ? "Dark Shop" : "Dark Shards") + " on the server.");
            return;
        }
        if (money) call("DarkShop", "give", p.getUniqueId(), amount);
        else call("DarkShards", "giveShards", p.getUniqueId(), (long) amount);
        it.setAmount(it.getAmount() - 1);
        if (e.getHand() == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(it.getAmount() > 0 ? it : null);
        say(p, parts[0].equals("money") ? "&a+" + Util.money(amount) + " &7from the voucher." : "&d+✦ " + (long) amount + " shards &7from the voucher.");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.5f);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void bookOnRod(InventoryClickEvent e) {
        ItemStack cursor = e.getCursor(), target = e.getCurrentItem();
        if (cursor == null || target == null || target.getType() != Material.FISHING_ROD || !cursor.hasItemMeta()) return;
        String type = cursor.getItemMeta().getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
        if (type == null) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!addLine(target, type)) {
            say(p, "&7That rod already has it.");
            return;
        }
        e.setCurrentItem(target);
        cursor.setAmount(cursor.getAmount() - 1);
        p.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
        say(p, "&aYour fishing rod can fish in " + (type.equals("lava") ? "&6lava" : "&5the void") + "&a now!");
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        hooks.values().removeIf(st -> st.player.equals(e.getPlayer().getUniqueId()));
    }

    // ------------------------------------------------------------------ /fish

    public Menu collection(Player p, String where) {
        List<Fish> list = fishIn(where);
        Menu m = new Menu(4, "&8" + Util.small("Fish - " + where));
        int slot = 10, found = 0;
        for (Fish f : list) {
            if (slot % 9 == 8) slot += 2;
            double best = data.getDouble("found." + p.getUniqueId() + "." + f.id(), -1);
            if (best >= 0) found++;
            m.set(slot++, best >= 0 ? Util.item(f.icon(), 1, f.rarity().color + f.rarity().name + " &f" + f.name(),
                    List.of("&7Biggest: &f" + best + " kg", "&7Worth about &a" + Util.money(f.pricePerKg() * (f.minKg() + f.maxKg()) / 2) + " &7each"))
                    : Util.item(Material.GRAY_DYE, "&8???", "&7" + f.rarity().color + f.rarity().name + " &7fish", "&7Not caught yet"));
        }
        String[][] tabs = {{"water", "WATER_BUCKET", "&bWater"}, {"lava", "LAVA_BUCKET", "&6Lava"}, {"void", "ENDER_EYE", "&5Void"}};
        for (int i = 0; i < 3; i++) {
            String w = tabs[i][0];
            m.set(29 + i * 2 - 1, Util.item(Material.valueOf(tabs[i][1]), (w.equals(where) ? "&l" : "") + tabs[i][2],
                    w.equals(where) ? "&7You're here" : "&eClick"), e -> collection(p, w).open(p));
        }
        m.set(4, Util.item(Material.FISHING_ROD, "&b&lYour fish", "&7Found here: &f" + found + "/" + list.size(),
                "&7Caught in total: &f" + data.getInt("caught." + p.getUniqueId()), "",
                "&7Lava and void fishing need the", "&6Lava Line &7and &5Void Line &7books."));
        m.fill();
        return m;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission("darkfishing.admin");
        if (sub.equals("test") && admin) {
            test(s);
            return true;
        }
        if (sub.equals("give") && admin) {
            if (a.length < 3) return say(s, "&7Usage: /fish give <player> <lava|void|money <amount>|shards <amount>|fish <id>|shiny>");
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) return say(s, "&cThat player isn't online.");
            ItemStack it = switch (a[2].toLowerCase(Locale.ROOT)) {
                case "lava", "void" -> book(a[2].toLowerCase(Locale.ROOT));
                case "money" -> moneyVoucher(a.length > 3 ? Util.parseMoney(a[3]) : 1000);
                case "shards" -> shardVoucher(a.length > 3 ? Long.parseLong(a[3]) : 10);
                case "fish" -> FISH.stream().filter(f -> a.length > 3 && f.id().equalsIgnoreCase(a[3])).findFirst().map(f -> fishItem(f, false)).orElse(null);
                case "shiny" -> fishItem(FISH.get(ThreadLocalRandom.current().nextInt(FISH.size())), true);
                default -> null;
            };
            if (it == null) return say(s, "&cDon't know that one.");
            for (ItemStack rest : t.getInventory().addItem(it).values()) t.getWorld().dropItemNaturally(t.getLocation(), rest);
            return say(s, "&7Gave it to &f" + t.getName() + "&7.");
        }
        if (sub.equals("reload") && admin) {
            reloadConfig();
            return say(s, "&aReloaded.");
        }
        if (!(s instanceof Player p)) return say(s, "&7/fish give|reload|test");
        collection(p, sub.equals("lava") || sub.equals("void") ? sub : "water").open(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("water", "lava", "void"));
            if (s.hasPermission("darkfishing.admin")) out.addAll(List.of("give", "reload"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3 && a[0].equalsIgnoreCase("give")) out.addAll(List.of("lava", "void", "money", "shards", "fish", "shiny"));
        else if (a.length == 4 && a[2].equalsIgnoreCase("fish")) for (Fish f : FISH) out.add(f.id());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ test

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        try {
            for (String w : List.of("water", "lava", "void")) {
                if (fishIn(w).size() < 6) fails.add(w + " has few fish");
                Map<Rarity, Integer> got = new LinkedHashMap<>();
                for (int i = 0; i < 3000; i++) got.merge(pickFish(w, 0).rarity(), 1, Integer::sum);
                if (got.getOrDefault(Rarity.COMMON, 0) <= got.getOrDefault(Rarity.RARE, 0)) fails.add(w + " rarities " + got);
            }
            ItemStack fish = fishItem(FISH.get(0), false);
            double v = valueOf(fish);
            if (v <= 0 || !fish.getItemMeta().getDisplayName().contains("Sardine")) fails.add("fish item " + v);
            ItemStack shiny = fishItem(FISH.get(0), true);
            if (!shiny.getItemMeta().getDisplayName().contains("Shiny")) fails.add("shiny");
            // /sell in Dark Shop pays the fish's own price
            Plugin shop = Bukkit.getPluginManager().getPlugin("DarkShop");
            if (shop != null) {
                Object sell = shop.getClass().getMethod("sellMenu").invoke(shop);
                double w = (double) sell.getClass().getMethod("worth", ItemStack.class).invoke(sell, fish);
                if (Math.abs(w - v) > 0.001) fails.add("/sell pays " + w + " not " + v);
            }
            ItemStack rod = new ItemStack(Material.FISHING_ROD);
            if (hasLine(rod, "lava") || !addLine(rod, "lava") || !hasLine(rod, "lava") || addLine(rod, "lava") || hasLine(rod, "void")) fails.add("lava line");
            if (!book("void").getItemMeta().getPersistentDataContainer().has(bookKey, PersistentDataType.STRING)) fails.add("book");
            ItemStack mv = moneyVoucher(2500);
            if (!"money:2500.0".equals(mv.getItemMeta().getPersistentDataContainer().get(voucherKey, PersistentDataType.STRING))) fails.add("money voucher");
            int fishes = 0, others = 0;
            for (int i = 0; i < 500; i++) {
                ItemStack r = roll("lava", 0);
                if (r != null && valueOf(r) > 0) fishes++;
                else others++;
            }
            if (fishes < 250 || others < 20) fails.add("catch mix " + fishes + "/" + others);
            if (Bukkit.getPluginManager().getPlugin("DarkCrates") != null && crateKey("water") == null) fails.add("crate key from Dark Crates");
        } catch (Throwable t) {
            fails.add(t.toString());
        }
        String r = fails.isEmpty() ? "DARKFISHING TEST OK (" + FISH.size() + " fish)" : "DARKFISHING TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }
}
