package dev.darkerdawnfr.darkshop;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /shop: the main menu, one menu per category, and the amount ("Buying") menu. */
public class ShopMenus {
    public record Entry(Material material, double price) {}

    public record Category(String id, String name, Material icon, int slot, String lore, List<Entry> items) {}

    private final DarkShop plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final List<String> missing = new ArrayList<>();

    private final String root;
    private final boolean premium;

    public ShopMenus(DarkShop plugin) {
        this(plugin, "categories", false);
    }

    /** root = where the categories are in config.yml; premium = /shop premium (rank discounts). */
    public ShopMenus(DarkShop plugin, String root, boolean premium) {
        this.plugin = plugin;
        this.root = root;
        this.premium = premium;
        reload();
    }

    public String root() {
        return root;
    }

    /** Percent off for this player in the premium shop: the best "discounts" entry they have the permission for. */
    public double discount(Player p) {
        if (!premium || p == null) return 0;
        double best = 0;
        org.bukkit.configuration.ConfigurationSection d = plugin.getConfig().getConfigurationSection("premium.discounts");
        if (d != null) for (String perm : d.getKeys(false))
            if (p.hasPermission("darkshop.discount." + perm)) best = Math.max(best, d.getDouble(perm));
        return Math.max(0, Math.min(90, best));
    }

    public double price(Player p, Entry en) {
        return Math.round(en.price() * (100 - discount(p))) / 100.0;
    }

    private String title(String t) {
        return "&8" + Util.small(premium ? t.replace("Shop", "Premium shop") : t);
    }

    public void reload() {
        categories.clear();
        missing.clear();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection(root);
        if (cs == null) return;
        for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null) continue;
            Material icon = Util.material(c.getString("icon", "CHEST"));
            if (icon == null || !icon.isItem()) icon = Material.CHEST;
            List<Entry> items = new ArrayList<>();
            ConfigurationSection is = c.getConfigurationSection("items");
            if (is != null) {
                for (String k : is.getKeys(false)) {
                    Material m = Util.material(k);
                    if (m == null || !m.isItem()) {
                        missing.add(k);
                        continue;
                    }
                    items.add(new Entry(m, is.getDouble(k)));
                }
            }
            categories.put(id, new Category(id, c.getString("name", id), icon, c.getInt("slot", 10 + categories.size() * 2),
                    c.getString("lore", ""), items));
        }
    }

    public Map<String, Category> categories() {
        return categories;
    }

    /** Item names in the config that this Minecraft version doesn't have. */
    public List<String> missing() {
        return missing;
    }

    public Menu main(Player v) {
        Menu m = new Menu(3, title("Shop"));
        for (Category c : categories.values()) {
            int slot = Math.max(0, Math.min(26, c.slot()));
            m.set(slot, Util.item(c.icon(), 1, c.name(), List.of(c.lore(), "", "&eClick to open")),
                    e -> category(v, c).open((Player) e.getWhoClicked()));
        }
        if (premium) m.set(22, Util.item(Material.NETHER_STAR, "&6&lYour discount: " + (int) discount(v) + "%",
                "&7Better ranks get bigger discounts.", "&7See &f/rank shop"));
        m.fill();
        return m;
    }

    public Menu category(Player v, Category c) {
        int rows = Math.min(6, Math.max(3, (c.items().size() + 6) / 7 + 2));
        Menu m = new Menu(rows, title("Shop - " + strip(c.name())));
        m.border();
        int i = 0;
        for (int slot = 10; slot < rows * 9 - 9 && i < c.items().size(); slot++) {
            if (slot % 9 == 0 || slot % 9 == 8) continue;
            Entry en = c.items().get(i++);
            double worth = plugin.getConfig().getDouble("worth." + en.material().name(), 0);
            List<String> lore = new java.util.ArrayList<>();
            if (premium && discount(v) > 0) lore.add("&7Price: &8&m" + Util.money(en.price()) + "&r &a" + Util.money(price(v, en)));
            else lore.add("&7Price: &a" + Util.money(price(v, en)));
            if (worth > 0) lore.add("&7Sells for: &e" + Util.money(worth));
            lore.add("");
            lore.add("&eClick to buy");
            m.set(slot, Util.item(en.material(), 1, "&f" + Util.nice(en.material()), lore),
                    e -> amount(v, c, en, 1).open((Player) e.getWhoClicked()));
        }
        m.set(rows * 9 - 5, Util.item(Material.ARROW, "&cBack", "&7Back to all categories"),
                e -> main(v).open((Player) e.getWhoClicked()));
        return m;
    }

    public Menu amount(Player v, Category c, Entry en, int amount) {
        int max = Math.max(1, plugin.getConfig().getInt("max-buy", 2304));
        int n = Math.max(1, Math.min(max, amount));
        Menu m = new Menu(3, "&8" + Util.small("Buying " + Util.nice(en.material())));
        int[] steps = {-64, -10, -1};
        for (int i = 0; i < 3; i++) {
            int d = steps[i];
            m.set(10 + i, Util.item(Material.RED_STAINED_GLASS_PANE, Math.abs(d), "&cRemove " + -d, List.of()),
                    e -> amount(v, c, en, n + d).open((Player) e.getWhoClicked()));
            m.set(16 - i, Util.item(Material.LIME_STAINED_GLASS_PANE, Math.abs(d), "&aAdd " + -d, List.of()),
                    e -> amount(v, c, en, n - d).open((Player) e.getWhoClicked()));
        }
        m.set(13, Util.item(en.material(), Math.min(n, en.material().getMaxStackSize()), "&f" + Util.nice(en.material()),
                List.of("&7Amount: &f" + n, "&7Total: &a" + Util.money(price(v, en) * n))));
        m.set(21, Util.item(Material.RED_STAINED_GLASS_PANE, "&c&lCancel", "&7Back to " + strip(c.name())),
                e -> category(v, c).open((Player) e.getWhoClicked()));
        m.set(23, Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lConfirm", "&7Buy &f" + n + "x &7for &a" + Util.money(price(v, en) * n)),
                e -> {
                    Player p = (Player) e.getWhoClicked();
                    if (buy(p, en, n)) p.closeInventory();
                });
        m.fill();
        return m;
    }

    /** Buys n of an item for a player: checks space and money first. */
    public boolean buy(Player p, Entry en, int n) {
        double total = Math.round(price(p, en) * n * 100) / 100.0;
        if (space(p, en.material()) < n) {
            p.sendMessage(plugin.msg("inventory-full"));
            return false;
        }
        if (!plugin.money().take(p.getUniqueId(), total)) {
            p.sendMessage(plugin.msg("not-enough").replace("{price}", Util.money(total)));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return false;
        }
        int left = n;
        while (left > 0) {
            int stack = Math.min(left, en.material().getMaxStackSize());
            p.getInventory().addItem(new ItemStack(en.material(), stack));
            left -= stack;
        }
        p.sendMessage(plugin.msg("bought").replace("{amount}", String.valueOf(n))
                .replace("{item}", Util.nice(en.material())).replace("{price}", Util.money(total)));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
        return true;
    }

    /** How many of this item still fit in the player's main inventory. */
    public static int space(Player p, Material m) {
        int max = m.getMaxStackSize(), free = 0;
        ItemStack[] storage = p.getInventory().getStorageContents();
        for (ItemStack it : storage) {
            if (it == null || it.getType() == Material.AIR) free += max;
            else if (it.getType() == m && it.isSimilar(new ItemStack(m))) free += Math.max(0, max - it.getAmount());
        }
        return free;
    }

    private static String strip(String s) {
        return s.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }
}
