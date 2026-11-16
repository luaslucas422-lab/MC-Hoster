package dev.darkerdawnfr.darkah;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Every listing, plus the items people can collect (expired, removed, or bought while offline). Saved in data.yml. */
public class AuctionHouse {
    public static final class Listing {
        public final UUID id, seller;
        public final String sellerName;
        public final ItemStack item;
        public final double price;
        public final long listed;
        public long expires;

        Listing(UUID id, UUID seller, String sellerName, ItemStack item, double price, long listed, long expires) {
            this.id = id;
            this.seller = seller;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
            this.listed = listed;
            this.expires = expires;
        }
    }

    public enum Sort {
        HIGHEST("Highest Price"), LOWEST("Lowest Price"), RECENT("Recently Listed"), OLDEST("Last Listed");
        public final String label;
        Sort(String l) { label = l; }
    }

    public enum Filter {
        ALL("All"), BLOCKS("Blocks"), TOOLS("Tools"), FOOD("Food"), COMBAT("Combat"), POTIONS("Potions"),
        BOOKS("Books"), INGREDIENTS("Ingredients"), UTILITIES("Utilities");
        public final String label;
        Filter(String l) { label = l; }
    }

    private final DarkAH plugin;
    private final File file;
    private final Map<UUID, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> claims = new HashMap<>();
    /** unit prices of the last sales of each item */
    private final Map<Material, List<Double>> sales = new HashMap<>();

