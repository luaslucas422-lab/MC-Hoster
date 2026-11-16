package dev.darkerdawnfr.darkshop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * /shop edit (ops): change the shop in game. Categories: name, icon (the item in your hand), slot, delete.
 * Items: add the one in your hand, change prices, remove. Also the /sell price of anything you hold.
 */
public class ShopEditor {
    private final DarkShop plugin;
    private final ShopMenus menus;
    private final String root;
    private final Ask ask;

    public ShopEditor(DarkShop plugin, ShopMenus menus) {
        this.plugin = plugin;
        this.menus = menus;
        this.root = menus.root();
        this.ask = new Ask(plugin);
    }

    private void save() {
        plugin.saveConfig();
        menus.reload();
    }

    /** Sets a price (also used by the self test). */
    public void setPrice(String cat, Material m, double price) {
        plugin.getConfig().set(root + "." + cat + ".items." + m.name(), price);
        save();
    }

    public void removeItem(String cat, Material m) {
        plugin.getConfig().set(root + "." + cat + ".items." + m.name(), null);
        save();
    }

    public Menu main(Player p) {
        Menu m = new Menu(4, "&8" + Util.small(root.startsWith("premium") ? "Premium shop editor" : "Shop editor"));
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection(root);
        if (cs != null) for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null) continue;
            Material icon = Util.material(c.getString("icon", "CHEST"));
            int items = c.getConfigurationSection("items") == null ? 0 : c.getConfigurationSection("items").getKeys(false).size();
            int slot = Math.max(0, Math.min(26, c.getInt("slot", 10)));
            m.set(slot, Util.item(icon == null ? Material.CHEST : icon, 1, c.getString("name", id),
                    List.of("&7Id: &f" + id, "&7Items: &f" + items, "", "&eClick to edit")), e -> category(p, id).open(p));
        }
        m.set(30, Util.item(Material.LIME_DYE, "&aAdd a category", "&7Pick where it goes in /shop,", "&7then type its name"), e ->
                slotPicker(p, null, slot -> ask.ask(p, "the new category's name (e.g. &bOres)", t -> {
                    String id = org.bukkit.ChatColor.stripColor(Util.color(t)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                    if (id.isEmpty() || plugin.getConfig().isSet(root + "." + id)) {
                        p.sendMessage(Util.color("&cPick another name."));
                        return;
                    }
                    addCategory(id, t, slot);
                }, () -> main(p).open(p)), () -> main(p).open(p)).open(p));
        ItemStack hand = p.getInventory().getItemInMainHand();
        boolean holding = hand.getType() != Material.AIR;
        m.set(32, Util.item(Material.SUNFLOWER, "&fSell price of what you hold",
                holding ? "&7" + Util.nice(hand.getType()) + ": &a" + Util.money(plugin.getConfig().getDouble("worth." + hand.getType().name(), 0)) : "&7Hold an item first",
                "", "&eClick to change", "&70 = can't be sold"), e -> {
            if (!holding) return;
            Material mat = hand.getType();
            ask.ask(p, "the /sell price for one " + Util.nice(mat), t -> {
                double v = Util.parseMoney(t);
                if (Double.isNaN(v) || v < 0) p.sendMessage(Util.color("&c" + t + " isn't a price."));
                else {
                    plugin.getConfig().set("worth." + mat.name(), v == 0 ? null : v);
                    plugin.saveConfig();
                }
            }, () -> main(p).open(p));
        });
        m.fill();
        return m;
    }

    public void addCategory(String id, String name, int slot) {
        plugin.getConfig().set(root + "." + id + ".name", name);
        plugin.getConfig().set(root + "." + id + ".icon", "CHEST");
        plugin.getConfig().set(root + "." + id + ".slot", slot < 0 ? freeSlot() : slot);
        plugin.getConfig().set(root + "." + id + ".lore", "&7Things for sale.");
        save();
    }

    /** Which /shop slots are taken: category id -> slot (and the premium discount star). */
    public Map<Integer, String> usedSlots() {
        Map<Integer, String> used = new java.util.HashMap<>();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection(root);
        if (cs != null) for (String id : cs.getKeys(false)) used.put(Math.max(0, Math.min(26, cs.getInt(id + ".slot", 10))), id);
        if (root.startsWith("premium")) used.put(22, "(your discount)");
        return used;
    }

    /** The /shop layout: click an empty spot. moving = the category being moved (its own spot counts as free). */
    public Menu slotPicker(Player p, String moving, java.util.function.IntConsumer then, Runnable back) {
        Menu m = new Menu(4, "&8" + Util.small("Pick a slot in /shop"));
        Map<Integer, String> used = usedSlots();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection(root);
        for (int s = 0; s < 27; s++) {
            int slot = s;
            String who = used.get(s);
            if (who != null && !who.equals(moving)) {
                Material icon = cs != null && cs.isSet(who) ? Util.material(cs.getString(who + ".icon", "CHEST")) : Material.NETHER_STAR;
                m.set(s, Util.item(icon == null ? Material.CHEST : icon, 1, cs != null && cs.isSet(who) ? cs.getString(who + ".name", who) : "&6Discount",
                        List.of("&cTaken")));
            } else {
                m.set(s, Util.item(who != null ? Material.LIME_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE, 1,
                        who != null ? "&aIt's here now (slot " + s + ")" : "&aPut it here", List.of("&7Slot " + s, "", "&eClick")), e -> then.accept(slot));
            }
        }
        for (int s = 27; s < 36; s++) m.set(s, Util.glass());
        m.set(31, Util.item(Material.ARROW, "&fBack"), e -> back.run());
        return m;
    }

    private int freeSlot() {
        List<Integer> used = new ArrayList<>();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection(root);
        if (cs != null) for (String id : cs.getKeys(false)) used.add(cs.getInt(id + ".slot"));
        for (int s = 10; s < 27; s++) if (s % 9 != 0 && s % 9 != 8 && !used.contains(s)) return s;
        return 22;
    }

    public Menu category(Player p, String id) {
        String path = root + "." + id;
        ConfigurationSection items = plugin.getConfig().getConfigurationSection(path + ".items");
        Menu m = new Menu(6, "&8" + Util.small("Edit " + id));
        int slot = 0;
        if (items != null) for (String k : items.getKeys(false)) {
            if (slot >= 45) break;
            Material mat = Util.material(k);
            if (mat == null || !mat.isItem()) continue;
            double price = items.getDouble(k);
            m.set(slot++, Util.item(mat, 1, "&f" + Util.nice(mat), List.of("&7Price: &a" + Util.money(price), "",
                    "&eClick to change the price", "&cShift-click to remove")), e -> {
                if (e.isShiftClick()) {
                    removeItem(id, mat);
                    category(p, id).open(p);
                } else ask.ask(p, "the price for one " + Util.nice(mat), t -> {
                    double v = Util.parseMoney(t);
                    if (Double.isNaN(v) || v <= 0) p.sendMessage(Util.color("&c" + t + " isn't a price."));
                    else setPrice(id, mat, v);
                }, () -> category(p, id).open(p));
            });
        }
        for (int i = 45; i < 54; i++) m.set(i, Util.glass());
        m.set(45, Util.item(Material.ARROW, "&fBack"), e -> main(p).open(p));
        m.set(47, Util.item(Material.LIME_DYE, "&aAdd the item in your hand", "&7Hold it, click, then type its price"), e -> {
            Material mat = p.getInventory().getItemInMainHand().getType();
            if (mat == Material.AIR) {
                p.sendMessage(Util.color("&cHold the item you want to sell in the shop first."));
                return;
            }
            ask.ask(p, "the price for one " + Util.nice(mat), t -> {
                double v = Util.parseMoney(t);
                if (Double.isNaN(v) || v <= 0) p.sendMessage(Util.color("&c" + t + " isn't a price."));
                else setPrice(id, mat, v);
            }, () -> category(p, id).open(p));
        });
        m.set(48, Util.item(Material.NAME_TAG, "&fRename", "&7Now: " + plugin.getConfig().getString(path + ".name", id)),
                e -> ask.ask(p, "the category name (& colours work)", t -> {
                    plugin.getConfig().set(path + ".name", t);
                    save();
                }, () -> category(p, id).open(p)));
        m.set(49, Util.item(Material.ITEM_FRAME, "&fIcon", "&7Hold an item and click", "&7to make it the icon"), e -> {
            Material mat = p.getInventory().getItemInMainHand().getType();
            if (mat == Material.AIR) return;
            plugin.getConfig().set(path + ".icon", mat.name());
            save();
            category(p, id).open(p);
        });
        m.set(50, Util.item(Material.COMPASS, "&fSlot in /shop: &e" + plugin.getConfig().getInt(path + ".slot"), "&eClick to move it"),
                e -> slotPicker(p, id, picked -> {
                    plugin.getConfig().set(path + ".slot", picked);
                    save();
                    category(p, id).open(p);
                }, () -> category(p, id).open(p)).open(p));
        m.set(51, Util.item(Material.WRITABLE_BOOK, "&fDescription", "&7Now: " + plugin.getConfig().getString(path + ".lore", "")),
                e -> ask.ask(p, "the description", t -> {
                    plugin.getConfig().set(path + ".lore", t);
                    save();
                }, () -> category(p, id).open(p)));
        m.set(53, Util.item(Material.TNT, "&cDelete this category", "&cShift-click to delete"), e -> {
            if (!e.isShiftClick()) return;
            plugin.getConfig().set(path, null);
            save();
            main(p).open(p);
        });
        return m;
    }
}
