package dev.darkerdawnfr.darkshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /orders: DonutSMP-style buy orders. "I want 640 diamonds at $50 each": the money is held when the order is made,
 * anyone can deliver diamonds and gets paid straight away, and the buyer collects them in Your Orders.
 */
public class Orders {
    public static final class Order {
        public final UUID id, owner;
        public final String ownerName;
        public final Material item;
        public final int amount;
        public final double price;
        public final long created;
        public int delivered, waiting;

        Order(UUID id, UUID owner, String ownerName, Material item, int amount, double price, long created) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.item = item;
            this.amount = amount;
            this.price = price;
            this.created = created;
        }

        public int left() {
            return amount - delivered;
        }
    }

    private final DarkShop plugin;
    private final File file;
    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private final Map<UUID, Integer> pages = new HashMap<>();

    public Orders(DarkShop plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
        load();
    }

    public List<Order> open() {
        List<Order> l = new ArrayList<>();
        for (Order o : orders.values()) if (o.left() > 0) l.add(o);
        l.sort((a, b) -> Double.compare(b.price, a.price));
        return l;
    }

    public List<Order> of(UUID owner) {
        List<Order> l = new ArrayList<>();
        for (Order o : orders.values()) if (o.owner.equals(owner)) l.add(o);
        return l;
    }

    public Order get(UUID id) {
        return orders.get(id);
    }

    /** Makes an order and holds the money. Returns null and a reason in why[0] if it can't. */
    public Order create(UUID owner, String name, Material item, int amount, double price, String[] why) {
        if (item == null || !item.isItem() || item == Material.AIR) {
            why[0] = "That isn't an item.";
            return null;
        }
        if (amount < 1 || amount > plugin.getConfig().getInt("orders.max-amount", 100_000)) {
            why[0] = "Order between 1 and " + plugin.getConfig().getInt("orders.max-amount", 100_000) + " items.";
            return null;
        }
        if (Double.isNaN(price) || price < plugin.getConfig().getDouble("orders.min-price", 0.1)) {
            why[0] = "The price each is too low.";
            return null;
        }
        if (of(owner).size() >= plugin.getConfig().getInt("orders.max-orders", 5)) {
            why[0] = "You can have " + plugin.getConfig().getInt("orders.max-orders", 5) + " orders at once.";
            return null;
        }
        double total = Math.round(amount * price * 100) / 100.0;
        if (!plugin.money().take(owner, total)) {
            why[0] = "You need " + Util.money(total) + " for that order.";
            return null;
        }
        Order o = new Order(UUID.randomUUID(), owner, name, item, amount, price, System.currentTimeMillis());
        orders.put(o.id, o);
        save();
        return o;
    }

    /** Someone delivers n items: they get paid now, the owner collects later. Returns how many were taken. */
    public int deliver(UUID who, Order o, int n) {
        if (!orders.containsKey(o.id) || who.equals(o.owner)) return 0;
        int take = Math.min(n, o.left());
        if (take <= 0) return 0;
        o.delivered += take;
        o.waiting += take;
        plugin.money().give(who, Math.round(take * o.price * 100) / 100.0);
        save();
        return take;
    }

    /** Gives the owner what was delivered. Returns how many. Finished orders disappear once collected. */
    public int collect(Player p, Order o) {
        int n = o.waiting;
        o.waiting = 0;
        giveItems(p, o.item, n);
        if (o.left() <= 0) orders.remove(o.id);
        save();
        return n;
    }

    /** Cancels: the money for what wasn't delivered comes back. Returns the refund. */
    public double cancel(UUID owner, Order o) {
        if (!o.owner.equals(owner) || orders.remove(o.id) == null) return 0;
        double refund = Math.round(o.left() * o.price * 100) / 100.0;
        plugin.money().give(owner, refund);
        Player p = Bukkit.getPlayer(owner);
        if (p != null && o.waiting > 0) giveItems(p, o.item, o.waiting);
        save();
        return refund;
    }

    private static void giveItems(Player p, Material m, int n) {
        while (n > 0) {
            int s = Math.min(n, m.getMaxStackSize());
            for (ItemStack rest : p.getInventory().addItem(new ItemStack(m, s)).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
            n -= s;
        }
    }

    // ------------------------------------------------------------------ menus

    public Menu browse(Player p, int page) {
        List<Order> all = open();
        int pages = Math.max(1, (all.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        pages_put(p, pg);
        Menu m = new Menu(6, "&8" + Util.small("Orders") + " &8(" + (pg + 1) + "/" + pages + ")");
        for (int i = 0; i < 45 && pg * 45 + i < all.size(); i++) {
            Order o = all.get(pg * 45 + i);
            boolean mine = o.owner.equals(p.getUniqueId());
            List<String> lore = new ArrayList<>(List.of("&7Pays: &a" + Util.money(o.price) + " &7each",
                    "&7Wanted: &f" + o.delivered + "&7/&f" + o.amount, "&7By: &f" + o.ownerName));
            lore.addAll(plugin.valueLore(o.item.name(), 1));
            lore.add("");
            lore.add(mine ? "&7Your own order" : "&eClick to deliver");
            m.set(i, Util.item(o.item, 1, "&f" + Util.nice(o.item), lore), e -> {
                if (!mine) deliverMenu(p, o).open(p);
            });
        }
        for (int s = 45; s < 54; s++) m.set(s, Util.glass());
        if (pg > 0) m.set(45, Util.item(Material.ARROW, "&fPrevious page"), e -> browse(p, pg - 1).open(p));
        if (pg < pages - 1) m.set(53, Util.item(Material.ARROW, "&fNext page"), e -> browse(p, pg + 1).open(p));
        m.set(48, Util.item(Material.CHEST, "&fYour orders", "&7Collect what was delivered", "&7or cancel an order."), e -> yours(p).open(p));
        m.set(50, Util.item(Material.WRITABLE_BOOK, "&aNew order", "&7Hold the item you want and type",
                "&f/orders new <amount> <price each>", "&7e.g. &f/orders new 640 50", "", "&7Or: &f/orders new DIAMOND 640 50",
                "&7The money is held until it's delivered."));
        if (all.isEmpty()) m.set(22, Util.item(Material.PAPER, "&7No orders yet", "&7Be the first: /orders new"));
        return m;
    }

    private void pages_put(Player p, int pg) {
        pages.put(p.getUniqueId(), pg);
    }

    public Menu deliverMenu(Player p, Order o) {
        Menu m = new Menu(6, "&8" + Util.small("Deliver " + Util.nice(o.item)));
        m.editable = true;
        m.onClose = pl -> {
            Inventory inv = m.getInventory();
            int want = o.left(), got = 0;
            List<ItemStack> back = new ArrayList<>();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || it.getType() == Material.AIR) continue;
                inv.setItem(i, null);
                if (it.isSimilar(new ItemStack(o.item)) && got < want) {
                    int use = Math.min(it.getAmount(), want - got);
                    got += use;
                    if (use < it.getAmount()) {
                        it.setAmount(it.getAmount() - use);
                        back.add(it);
                    }
                } else back.add(it);
            }
            int taken = got > 0 ? deliver(pl.getUniqueId(), o, got) : 0;
            if (taken < got) back.add(new ItemStack(o.item, got - taken));
            for (ItemStack it : back)
                for (ItemStack rest : pl.getInventory().addItem(it).values()) pl.getWorld().dropItemNaturally(pl.getLocation(), rest);
            if (taken > 0) {
                pl.sendMessage(plugin.msg("order-delivered").replace("{amount}", String.valueOf(taken))
                        .replace("{item}", Util.nice(o.item)).replace("{price}", Util.money(taken * o.price)));
                pl.playSound(pl.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                Player owner = Bukkit.getPlayer(o.owner);
                if (owner != null) owner.sendMessage(plugin.msg("order-filled").replace("{amount}", String.valueOf(taken))
                        .replace("{item}", Util.nice(o.item)).replace("{player}", pl.getName()));
            } else if (!back.isEmpty()) {
                pl.sendMessage(plugin.msg("order-nothing").replace("{item}", Util.nice(o.item)));
            }
        };
        return m;
    }

    public Menu yours(Player p) {
        Menu m = new Menu(3, "&8" + Util.small("Your orders"));
        int slot = 10;
        for (Order o : of(p.getUniqueId())) {
            if (slot > 16) break;
            m.set(slot++, Util.item(o.item, 1, "&f" + Util.nice(o.item), List.of("&7Delivered: &f" + o.delivered + "&7/&f" + o.amount,
                    "&7Paying: &a" + Util.money(o.price) + " &7each", "&7To collect: &a" + o.waiting, "",
                    o.waiting > 0 ? "&eClick to collect" : "&7Nothing to collect yet",
                    "&cShift-click to cancel (refund " + Util.money(o.left() * o.price) + ")")), e -> {
                if (e.isShiftClick()) {
                    double r = cancel(p.getUniqueId(), o);
                    p.sendMessage(plugin.msg("order-cancelled").replace("{price}", Util.money(r)));
                } else if (o.waiting > 0) {
                    int n = collect(p, o);
                    p.sendMessage(plugin.msg("order-collected").replace("{amount}", String.valueOf(n)).replace("{item}", Util.nice(o.item)));
                }
                yours(p).open(p);
            });
        }
        if (slot == 10) m.set(13, Util.item(Material.PAPER, "&7You have no orders", "&7Hold an item: &f/orders new <amount> <price>"));
        m.set(22, Util.item(Material.ARROW, "&fBack to all orders"), e -> browse(p, pages.getOrDefault(p.getUniqueId(), 0)).open(p));
        m.fill();
        return m;
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection c = y.getConfigurationSection(k);
            if (c == null) continue;
            try {
                Material m = Material.matchMaterial(c.getString("item", ""));
                if (m == null) continue;
                Order o = new Order(UUID.fromString(k), UUID.fromString(c.getString("owner", "")), c.getString("name", "?"), m,
                        c.getInt("amount"), c.getDouble("price"), c.getLong("created"));
                o.delivered = c.getInt("delivered");
                o.waiting = c.getInt("waiting");
                orders.put(o.id, o);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Order o : orders.values()) {
            String p = o.id + ".";
            y.set(p + "owner", o.owner.toString());
            y.set(p + "name", o.ownerName);
            y.set(p + "item", o.item.name());
            y.set(p + "amount", o.amount);
            y.set(p + "price", o.price);
            y.set(p + "created", o.created);
            y.set(p + "delivered", o.delivered);
            y.set(p + "waiting", o.waiting);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save orders: " + e);
        }
    }

    public void remove(UUID id) {
        orders.remove(id);
        save();
    }
}