    public AuctionHouse(DarkAH plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    // ------------------------------------------------------------------ queries

    public List<Listing> all() {
        return new ArrayList<>(listings.values());
    }

    public Listing get(UUID id) {
        return listings.get(id);
    }

    public List<Listing> of(UUID seller) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values()) if (l.seller.equals(seller)) out.add(l);
        return out;
    }

    public List<ItemStack> claims(UUID who) {
        return claims.computeIfAbsent(who, k -> new ArrayList<>());
    }

    public List<Listing> browse(Sort sort, Filter filter, String search) {
        String q = search == null ? "" : search.toLowerCase(Locale.ROOT).trim();
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (filter != Filter.ALL && category(l.item.getType()) != filter) continue;
            if (!q.isEmpty() && !searchText(l).contains(q)) continue;
            out.add(l);
        }
        switch (sort) {
            case HIGHEST -> out.sort((a, b) -> Double.compare(b.price, a.price));
            case LOWEST -> out.sort((a, b) -> Double.compare(a.price, b.price));
            case RECENT -> out.sort((a, b) -> Long.compare(b.listed, a.listed));
            case OLDEST -> out.sort((a, b) -> Long.compare(a.listed, b.listed));
        }
        return out;
    }

    private static String searchText(Listing l) {
        StringBuilder b = new StringBuilder(Util.nice(l.item.getType()).toLowerCase(Locale.ROOT));
        b.append(' ').append(l.item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        b.append(' ').append(l.sellerName.toLowerCase(Locale.ROOT));
        if (l.item.hasItemMeta() && l.item.getItemMeta() != null && l.item.getItemMeta().hasDisplayName())
            b.append(' ').append(org.bukkit.ChatColor.stripColor(l.item.getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT));
        return b.toString();
    }

    public static Filter category(Material m) {
        String n = m.name();
        if (n.endsWith("POTION") || n.equals("TIPPED_ARROW")) return Filter.POTIONS;
        if (n.endsWith("BOOK")) return Filter.BOOKS;
        if (n.endsWith("_SWORD") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("SHIELD")
                || n.equals("MACE") || n.equals("TOTEM_OF_UNDYING") || n.equals("END_CRYSTAL") || n.endsWith("ARROW")
                || n.endsWith("_SPEAR")) return Filter.COMBAT;
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS")
                || n.equals("FLINT_AND_STEEL") || n.equals("FISHING_ROD") || n.equals("BRUSH")) return Filter.TOOLS;
        if (m.isEdible()) return Filter.FOOD;
        if (n.equals("ENDER_PEARL") || n.equals("ENDER_EYE") || n.equals("ELYTRA") || n.equals("FIREWORK_ROCKET")
                || n.endsWith("BUCKET") || n.equals("COMPASS") || n.equals("RECOVERY_COMPASS") || n.equals("CLOCK")
                || n.equals("MAP") || n.equals("FILLED_MAP") || n.equals("LEAD") || n.equals("NAME_TAG") || n.equals("SADDLE")
                || n.equals("EXPERIENCE_BOTTLE") || n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.endsWith("MINECART")
                || n.equals("SPYGLASS") || n.endsWith("_BUNDLE") || n.equals("BUNDLE")) return Filter.UTILITIES;
        if (m.isBlock()) return Filter.BLOCKS;
        return Filter.INGREDIENTS;
    }

    // ------------------------------------------------------------------ changes

    public Listing list(UUID seller, String name, ItemStack item, double price) {
        long now = System.currentTimeMillis();
        long hours = Math.max(1, plugin.getConfig().getLong("listing-hours", 48));
        Listing l = new Listing(UUID.randomUUID(), seller, name, item.clone(), price, now, now + hours * 3_600_000L);
        listings.put(l.id, l);
        save();
        return l;
    }

    /** The seller takes it back: into their inventory, or their collect list if it's full / they're offline. */
    public boolean cancel(Listing l) {
        if (listings.remove(l.id) == null) return false;
        deliver(l.seller, l.item);
        save();
        return true;
    }

    public enum Result { OK, GONE, OWN, NO_ECONOMY, NO_MONEY }

    public Result buy(UUID buyer, Listing l) {
        if (!listings.containsKey(l.id)) return Result.GONE;
        if (l.seller.equals(buyer)) return Result.OWN;
        Eco eco = plugin.eco();
        if (!eco.ready()) return Result.NO_ECONOMY;
        if (!eco.take(buyer, l.price)) return Result.NO_MONEY;
        listings.remove(l.id);
        recordSale(l.item.getType(), l.price / Math.max(1, l.item.getAmount()));
        double tax = Math.max(0, Math.min(100, plugin.getConfig().getDouble("tax-percent", 0)));
        double paid = Math.round(l.price * (100 - tax)) / 100.0;
        eco.give(l.seller, paid);
        deliver(buyer, l.item);
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            Player b = Bukkit.getPlayer(buyer);
            seller.sendMessage(plugin.msg("sold").replace("{buyer}", b != null ? b.getName() : "Someone")
                    .replace("{item}", DarkAH.itemName(l.item)).replace("{price}", Util.money(paid)));
        }
        save();
        return Result.OK;
    }

    /** Gives an item to an online player; what doesn't fit (or everything, if offline) goes to their collect list. */
    public void deliver(UUID who, ItemStack item) {
        Player p = Bukkit.getPlayer(who);
        if (p != null) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(item.clone());
            if (left.isEmpty()) return;
            claims(who).addAll(left.values());
            p.sendMessage(plugin.msg("inventory-full"));
        } else {
            claims(who).add(item.clone());
        }
        save();
    }

    /** Moves expired listings to the seller's collect list. Returns how many expired. */
    public int expire() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Listing l : new ArrayList<>(listings.values())) {
            if (l.expires > now) continue;
            listings.remove(l.id);
            claims(l.seller).add(l.item.clone());
            n++;
            Player p = Bukkit.getPlayer(l.seller);
            if (p != null) p.sendMessage(plugin.msg("expired").replace("{item}", DarkAH.itemName(l.item)));
        }
        if (n > 0) save();
        return n;
    }

    public void remove(UUID listing) {
        listings.remove(listing);
    }

    public void clearClaims(UUID who) {
        claims.remove(who);
    }

    public void recordSale(Material m, double unit) {
        List<Double> l = sales.computeIfAbsent(m, k -> new ArrayList<>());
        l.add(Math.round(unit * 100) / 100.0);
        while (l.size() > 25) l.remove(0);
    }

    /** What one of this item usually sells for on /ah (the middle of the last 25 sales), 0 if it never sold. */
    public double auctionValue(Material m) {
        List<Double> l = sales.get(m);
        if (l == null || l.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(l);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    public void forgetSales(Material m) {
        sales.remove(m);
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ls = y.getConfigurationSection("listings");
        if (ls != null) {
            for (String k : ls.getKeys(false)) {
                ConfigurationSection c = ls.getConfigurationSection(k);
                if (c == null) continue;
                ItemStack it = c.getItemStack("item");
                if (it == null) continue;
                try {
                    Listing l = new Listing(UUID.fromString(k), UUID.fromString(c.getString("seller", "")),
                            c.getString("name", "?"), it, c.getDouble("price"), c.getLong("listed"), c.getLong("expires"));
                    listings.put(l.id, l);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection ss = y.getConfigurationSection("sales");
        if (ss != null) for (String k : ss.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null) sales.put(m, new ArrayList<>(ss.getDoubleList(k)));
        }
        ConfigurationSection cs = y.getConfigurationSection("claims");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                List<ItemStack> items = new ArrayList<>();
                for (Object o : cs.getList(k, List.of())) if (o instanceof ItemStack it) items.add(it);
                try {
                    if (!items.isEmpty()) claims.put(UUID.fromString(k), items);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String p = "listings." + l.id + ".";
            y.set(p + "seller", l.seller.toString());
            y.set(p + "name", l.sellerName);
            y.set(p + "item", l.item);
            y.set(p + "price", l.price);
            y.set(p + "listed", l.listed);
            y.set(p + "expires", l.expires);
        }
        for (Map.Entry<Material, List<Double>> e : sales.entrySet()) y.set("sales." + e.getKey().name(), e.getValue());
        for (Map.Entry<UUID, List<ItemStack>> e : claims.entrySet())
            if (!e.getValue().isEmpty()) y.set("claims." + e.getKey(), e.getValue());
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save the auction house: " + e);
        }
    }
}
