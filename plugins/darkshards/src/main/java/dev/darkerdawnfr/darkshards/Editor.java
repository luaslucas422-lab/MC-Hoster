package dev.darkerdawnfr.darkshards;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * /shards edit (ops): change every shard item in game. Hold any item and click "Item" to make the
 * tool that item (e.g. a diamond pickaxe instead of the amethyst shard), or type a name.
 */
public class Editor implements Listener {
    private static final List<String> ABILITIES = List.of("pickaxe", "axe", "shovel", "hoe", "none");
    private final DarkShards plugin;
    private final Map<UUID, Consumer<String>> typing = new ConcurrentHashMap<>();

    public Editor(DarkShards plugin) {
        this.plugin = plugin;
    }

    private String path(String id) {
        return "items." + id + ".";
    }

    /** Changes one setting of an item and reloads. Also used by the self test. */
    public void set(String id, String key, Object value) {
        plugin.getConfig().set(path(id) + key, value);
        plugin.saveConfig();
        plugin.items().reload();
    }

    public Menu main() {
        Menu m = new Menu(4, "&8" + Util.small("Shard item editor"));
        org.bukkit.configuration.ConfigurationSection cs = plugin.getConfig().getConfigurationSection("items");
        int slot = 10;
        if (cs != null) for (String id : cs.getKeys(false)) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 26) break;
            ShardItems.Def d = plugin.items().defs().get(id);
            ItemStack show = d != null ? plugin.items().create(d)
                    : Util.item(Material.GRAY_DYE, "&7" + id + " (off)", "&7Turned off");
            var meta = show.getItemMeta();
            List<String> lore = new ArrayList<>(List.of(Util.color("&7Id: &f" + id), "", Util.color("&eClick to edit")));
            meta.setLore(lore);
            show.setItemMeta(meta);
            m.set(slot++, show, e -> item(id).open((Player) e.getWhoClicked()));
        }
        m.set(31, Util.item(Material.LIME_DYE, "&aAdd an item", "&7Starts as a copy of the Shard Pickaxe"), e -> {
            String id = "item" + (cs == null ? 1 : cs.getKeys(false).size() + 1);
            for (String k : List.of("name", "material", "ability", "size", "speed", "price", "hours", "lore"))
                plugin.getConfig().set(path(id) + k, plugin.getConfig().get("items.pickaxe." + k));
            plugin.getConfig().set(path(id) + "name", "&d&lNew Shard Item");
            plugin.getConfig().set(path(id) + "slot", firstFreeSlot());
            plugin.saveConfig();
            plugin.items().reload();
            item(id).open((Player) e.getWhoClicked());
        });
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("DarkCrates") != null)
            m.set(27, Util.item(Material.TRIPWIRE_HOOK, "&eCrate Keys page", "&7Key prices, crates and their loot", "&eClick to edit"),
                    e -> ((Player) e.getWhoClicked()).performCommand("crate edit"));
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("DarkSpawners") != null)
            m.set(35, Util.item(Material.SPAWNER, "&eSpawners page", "&7Spawner prices, loot and XP", "&eClick to edit"),
                    e -> ((Player) e.getWhoClicked()).performCommand("spawners edit"));
        m.set(33, Util.item(Material.CLOCK, "&fEarning shards",
                "&7Per kill: &d" + plugin.getConfig().getLong("earn.kill", 5),
                "&7Playing: &d" + plugin.getConfig().getLong("earn.amount", 1) + " &7every &d" + plugin.getConfig().getInt("earn.minutes", 5) + " min",
                "", "&eLeft: change shards per kill", "&eRight: change minutes per shard"), e -> {
            Player p = (Player) e.getWhoClicked();
            boolean right = e.getClick() == ClickType.RIGHT;
            ask(p, right ? "how many minutes of playing give a shard" : "how many shards a kill gives", t -> {
                long n = num(t);
                if (n < 0) bad(p, t);
                else {
                    plugin.getConfig().set(right ? "earn.minutes" : "earn.kill", n);
                    plugin.saveConfig();
                }
            }, this::main);
        });
        m.fill();
        return m;
    }

    private int firstFreeSlot() {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (ShardItems.Def d : plugin.items().defs().values()) used.add(d.slot());
        for (int s = 10; s < 27; s++) if (s % 9 != 0 && s % 9 != 8 && !used.contains(s)) return s;
        return 22;
    }

    public Menu item(String id) {
        String p = path(id);
        var c = plugin.getConfig();
        ShardItems.Def d = plugin.items().defs().get(id);
        Menu m = new Menu(5, "&8" + Util.small("Edit " + id));
        m.set(4, d != null ? plugin.items().create(d) : Util.item(Material.BARRIER, "&cTurned off or broken",
                "&7Check the item name: " + c.getString(p + "material")));
        String mat = c.getString(p + "material", "AMETHYST_SHARD");
        m.set(10, Util.item(Material.NAME_TAG, "&fName", "&7Now: " + c.getString(p + "name", id), "", "&eClick to change"),
                e -> ask((Player) e.getWhoClicked(), "the name (& colours work)", t -> set(id, "name", t), () -> item(id)));
        Material icon = Util.material(mat);
        m.set(11, Util.item(icon != null && icon.isItem() ? icon : Material.BARRIER, "&fItem: &d" + mat,
                "&7What the tool is made of.", "", "&eLeft-click: use the item in your hand", "&eRight-click: type an item name"), e -> {
            Player pl = (Player) e.getWhoClicked();
            if (e.getClick() == ClickType.RIGHT) {
                ask(pl, "an item name, e.g. DIAMOND_PICKAXE", t -> {
                    Material x = Util.material(t.trim().toUpperCase().replace(' ', '_'));
                    if (x == null || !x.isItem()) bad(pl, t);
                    else set(id, "material", x.name());
                }, () -> item(id));
                return;
            }
            ItemStack hand = pl.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR) {
                pl.sendMessage(Util.color("&cHold the item you want first (e.g. a diamond pickaxe), then click again."));
                return;
            }
            set(id, "material", hand.getType().name());
            item(id).open(pl);
        });
        String ability = c.getString(p + "ability", "pickaxe");
        m.set(12, Util.item(Material.IRON_PICKAXE, "&fAbility: &d" + ability, "&7pickaxe / shovel: mines size x size",
                "&7axe: whole trees", "&7hoe: harvests and replants", "&7none: just the item", "", "&eClick to change"), e -> {
            set(id, "ability", ABILITIES.get((ABILITIES.indexOf(ability) + 1) % ABILITIES.size()));
            item(id).open((Player) e.getWhoClicked());
        });
        int size = c.getInt(p + "size", 3);
        m.set(13, Util.item(Material.SCAFFOLDING, "&fSize: &d" + size + "x" + size, "", "&eLeft: bigger  &eRight: smaller"), e -> {
            int n = Math.max(1, Math.min(9, size + (e.getClick() == ClickType.RIGHT ? -2 : 2)));
            set(id, "size", n);
            item(id).open((Player) e.getWhoClicked());
        });
        m.set(14, Util.item(Material.AMETHYST_SHARD, "&fPrice: &d✦ " + c.getLong(p + "price", 50), "", "&eClick to change"),
                e -> askNumber((Player) e.getWhoClicked(), id, "price", "the price in shards"));
        m.set(15, Util.item(Material.CLOCK, "&fLasts: &d" + c.getDouble(p + "hours", 24) + " hours", "", "&eClick to change"),
                e -> askNumber((Player) e.getWhoClicked(), id, "hours", "how many hours it lasts"));
        m.set(16, Util.item(Material.FEATHER, "&fMining speed: &d" + c.getDouble(p + "speed", 9), "&7Netherite pickaxe = 9", "", "&eClick to change"),
                e -> askNumber((Player) e.getWhoClicked(), id, "speed", "the mining speed"));
        List<String> lore = new ArrayList<>();
        for (String l : c.getStringList(p + "lore")) lore.add("&r" + l);
        lore.addAll(List.of("", "&eClick to change", "&7(use | between lines)"));
        m.set(20, Util.item(Material.WRITABLE_BOOK, 1, "&fDescription", lore),
                e -> ask((Player) e.getWhoClicked(), "the description, | between lines", t -> set(id, "lore", Arrays.asList(t.split("\\|"))), () -> item(id)));
        m.set(21, Util.item(Material.COMPASS, "&fShop slot: &d" + c.getInt(p + "slot", 10), "&70 = top left", "", "&eClick to change"),
                e -> askNumber((Player) e.getWhoClicked(), id, "slot", "the slot in the shop (0-53)"));
        boolean on = c.getBoolean(p + "enabled", true);
        m.set(22, Util.item(on ? Material.LIME_DYE : Material.GRAY_DYE, on ? "&aIn the shop" : "&7Hidden from the shop", "", "&eClick to switch"), e -> {
            set(id, "enabled", !on);
            item(id).open((Player) e.getWhoClicked());
        });
        m.set(24, Util.item(Material.CHEST, "&fGive me one", "&7To try it out"), e -> {
            ShardItems.Def now = plugin.items().defs().get(id);
            if (now != null) e.getWhoClicked().getInventory().addItem(plugin.items().create(now));
        });
        m.set(36, Util.item(Material.ARROW, "&fBack"), e -> main().open((Player) e.getWhoClicked()));
        m.set(44, Util.item(Material.TNT, "&cDelete this item", "&cShift-click to delete"), e -> {
            if (!e.isShiftClick()) return;
            plugin.getConfig().set("items." + id, null);
            plugin.saveConfig();
            plugin.items().reload();
            main().open((Player) e.getWhoClicked());
        });
        m.fill();
        return m;
    }

    private void askNumber(Player p, String id, String key, String what) {
        ask(p, what, t -> {
            try {
                double v = Double.parseDouble(t.trim());
                if (v < 0) throw new NumberFormatException();
                set(id, key, key.equals("hours") || key.equals("speed") ? (Object) v : (Object) (long) v);
            } catch (NumberFormatException ex) {
                bad(p, t);
            }
        }, () -> item(id));
    }

    private static long num(String t) {
        try {
            return Long.parseLong(t.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void bad(Player p, String t) {
        p.sendMessage(Util.color("&c" + t + " doesn't work there, nothing changed."));
    }

    private void ask(Player p, String what, Consumer<String> then, java.util.function.Supplier<Menu> back) {
        p.closeInventory();
        typing.put(p.getUniqueId(), t -> {
            then.accept(t);
            back.get().open(p);
        });
        p.sendMessage(Util.color("&d✦ &7Type " + what + " in chat (or &fcancel&7)."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncPlayerChatEvent e) {
        Consumer<String> c = typing.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String text = e.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.trim().equalsIgnoreCase("cancel")) {
                e.getPlayer().sendMessage(Util.color("&7Nothing changed."));
                return;
            }
            c.accept(text);
        });
    }
}
