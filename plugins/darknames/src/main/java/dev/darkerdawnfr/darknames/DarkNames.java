package dev.darkerdawnfr.darknames;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dark Names by DarkerDawnFR: rename the item you hold in any colour.
 * /darknames (/dn): Rename (a menu with colours, gradients, animations and bold/italic/...) or Reset (back to its own name).
 * Typed names can use &a codes, &#RRGGBB, <#RRGGBB> and <gradient:#from:#to>text</gradient>.
 */
public class DarkNames extends JavaPlugin implements Listener, TabCompleter {
    /** What a player is making in the rename menu. */
    static final class Edit {
        String text;
        Colors.Style style = new Colors.Style("solid", "White", List.of("#FFFFFF"));
        String fmt = "";
    }

    private final Map<UUID, Edit> edits = new HashMap<>();
    private NamespacedKey textKey, styleKey, fmtKey;
    private Ask ask;
    private long frame;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        textKey = new NamespacedKey(this, "text");
        styleKey = new NamespacedKey(this, "style");
        fmtKey = new NamespacedKey(this, "fmt");
        ask = new Ask(this);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("darknames");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        long every = Math.max(1, getConfig().getLong("animation-ticks", 3));
        Bukkit.getScheduler().runTaskTimer(this, this::animateAll, every, every);
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&dNames&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
    }

    private int maxLength() {
        return Math.max(3, getConfig().getInt("max-length", 32));
    }

    // ------------------------------------------------------------------ names on items

    public String nameOf(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.getPersistentDataContainer().has(textKey, PersistentDataType.STRING))
            return m.getPersistentDataContainer().get(textKey, PersistentDataType.STRING);
        if (m != null && m.hasDisplayName()) return Colors.strip(m.getDisplayName());
        return Util.nice(it.getType());
    }

    /** Renames the item. Returns false if it can't be renamed. */
    public boolean rename(ItemStack it, String text, Colors.Style st, String fmt) {
        if (it == null || it.getType() == Material.AIR) return false;
        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        m.setDisplayName(Colors.render(text, st, fmt, frame));
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(textKey, PersistentDataType.STRING, text);
        pdc.set(styleKey, PersistentDataType.STRING, st.save());
        pdc.set(fmtKey, PersistentDataType.STRING, fmt == null ? "" : fmt);
        it.setItemMeta(m);
        return true;
    }

    /** Back to the item's own name. */
    public boolean reset(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        boolean had = m.hasDisplayName();
        m.setDisplayName(null);
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.remove(textKey);
        pdc.remove(styleKey);
        pdc.remove(fmtKey);
        it.setItemMeta(m);
        return had;
    }

    /** Moves an animated name on one frame. Returns true if the item changed. */
    public boolean animate(ItemStack it, long f) {
        if (it == null || !it.hasItemMeta()) return false;
        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        String s = pdc.get(styleKey, PersistentDataType.STRING);
        if (s == null || !s.startsWith("anim:")) return false;
        Colors.Style st = Colors.Style.load(s);
        String text = pdc.get(textKey, PersistentDataType.STRING);
        if (st == null || text == null) return false;
        String name = Colors.render(text, st, pdc.getOrDefault(fmtKey, PersistentDataType.STRING, ""), f);
        if (name.equals(m.getDisplayName())) return false;
        m.setDisplayName(name);
        it.setItemMeta(m);
        return true;
    }

    private void animateAll() {
        frame++;
        int max = getConfig().getInt("max-animated-per-player", 12);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            int done = 0;
            for (int i = 0; i < inv.getSize() && done < max; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || !it.hasItemMeta()) continue;
                if (animate(it, frame)) {
                    inv.setItem(i, it);
                    done++;
                }
            }
        }
    }

    // ------------------------------------------------------------------ menus

    public Menu main(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        Menu m = new Menu(3, "&8" + Util.small("Dark Names"));
        m.set(11, Util.item(Material.NAME_TAG, "&d&lRename", "&7Colours, gradients and", "&7animated names", "", "&eClick"), e -> startRename(p, null));
        if (hand.getType() != Material.AIR) m.set(13, hand.clone());
        else m.set(13, Util.item(Material.BARRIER, "&cHold an item", "&7Hold the item you want to rename"));
        m.set(15, Util.item(Material.WATER_BUCKET, "&b&lReset", "&7Back to its own name", "", "&eClick"), e -> {
            p.closeInventory();
            Bukkit.dispatchCommand(p, "darknames reset");
        });
        m.fill();
        return m;
    }

    private void startRename(Player p, String text) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            p.closeInventory();
            say(p, "&cHold the item you want to rename.");
            return;
        }
        Edit e = new Edit();
        ItemMeta m = hand.getItemMeta();
        String saved = m == null ? null : m.getPersistentDataContainer().get(styleKey, PersistentDataType.STRING);
        if (saved != null) { // keep editing what it has
            Colors.Style st = Colors.Style.load(saved);
            if (st != null) e.style = st;
            e.fmt = m.getPersistentDataContainer().getOrDefault(fmtKey, PersistentDataType.STRING, "");
        }
        e.text = text != null ? text : nameOf(hand);
        edits.put(p.getUniqueId(), e);
        editor(p).open(p);
    }

    private boolean allowed(Player p, Colors.Style st) {
        return switch (st.kind()) {
            case "gradient" -> p.hasPermission("darknames.gradients");
            case "anim" -> p.hasPermission("darknames.animated");
            case "custom" -> p.hasPermission("darknames.codes");
            default -> true;
        };
    }

    private ItemStack swatch(Material icon, String name, Colors.Style st, Edit e, String... lore) {
        boolean chosen = st.save().equals(e.style.save());
        List<String> l = new ArrayList<>();
        l.add(Colors.render(Colors.strip(e.text).isEmpty() ? "Preview" : e.text, st, e.fmt, frame));
        for (String x : lore) l.add(Util.color(x));
        l.add("");
        l.add(Util.color(chosen ? "&a✔ Chosen" : "&eClick to use"));
        ItemStack it = Util.item(icon, 1, Colors.render(name, st, "l", frame), l);
        if (chosen) {
            ItemMeta m = it.getItemMeta();
            try {
                m.setEnchantmentGlintOverride(true);
            } catch (Throwable ignored) {
            }
            it.setItemMeta(m);
        }
        return it;
    }

    private static final Material[] DYES = {Material.WHITE_DYE, Material.LIGHT_GRAY_DYE, Material.RED_DYE, Material.ORANGE_DYE, Material.YELLOW_DYE,
            Material.LIME_DYE, Material.LIGHT_BLUE_DYE, Material.BLUE_DYE, Material.MAGENTA_DYE, Material.RED_TERRACOTTA, Material.GREEN_DYE,
            Material.CYAN_DYE, Material.BLUE_TERRACOTTA, Material.PURPLE_DYE, Material.BLACK_DYE, Material.PINK_DYE, Material.ORANGE_TERRACOTTA, Material.LIME_TERRACOTTA};

    public Menu editor(Player p) {
        Edit e = edits.get(p.getUniqueId());
        if (e == null) return main(p);
        Menu m = new Menu(6, "&8" + Util.small("Rename"));
        int slot = 0, d = 0;
        // rows 1-2: colours
        for (Map.Entry<String, String> c : Colors.SOLIDS.entrySet()) {
            Colors.Style st = new Colors.Style("solid", c.getKey(), List.of(c.getValue()));
            m.set(slot++, swatch(DYES[d++ % DYES.length], c.getKey(), st, e, "&8" + c.getValue()), ev -> pick(p, st));
        }
        // row 3: gradients
        slot = 18;
        for (Map.Entry<String, List<String>> g : Colors.GRADIENTS.entrySet()) {
            Colors.Style st = new Colors.Style("gradient", g.getKey(), g.getValue());
            m.set(slot++, swatch(Material.PAPER, g.getKey(), st, e, "&8Gradient"), ev -> pick(p, st));
        }
        // row 4: animations (they use the colours of the gradient you picked last)
        slot = 27;
        List<String> base = e.style.kind().equals("solid") || e.style.kind().equals("custom") ? e.style.colors() : e.style.colors();
        for (Map.Entry<String, String[]> a : Colors.ANIMATIONS.entrySet()) {
            List<String> cols = switch (a.getKey()) {
                case "wave" -> e.style.colors().size() > 1 ? e.style.colors() : Colors.GRADIENTS.get("Galaxy");
                case "pulse" -> e.style.colors().size() > 1 ? e.style.colors().subList(0, 2) : List.of(base.isEmpty() ? "#FF55FF" : base.get(0), "#FFFFFF");
                case "shine", "glitch" -> List.of(base.isEmpty() ? "#FFAA00" : base.get(0));
                default -> List.of();
            };
            if (e.style.kind().equals("anim") && e.style.id().equals(a.getKey())) cols = e.style.colors();
            Colors.Style st = new Colors.Style("anim", a.getKey(), cols);
            m.set(slot++, swatch(Material.CLOCK, a.getValue()[0], st, e, "&8Animated: " + a.getValue()[1],
                    "&8Uses the colours you picked before"), ev -> pick(p, st));
        }
        m.set(34, Util.item(Material.WRITABLE_BOOK, "&fYour own gradient", "&7Type two or more colours,", "&7like &f#FF0000 #0000FF"), ev ->
                ask.ask(p, "colours like #FF0000 #0000FF", t -> {
                    List<String> cs = new ArrayList<>();
                    for (String x : t.trim().split("[ ,]+")) if (Colors.isHex(x)) cs.add(x.startsWith("#") ? x : "#" + x);
                    if (cs.size() == 1) e.style = new Colors.Style("solid", "Custom", cs);
                    else if (cs.size() > 1) e.style = new Colors.Style("gradient", "Custom", cs);
                    else say(p, "&cUse colours like #FF0000 #0000FF.");
                }, () -> editor(p).open(p)));
        // row 5: bold, italic...
        String[][] f = {{"l", "Bold", "ANVIL"}, {"o", "Italic", "FEATHER"}, {"n", "Underline", "STRING"}, {"m", "Strikethrough", "SHEARS"}, {"k", "Magic", "ENDER_EYE"}};
        slot = 38;
        for (String[] x : f) {
            boolean on = e.fmt.contains(x[0]);
            m.set(slot++, Util.item(Material.valueOf(x[2]), (on ? "&a" : "&7") + x[1] + (on ? " ON" : " OFF"), "&eClick to switch"), ev -> {
                e.fmt = on ? e.fmt.replace(x[0], "") : e.fmt + x[0];
                editor(p).open(p);
            });
        }
        // bottom row
        m.set(45, Util.item(Material.RED_STAINED_GLASS_PANE, "&cCancel"), ev -> {
            edits.remove(p.getUniqueId());
            p.closeInventory();
        });
        m.set(47, Util.item(Material.NAME_TAG, "&eChange the text", "&7Now: &f" + Colors.strip(e.text),
                "&7You can type colour codes too:", "&f&&ca &f&&#FF8800 &f<gradient:#f00:#00f>", "&7(max " + maxLength() + " letters)"), ev ->
                ask.ask(p, "the new name", t -> {
                    String text = t.trim();
                    if (Colors.length(Colors.strip(Colors.codes(text))) > maxLength()) {
                        say(p, "&cThat's too long (max " + maxLength() + " letters).");
                        return;
                    }
                    if (Colors.hasCodes(text)) {
                        e.style = new Colors.Style("custom", "typed", List.of());
                        e.text = text;
                    } else e.text = text;
                }, () -> editor(p).open(p)));
        ItemStack preview = p.getInventory().getItemInMainHand().clone();
        if (preview.getType() == Material.AIR) preview = new ItemStack(Material.NAME_TAG);
        ItemMeta pm = preview.getItemMeta();
        if (pm != null) {
            pm.setDisplayName(Colors.render(e.text, e.style, e.fmt, frame));
            preview.setItemMeta(pm);
        }
        m.set(49, preview);
        m.set(53, Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lSave the name", "&7Puts it on the item you hold"), ev -> {
            p.closeInventory();
            apply(p, e);
        });
        for (int i = 0; i < 54; i++) if (m.getInventory().getItem(i) == null) m.set(i, Util.glass());
        return m;
    }

    private void pick(Player p, Colors.Style st) {
        Edit e = edits.get(p.getUniqueId());
        if (e == null) return;
        if (!allowed(p, st)) {
            say(p, "&cYou need a rank for " + (st.kind().equals("anim") ? "animated names" : "gradients") + ".");
            return;
        }
        if (e.style.kind().equals("custom")) e.text = Colors.strip(Colors.codes(e.text)); // a preset replaces typed colours
        e.style = st;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
        editor(p).open(p);
    }

    private void apply(Player p, Edit e) {
        edits.remove(p.getUniqueId());
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            say(p, "&cHold the item you want to rename.");
            return;
        }
        if (!allowed(p, e.style)) {
            say(p, "&cYou can't use that style.");
            return;
        }
        if (rename(hand, e.text, e.style, e.fmt)) {
            p.getInventory().setItemInMainHand(hand);
            say(p, "&aRenamed to " + hand.getItemMeta().getDisplayName() + "&a!");
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.4f, 1.6f);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        edits.remove(e.getPlayer().getUniqueId());
    }

    /** "/dark names ..." goes to /darknames. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void longName(PlayerCommandPreprocessEvent e) {
        String low = e.getMessage().toLowerCase(Locale.ROOT).replaceAll(" +", " ");
        if (low.equals("/dark names") || low.startsWith("/dark names "))
            e.setMessage("/darknames" + e.getMessage().replaceAll(" +", " ").substring("/dark names".length()));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("test") && s.hasPermission("darknames.admin")) {
            test(s);
            return true;
        }
        if (sub.equals("reload") && s.hasPermission("darknames.admin")) {
            reloadConfig();
            return say(s, "&aReloaded.");
        }
        if (!(s instanceof Player p)) return say(s, "&7Only players can rename items.");
        if (!p.hasPermission("darknames.use")) return say(p, "&cYou can't rename items.");
        switch (sub) {
            case "" -> main(p).open(p);
            case "rename", "name", "set" -> {
                String text = a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : null;
                if (text != null && Colors.length(Colors.strip(Colors.codes(text))) > maxLength()) return say(p, "&cThat's too long (max " + maxLength() + " letters).");
                if (text != null && Colors.hasCodes(text) && p.hasPermission("darknames.codes")) {
                    // typed with its own colours: put it on right away
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (!rename(hand, text, new Colors.Style("custom", "typed", List.of()), "")) return say(p, "&cHold the item you want to rename.");
                    return say(p, "&aRenamed to " + hand.getItemMeta().getDisplayName() + "&a!");
                }
                startRename(p, text);
            }
            case "reset", "clear" -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) return say(p, "&cHold the item you want to reset.");
                say(p, reset(hand) ? "&7Your &f" + Util.nice(hand.getType()) + " &7has its own name again." : "&7That item already has its own name.");
            }
            default -> {
                say(p, "&f/dn &7- the menu  &f/dn rename [name] &7- rename what you hold  &f/dn reset &7- its own name again");
                say(p, "&7Colours: &f&&a &7codes, &f&&#FF8800&7, &f<gradient:#ff0000:#0000ff>name</gradient>");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (String x : List.of("rename", "reset")) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        return out;
    }

    // ------------------------------------------------------------------ test

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        try {
            String red = Colors.hex("#FF0000");
            if (!red.equals("§x§F§F§0§0§0§0")) fails.add("hex " + red);
            String g = Colors.gradient("Sword", List.of("#FF0000", "#0000FF"), "l");
            if (!g.startsWith(red + "§l") || !g.contains(Colors.hex("#0000FF") + "§l" + "d") || !Colors.strip(g).equals("Sword"))
                fails.add("gradient " + g.replace('§', '&'));
            for (String id : Colors.ANIMATIONS.keySet()) {
                String f0 = Colors.frame(id, "Dark Blade", List.of("#FF0000", "#0000FF"), "", 0);
                boolean moved = false;
                for (int f = 1; f < 40 && !moved; f++) moved = !Colors.frame(id, "Dark Blade", List.of("#FF0000", "#0000FF"), "", f).equals(f0);
                if (!moved) fails.add(id + " doesn't move");
                if (!Colors.strip(f0).equals("Dark Blade")) fails.add(id + " changes the text");
            }
            String typed = Colors.codes("&aHi <gradient:#ff0000:#0000ff>there</gradient> &#FF8800x");
            if (!Colors.strip(typed).equals("Hi there x") || !typed.contains(Colors.hex("#FF8800"))) fails.add("typed codes " + typed.replace('§', '&'));
            if (!Colors.hasCodes("&cHi") || Colors.hasCodes("Hi & bye")) fails.add("finding codes");
            Colors.Style st = new Colors.Style("anim", "rainbow", List.of());
            if (!st.save().equals(Colors.Style.load(st.save()).save())) fails.add("style save");
            // a real item: rename, animate, reset
            ItemStack it = new ItemStack(Material.DIAMOND_SWORD);
            if (!rename(it, "Dark Blade", st, "l") || !Colors.strip(it.getItemMeta().getDisplayName()).equals("Dark Blade")) fails.add("rename");
            String before = it.getItemMeta().getDisplayName();
            boolean changed = false;
            for (int f = 1; f < 20 && !changed; f++) changed = animate(it, frame + f);
            if (!changed || it.getItemMeta().getDisplayName().equals(before)) fails.add("animation on the item");
            if (!nameOf(it).equals("Dark Blade")) fails.add("name kept " + nameOf(it));
            ItemStack plain = new ItemStack(Material.STONE);
            rename(plain, "Rock", new Colors.Style("gradient", "Fire", Colors.GRADIENTS.get("Fire")), "");
            if (animate(plain, 5)) fails.add("a gradient name animated");
            if (!reset(it) || it.getItemMeta().hasDisplayName() || animate(it, 99)) fails.add("reset");
        } catch (Throwable t) {
            fails.add(t.toString());
        }
        String r = fails.isEmpty() ? "DARKNAMES TEST OK (" + Colors.SOLIDS.size() + " colours, " + Colors.GRADIENTS.size() + " gradients, "
                + Colors.ANIMATIONS.size() + " animations)" : "DARKNAMES TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }
}
