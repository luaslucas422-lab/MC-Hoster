package dev.darkerdawnfr.darkranks;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /store: ranks bought with real money on your web store (Tebex, a website...). It only shows them and links to the
 * store; nothing is sold in game. Two modes like /discord: GUI (default: an enchanted totem "Paid Ranks" that opens the
 * ranks) or Text (the store link in chat). Ops: /store edit, /store link <url>, /store mode <gui|text>.
 */
public class Store implements CommandExecutor, TabCompleter {
    private final DarkRanks plugin;
    private final Ask ask;

    public Store(DarkRanks plugin, Ask ask) {
        this.plugin = plugin;
        this.ask = ask;
    }

    private String c(String path, String def) {
        return plugin.getConfig().getString("store." + path, def);
    }

    private void set(String path, Object v) {
        plugin.getConfig().set("store." + path, v);
        plugin.saveConfig();
    }

    public String link() {
        return c("link", "https://your-store.tebex.io");
    }

    public boolean gui() {
        return !c("mode", "gui").equalsIgnoreCase("text");
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(Util.color(c("prefix", "&8[&6Store&8] ") + t));
        return true;
    }

    /** The paid ranks: name, icon, price, perks. */
    public List<Map<String, Object>> ranks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("store.ranks")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            out.add(copy);
        }
        return out;
    }

    private void saveRanks(List<Map<String, Object>> l) {
        set("ranks", l);
    }

    private static String str(Map<String, Object> m, String k, String d) {
        Object o = m.get(k);
        return o == null ? d : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> perks(Map<String, Object> m) {
        Object o = m.get("perks");
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x));
        return out;
    }

    public void sendLink(CommandSender s) {
        for (String l : plugin.getConfig().getStringList("store.text")) s.sendMessage(Util.color(l.replace("{link}", link())));
        Chat.link(s, Util.color(c("prefix", "&8[&6Store&8] ") + "&e"), link());
    }

    // ------------------------------------------------------------------ menus

    public Menu main(Player p) {
        Menu m = new Menu(3, c("title", "&8Store"));
        ItemStack totem = Util.item(Material.TOTEM_OF_UNDYING, c("button", "&e&lPaid Ranks"),
                "&7Ranks you can buy on our store", "&7to support the server.", "", "&eClick to see them");
        glint(totem);
        m.set(13, totem, e -> paidRanks(p).open(p));
        if (p.hasPermission("darkranks.admin"))
            m.set(26, Util.item(Material.WRITABLE_BOOK, "&cEdit the store", "&7Ops only"), e -> editor(p).open(p));
        m.fill();
        return m;
    }

    private static void glint(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        try {
            meta.setEnchantmentGlintOverride(true);
        } catch (Throwable t) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.values()[0], 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        it.setItemMeta(meta);
    }

    private ItemStack icon(Map<String, Object> r, boolean edit) {
        Material mat = Util.material(str(r, "icon", "GOLD_INGOT"));
        List<String> lore = new ArrayList<>();
        lore.add("&7Price: &a" + str(r, "price", "?"));
        lore.add("");
        for (String pk : perks(r)) lore.add("&8• &f" + pk);
        lore.add("");
        lore.add(edit ? "&eClick to change" : "&eClick to buy it on the store");
        ItemStack it = Util.item(mat == null ? Material.GOLD_INGOT : mat, 1, str(r, "name", "Rank"), lore);
        glint(it);
        return it;
    }

    public Menu paidRanks(Player p) {
        List<Map<String, Object>> rs = ranks();
        int rows = Math.min(6, Math.max(3, (rs.size() + 6) / 7 + 2));
        Menu m = new Menu(rows, "&8" + Util.small("Paid ranks"));
        int slot = 10;
        for (Map<String, Object> r : rs) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++;
            if (slot >= rows * 9 - 9) break;
            m.set(slot++, icon(r, false), e -> {
                p.closeInventory();
                say(p, "&7Buy " + str(r, "name", "it") + " &7(" + str(r, "price", "") + "&7) on our store:");
                Chat.link(p, Util.color("&8» &e"), link());
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f);
            });
        }
        if (rs.isEmpty()) m.set(13, Util.item(Material.BARRIER, "&7No paid ranks yet", p.hasPermission("darkranks.admin") ? "&7/store edit" : ""));
        m.set(rows * 9 - 5, Util.item(Material.ARROW, "&fBack"), e -> main(p).open(p));
        m.fill();
        return m;
    }

    public Menu editor(Player p) {
        List<Map<String, Object>> rs = ranks();
        Menu m = new Menu(5, "&8" + Util.small("Store editor"));
        int slot = 0;
        for (int i = 0; i < rs.size() && slot < 27; i++) {
            int idx = i;
            m.set(slot++, icon(rs.get(i), true), e -> rankEditor(p, idx).open(p));
        }
        m.set(36, Util.item(Material.LIME_DYE, "&aAdd a paid rank", "&7You'll type its name"), e ->
                ask.ask(p, "the paid rank's name (& colours work, like &6&lLEGEND)", t -> {
                    List<Map<String, Object>> l = ranks();
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", t.trim());
                    r.put("icon", "GOLD_INGOT");
                    r.put("price", "€4.99");
                    r.put("perks", new ArrayList<>(List.of("Change me in /store edit")));
                    l.add(r);
                    saveRanks(l);
                }, () -> editor(p).open(p)));
        m.set(38, Util.item(Material.CHAIN, "&eStore link", "&7Now: &f" + link()), e ->
                ask.ask(p, "the store link (https://...)", t -> {
                    if (t.trim().startsWith("http")) set("link", t.trim());
                    else say(p, "&cA link starts with https://");
                }, () -> editor(p).open(p)));
        m.set(40, Util.item(gui() ? Material.TOTEM_OF_UNDYING : Material.PAPER, "&eMode: &f" + (gui() ? "GUI" : "Text"),
                gui() ? "&7/store opens the Paid Ranks menu" : "&7/store sends the link in chat", "", "&eClick to switch"), e -> {
            set("mode", gui() ? "text" : "gui");
            editor(p).open(p);
        });
        m.set(42, Util.item(Material.BOOK, "&eText mode message", "&7The lines before the link", "&7({link} = the link)"), e ->
                ask.ask(p, "the message (use | for a new line)", t -> set("text", List.of(t.split("\\|"))), () -> editor(p).open(p)));
        m.set(44, Util.item(Material.ARROW, "&fClose"), e -> p.closeInventory());
        m.fill();
        return m;
    }

    public Menu rankEditor(Player p, int idx) {
        List<Map<String, Object>> rs = ranks();
        if (idx >= rs.size()) return editor(p);
        Map<String, Object> r = rs.get(idx);
        Menu m = new Menu(3, "&8" + Util.small("Edit paid rank"));
        m.set(4, icon(r, false));
        Runnable back = () -> rankEditor(p, idx).open(p);
        m.set(10, Util.item(Material.NAME_TAG, "&eName", "&7Now: " + str(r, "name", "")), e ->
                ask.ask(p, "the name", t -> change(idx, "name", t.trim()), back));
        m.set(11, Util.item(Material.SUNFLOWER, "&ePrice", "&7Now: &a" + str(r, "price", ""), "&7Any text, like €4.99 or $10/month"), e ->
                ask.ask(p, "the price", t -> change(idx, "price", t.trim()), back));
        m.set(12, Util.item(Material.BOOK, "&ePerks", "&7What it gives (shown in the menu)"), e ->
                ask.ask(p, "the perks, split with | (like /fly | 10 homes | a gold tag)", t -> {
                    List<String> l = new ArrayList<>();
                    for (String x : t.split("\\|")) if (!x.isBlank()) l.add(x.trim());
                    change(idx, "perks", l);
                }, back));
        m.set(14, Util.item(Material.ITEM_FRAME, "&eIcon", "&7Hold an item and click"), e -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() != Material.AIR) change(idx, "icon", hand.getType().name());
            back.run();
        });
        m.set(15, Util.item(Material.ARROW, "&fMove left"), e -> {
            if (idx > 0) {
                List<Map<String, Object>> l = ranks();
                l.add(idx - 1, l.remove(idx));
                saveRanks(l);
            }
            editor(p).open(p);
        });
        m.set(16, Util.item(Material.BARRIER, "&cDelete", "&7Shift-click"), e -> {
            if (!e.isShiftClick()) return;
            List<Map<String, Object>> l = ranks();
            l.remove(idx);
            saveRanks(l);
            editor(p).open(p);
        });
        m.set(22, Util.item(Material.ARROW, "&fBack"), e -> editor(p).open(p));
        m.fill();
        return m;
    }

    public void change(int idx, String key, Object v) {
        List<Map<String, Object>> l = ranks();
        if (idx < l.size()) {
            l.get(idx).put(key, v);
            saveRanks(l);
        }
    }

    // ------------------------------------------------------------------ /store

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        boolean admin = s.hasPermission("darkranks.admin");
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        if (admin) switch (sub) {
            case "edit" -> {
                if (s instanceof Player p) editor(p).open(p);
                return true;
            }
            case "link" -> {
                if (a.length < 2) return say(s, "&7Usage: /store link <https://...>");
                set("link", a[1]);
                return say(s, "&7Store link: &f" + a[1]);
            }
            case "mode" -> {
                if (a.length < 2 || !(a[1].equalsIgnoreCase("gui") || a[1].equalsIgnoreCase("text"))) return say(s, "&7Usage: /store mode <gui|text>");
                set("mode", a[1].toLowerCase(Locale.ROOT));
                return say(s, "&7/store now " + (gui() ? "opens the Paid Ranks menu." : "sends the link in chat."));
            }
            default -> {
            }
        }
        if (s instanceof Player p && gui()) main(p).open(p);
        else sendLink(s);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("darkranks.admin")) return out;
        if (a.length == 1) out.addAll(List.of("edit", "link", "mode"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("mode")) out.addAll(List.of("gui", "text"));
        out.removeIf(v -> !v.startsWith(a[a.length - 1].toLowerCase(Locale.ROOT)));
        return out;
    }
}
