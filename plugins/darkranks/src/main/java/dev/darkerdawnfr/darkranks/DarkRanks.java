package dev.darkerdawnfr.darkranks;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dark Ranks by DarkerDawnFR: ranks like LuckPerms, but simple.
 * Each rank has permissions (and gets the ranks below it), a prefix with a custom icon shown in chat and the tab
 * list (like DonutSMP's media tag), a price in money, and perks. /rank shop to buy, /rank add <player> <rank>,
 * /rank edit (ops) to change everything in game.
 */
public class DarkRanks extends JavaPlugin implements Listener, TabCompleter {
    public record Rank(String id, String name, String prefix, int weight, double price, boolean shop, Material icon,
                       List<String> permissions, List<String> perks, String inherit) {}

    private final Map<String, Rank> ranks = new HashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private YamlConfiguration players;
    private File playersFile;
    private Ask ask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getInt("config-version", 1) < 2) {
            // newer version: ranks get /shop premium, and /media has its requirements
            String[][] add = {{"vip", "darkshop.premium"}, {"vip", "darkshop.discount.vip"}, {"vip+", "darkshop.discount.vipplus"},
                    {"mvp", "darkshop.discount.mvp"}, {"mvp+", "darkshop.discount.mvpplus"}, {"legend", "darkshop.discount.legend"}};
            for (String[] x : add) {
                String path = "ranks." + x[0] + ".permissions";
                if (!getConfig().isSet("ranks." + x[0])) continue;
                List<String> l = new ArrayList<>(getConfig().getStringList(path));
                if (!l.contains(x[1])) l.add(x[1]);
                getConfig().set(path, l);
            }
            var defs = getConfig().getDefaults();
            if (defs != null && !getConfig().isSet("media") && defs.isSet("media")) getConfig().set("media", defs.get("media"));
            getConfig().set("config-version", 2);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 3) {
            // /ec (ender chest anywhere) for every rank in the shop, from VIP up
            if (getConfig().isSet("ranks.vip")) {
                List<String> l = new ArrayList<>(getConfig().getStringList("ranks.vip.permissions"));
                if (!l.contains("darkperks.ec")) l.add("darkperks.ec");
                getConfig().set("ranks.vip.permissions", l);
                List<String> perks = new ArrayList<>(getConfig().getStringList("ranks.vip.perks"));
                if (perks.stream().noneMatch(x -> x.contains("/ec"))) perks.add(0, "&f/ec &7- ender chest anywhere");
                getConfig().set("ranks.vip.perks", perks);
            }
            getConfig().set("config-version", 3);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 4) {
            // more homes and a better respawn kit for better ranks
            String[][] add = {{"vip", "darkhomes.homes.7", "darkessentials.starter.vip", "&f7 homes", "&fBetter respawn kit (iron sword)"},
                    {"vip+", "darkhomes.homes.9", "darkessentials.starter.vipplus", "&f9 homes", "&fRespawn kit: iron armour and tools"},
                    {"mvp", "darkhomes.homes.12", "darkessentials.starter.mvp", "&f12 homes", "&fRespawn kit: + shield, golden carrots"},
                    {"mvp+", "darkhomes.homes.15", "darkessentials.starter.mvpplus", "&f15 homes", "&fRespawn kit: diamond sword and pieces"},
                    {"legend", "darkhomes.homes.20", "darkessentials.starter.legend", "&f20 homes", "&fRespawn kit: full diamond"}};
            for (String[] x : add) {
                if (!getConfig().isSet("ranks." + x[0])) continue;
                List<String> l = new ArrayList<>(getConfig().getStringList("ranks." + x[0] + ".permissions"));
                for (String perm : new String[]{x[1], x[2]}) if (!l.contains(perm)) l.add(perm);
                getConfig().set("ranks." + x[0] + ".permissions", l);
                List<String> perks = new ArrayList<>(getConfig().getStringList("ranks." + x[0] + ".perks"));
                perks.removeIf(pk -> pk.matches(".*\\d+ homes.*"));
                perks.add(x[3]);
                perks.add(x[4]);
                getConfig().set("ranks." + x[0] + ".perks", perks);
            }
            getConfig().set("config-version", 4);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 5) {
            // /sell boost for ranks
            String[][] add = {{"vip", "vip", "1.05"}, {"vip+", "vipplus", "1.1"}, {"mvp", "mvp", "1.15"}, {"mvp+", "mvpplus", "1.2"}, {"legend", "legend", "1.25"}};
            for (String[] x : add) {
                if (!getConfig().isSet("ranks." + x[0])) continue;
                List<String> l = new ArrayList<>(getConfig().getStringList("ranks." + x[0] + ".permissions"));
                if (!l.contains("darkshop.multiplier." + x[1])) l.add("darkshop.multiplier." + x[1]);
                getConfig().set("ranks." + x[0] + ".permissions", l);
                List<String> perks = new ArrayList<>(getConfig().getStringList("ranks." + x[0] + ".perks"));
                perks.removeIf(pk -> pk.contains("/sell money"));
                perks.add("&f" + x[2] + "x /sell money");
                getConfig().set("ranks." + x[0] + ".perks", perks);
            }
            getConfig().set("config-version", 5);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 6) {
            // the owner rank gets everything ops get
            if (getConfig().isSet("ranks.owner")) {
                List<String> l = new ArrayList<>(getConfig().getStringList("ranks.owner.permissions"));
                if (!l.contains("*")) l.add("*");
                getConfig().set("ranks.owner.permissions", l);
            }
            if (!getConfig().isSet("owners")) getConfig().set("owners", new ArrayList<String>());
            getConfig().set("config-version", 6);
            saveConfig();
        }
        playersFile = new File(getDataFolder(), "players.yml");
        players = YamlConfiguration.loadConfiguration(playersFile);
        load();
        ask = new Ask(this);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("rank", "media")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, PermissionAttachment> e : attachments.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.removeAttachment(e.getValue());
        }
        attachments.clear();
    }

    private String prefix() {
        return Util.color(getConfig().getString("message-prefix", "&8[&6Ranks&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
    }

    // ------------------------------------------------------------------ ranks

    public void load() {
        ranks.clear();
        ConfigurationSection cs = getConfig().getConfigurationSection("ranks");
        if (cs == null) return;
        for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null) continue;
            Material icon = Util.material(c.getString("icon", "PAPER"));
            ranks.put(id.toLowerCase(Locale.ROOT), new Rank(id.toLowerCase(Locale.ROOT), c.getString("name", id), c.getString("prefix", ""),
                    c.getInt("weight", 0), c.getDouble("price", 0), c.getBoolean("shop", false), icon == null ? Material.PAPER : icon,
                    c.getStringList("permissions"), c.getStringList("perks"), c.getString("inherit", "")));
        }
    }

    public List<Rank> sorted() {
        List<Rank> l = new ArrayList<>(ranks.values());
        l.sort((a, b) -> Integer.compare(a.weight(), b.weight()));
        return l;
    }

    public Rank defaultRank() {
        Rank r = ranks.get(getConfig().getString("default-rank", "member"));
        return r != null ? r : sorted().isEmpty() ? null : sorted().get(0);
    }

    public Rank rankOf(UUID id) {
        Rank r = ranks.get(players.getString(id.toString(), ""));
        return r != null ? r : defaultRank();
    }

    /** All permissions of a rank, with the ranks it inherits from. */
    public Set<String> permissions(Rank r) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        while (r != null && seen.add(r.id())) {
            out.addAll(r.permissions());
            r = ranks.get(r.inherit().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    public void setRank(UUID id, Rank r) {
        Rank def = defaultRank();
        players.set(id.toString(), r == null || r == def ? null : r.id());
        try {
            getDataFolder().mkdirs();
            players.save(playersFile);
        } catch (IOException e) {
            getLogger().warning("Could not save players.yml: " + e);
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) apply(p);
    }

    /** Gives an online player the permissions of their rank. */
    public void apply(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException ignored) {
            }
        }
        Rank r = rankOf(p.getUniqueId());
        PermissionAttachment a = p.addAttachment(this);
        if (r != null) for (String perm : permissions(r)) {
            boolean on = !perm.startsWith("-");
            String node = on ? perm : perm.substring(1);
            for (String x : expand(node)) a.setPermission(x, on);
        }
        attachments.put(p.getUniqueId(), a);
        p.recalculatePermissions();
        try {
            p.updateCommands();
        } catch (Throwable ignored) {
        }
    }

    /** "*" = everything ops get (every plugin's op permissions); "plugin.*" = every permission starting with "plugin.". */
    public Set<String> expand(String node) {
        Set<String> out = new LinkedHashSet<>();
        out.add(node);
        if (node.equals("*")) {
            for (org.bukkit.permissions.Permission p : Bukkit.getPluginManager().getPermissions())
                if (p.getDefault() == org.bukkit.permissions.PermissionDefault.OP) out.add(p.getName());
        } else if (node.endsWith(".*")) {
            String pre = node.substring(0, node.length() - 1).toLowerCase(Locale.ROOT);
            for (org.bukkit.permissions.Permission p : Bukkit.getPluginManager().getPermissions())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
        }
        return out;
    }

    /** The server owner's name(s): MC Hoster writes owner.txt, or list them under "owners" in config.yml. */
    public List<String> owners() {
        List<String> out = new ArrayList<>(getConfig().getStringList("owners"));
        File f = new File(getDataFolder(), "owner.txt");
        if (f.exists()) try {
            for (String l : java.nio.file.Files.readAllLines(f.toPath())) if (!l.isBlank()) out.add(l.trim());
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Owners get the owner rank when they join (once they have something lower). */
    private void ownerCheck(Player p) {
        Rank owner = ranks.get(getConfig().getString("owner-rank", "owner").toLowerCase(Locale.ROOT));
        if (owner == null) return;
        boolean isOwner = false;
        for (String n : owners()) if (n.equalsIgnoreCase(p.getName()) || n.equalsIgnoreCase(p.getUniqueId().toString())) isOwner = true;
        if (!isOwner) return;
        Rank now = rankOf(p.getUniqueId());
        if (now == null || now.weight() < owner.weight()) {
            setRank(p.getUniqueId(), owner);
            p.sendMessage(Util.color("&8[&cRanks&8] &7You're the server owner, so you're now " + owner.name() + "&7. Edit things with &f/shop edit&7, &f/rank edit&7..."));
        }
    }

    // used by Dark Chat and Dark Scoreboard
    public String prefix(UUID id) {
        Rank r = rankOf(id);
        return r == null ? "" : Util.color(r.prefix());
    }

    public String prefix(Player p) {
        return prefix(p.getUniqueId());
    }

    public String rankName(UUID id) {
        Rank r = rankOf(id);
        return r == null ? "" : Util.color(r.name());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent e) {
        ownerCheck(e.getPlayer());
        apply(e.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        attachments.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ buying

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

    /** Buys a rank with money. Returns null if it worked, otherwise why not. */
    public String buy(UUID id, Rank r) {
        Rank now = rankOf(id);
        if (!r.shop()) return "That rank isn't for sale.";
        if (now != null && now.weight() >= r.weight()) return "You already have " + (now == r ? "this rank" : "a better rank") + ".";
        if (Bukkit.getPluginManager().getPlugin("DarkShop") == null) return "Buying ranks needs Dark Shop (the money).";
        if (!Boolean.TRUE.equals(call("DarkShop", "take", id, r.price()))) return "You need " + Util.money(r.price()) + " for " + ChatColor.stripColor(Util.color(r.name())) + ".";
        setRank(id, r);
        return null;
    }

    public Menu shop(Player p) {
        List<Rank> forSale = new ArrayList<>();
        for (Rank r : sorted()) if (r.shop()) forSale.add(r);
        Menu m = new Menu(3, "&8" + Util.small("Rank shop"));
        Rank mine = rankOf(p.getUniqueId());
        int start = 13 - Math.min(forSale.size(), 7) / 2;
        for (int i = 0; i < forSale.size() && i < 7; i++) {
            Rank r = forSale.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("&7Shows as: " + r.prefix() + "&f" + p.getName());
            lore.add("&7Price: &a" + Util.money(r.price()));
            lore.add("");
            lore.add("&f&lPerks");
            for (String perk : r.perks()) lore.add("&8• " + perk);
            lore.add("");
            boolean have = mine != null && mine.weight() >= r.weight();
            lore.add(have ? (mine == r ? "&aYour rank" : "&7You have a better rank") : "&eClick to buy");
            m.set(start + i, Util.item(r.icon(), 1, r.name(), lore), e -> {
                if (!have) confirm(p, r).open(p);
            });
        }
        m.set(22, Util.item(Material.NAME_TAG, "&fYour rank: " + (mine == null ? "none" : mine.name()),
                "&7Shows as: " + (mine == null ? "" : mine.prefix()) + "&f" + p.getName()));
        m.fill();
        return m;
    }

    private Menu confirm(Player p, Rank r) {
        Menu m = new Menu(3, "&8" + Util.small("Buy " + ChatColor.stripColor(Util.color(r.name())) + "?"));
        m.set(13, Util.item(r.icon(), r.name(), "&7Price: &a" + Util.money(r.price())));
        m.set(11, Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lConfirm", "&7Pay &a" + Util.money(r.price())), e -> {
            String why = buy(p.getUniqueId(), r);
            if (why != null) {
                say(p, "&c" + why);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return;
            }
            p.closeInventory();
            say(p, "&aYou're now " + r.name() + "&a! Your perks are ready.");
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            if (getConfig().getBoolean("announce-buys", true)) {
                if (Bukkit.getPluginManager().getPlugin("DarkAnnounce") != null)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "donor " + p.getName() + " " + ChatColor.stripColor(Util.color(r.name())) + " rank");
                else Bukkit.broadcastMessage(prefix() + Util.color("&f" + p.getName() + " &7bought " + r.name() + "&7!"));
            }
        });
        m.set(15, Util.item(Material.RED_STAINED_GLASS_PANE, "&c&lCancel"), e -> shop(p).open(p));
        m.fill();
        return m;
    }

    // ------------------------------------------------------------------ /rank edit

    private void set(String id, String key, Object v) {
        getConfig().set("ranks." + id + "." + key, v);
        saveConfig();
        load();
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
    }

    public Menu editor(Player p) {
        Menu m = new Menu(4, "&8" + Util.small("Rank editor"));
        int slot = 10;
        for (Rank r : sorted()) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 27) break;
            m.set(slot++, Util.item(r.icon(), 1, r.name(), List.of("&7Id: &f" + r.id(), "&7Shows as: " + r.prefix() + "&fName",
                    "&7Order: &f" + r.weight() + "  &7Price: &a" + Util.money(r.price()), r.shop() ? "&aIn the shop" : "&7Not in the shop",
                    "&7Permissions: &f" + permissions(r).size(), "", "&eClick to edit")), e -> rankEditor(p, r.id()).open(p));
        }
        m.set(31, Util.item(Material.LIME_DYE, "&aNew rank", "&7You'll type a short id, e.g. elite"), e -> ask.ask(p, "a short id for the rank (letters only)", t -> {
            String id = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_+]", "");
            if (id.isEmpty() || ranks.containsKey(id)) {
                say(p, "&cPick another id.");
                return;
            }
            set(id, "name", "&f&l" + id.toUpperCase(Locale.ROOT));
            set(id, "prefix", "&f&l" + id.toUpperCase(Locale.ROOT) + " &r");
            set(id, "weight", sorted().isEmpty() ? 0 : sorted().get(sorted().size() - 1).weight() + 10);
            set(id, "price", 0);
            set(id, "shop", false);
            set(id, "icon", "PAPER");
            set(id, "inherit", getConfig().getString("default-rank", "member"));
            set(id, "permissions", new ArrayList<String>());
            set(id, "perks", new ArrayList<String>());
        }, () -> editor(p).open(p)));
        m.fill();
        return m;
    }

    public Menu rankEditor(Player p, String id) {
        Rank r = ranks.get(id);
        if (r == null) return editor(p);
        Menu m = new Menu(6, "&8" + Util.small("Edit " + id));
        Runnable back = () -> rankEditor(p, id).open(p);
        m.set(4, Util.item(r.icon(), 1, r.name(), List.of("&7Shows as: " + r.prefix() + "&f" + p.getName())));
        m.set(10, Util.item(Material.NAME_TAG, "&fName: " + r.name(), "", "&eClick to change"),
                e -> ask.ask(p, "the rank's name (& colours work)", t -> set(id, "name", t), back));
        m.set(11, Util.item(Material.OAK_SIGN, "&fText before names: " + r.prefix() + "&fName", "&7Put a custom icon in it,",
                        "&7e.g. &6&l♛ OWNER &7or &d&l▶ MEDIA", "", "&eClick to change"),
                e -> ask.ask(p, "the text before names, e.g. &6&l♛ OWNER (a space at the end is added)", t -> set(id, "prefix", t.endsWith(" ") ? t : t + " "), back));
        m.set(12, Util.item(Material.GOLD_INGOT, "&fPrice: &a" + Util.money(r.price()), "", "&eClick to change"),
                e -> ask.ask(p, "the price (e.g. 50k)", t -> {
                    double v = Util.parseMoney(t);
                    if (Double.isNaN(v) || v < 0) say(p, "&c" + t + " isn't a price.");
                    else set(id, "price", v);
                }, back));
        m.set(13, Util.item(r.icon(), "&fShop icon", "&7Hold an item and click"), e -> {
            Material mat = p.getInventory().getItemInMainHand().getType();
            if (mat != Material.AIR) set(id, "icon", mat.name());
            back.run();
        });
        m.set(14, Util.item(r.shop() ? Material.LIME_DYE : Material.GRAY_DYE, r.shop() ? "&aIn /rank shop" : "&7Not for sale",
                "", "&eClick to switch"), e -> {
            set(id, "shop", !r.shop());
            back.run();
        });
        m.set(15, Util.item(Material.ANVIL, "&fOrder: &e" + r.weight(), "&7Higher = better rank.", "&7You can only buy ranks above yours.", "", "&eClick to change"),
                e -> ask.ask(p, "the order number", t -> {
                    try {
                        set(id, "weight", Integer.parseInt(t.trim()));
                    } catch (NumberFormatException ex) {
                        say(p, "&cThat isn't a number.");
                    }
                }, back));
        m.set(16, Util.item(Material.CHAIN, "&fAlso gets: " + (r.inherit().isEmpty() ? "&7nothing" : ranks.containsKey(r.inherit()) ? ranks.get(r.inherit()).name() : r.inherit()),
                        "&7Gets every permission of that rank too.", "", "&eClick to change"),
                e -> ask.ask(p, "the rank it includes (" + String.join(", ", ranks.keySet()) + ", or none)",
                        t -> set(id, "inherit", t.equalsIgnoreCase("none") ? "" : t.toLowerCase(Locale.ROOT)), back));
        List<String> perms = new ArrayList<>();
        for (String s : r.permissions()) perms.add("&f" + s);
        if (perms.isEmpty()) perms.add("&7none of its own");
        perms.addAll(List.of("", "&eLeft-click: add one", "&eRight-click: remove one", "&7e.g. darkperks.fly, darkhomes.homes.7"));
        m.set(28, Util.item(Material.BOOK, 1, "&fPermissions", perms), e -> {
            boolean add = e.getClick() != ClickType.RIGHT;
            ask.ask(p, add ? "a permission to add" : "a permission to remove", t -> {
                List<String> now = new ArrayList<>(r.permissions());
                if (add) now.add(t.trim());
                else now.remove(t.trim());
                set(id, "permissions", now);
            }, back);
        });
        List<String> perks = new ArrayList<>();
        for (String s : r.perks()) perks.add("&8• " + s);
        perks.addAll(List.of("", "&eClick to change (| between lines)"));
        m.set(29, Util.item(Material.WRITABLE_BOOK, 1, "&fPerks shown in the shop", perks),
                e -> ask.ask(p, "the perks, | between lines", t -> set(id, "perks", Arrays.asList(t.split("\\|"))), back));
        m.set(31, Util.item(Material.PAINTING, "&fIcon ideas", "&f✦ ✪ ♛ ★ ☠ ⚔ ❤ ☀ ⚡ ▶ ♦ ✿ ☯ ♫ ✔ ☄ ⛏ ⌛",
                "&7Copy one into the text before names."));
        m.set(45, Util.item(Material.ARROW, "&fBack"), e -> editor(p).open(p));
        m.set(53, Util.item(Material.TNT, "&cDelete this rank", "&cShift-click to delete"), e -> {
            if (!e.isShiftClick() || r == defaultRank()) return;
            getConfig().set("ranks." + id, null);
            saveConfig();
            load();
            editor(p).open(p);
        });
        m.fill();
        return m;
    }

    // ------------------------------------------------------------------ commands

    @SuppressWarnings("deprecation")
    private OfflinePlayer find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on;
        OfflinePlayer p = Bukkit.getOfflinePlayer(name);
        return p.hasPlayedBefore() ? p : null;
    }

    /** /media: what you need for the Media rank. */
    public Menu media(Player p) {
        Menu m = new Menu(3, "&8" + Util.small("Media rank"));
        Rank media = ranks.get("media");
        String tag = media == null ? "&d&l▶ MEDIA " : media.prefix();
        List<String> req = new ArrayList<>(List.of("&7To get " + tag + "&7you need:", ""));
        for (String r : getConfig().getStringList("media.requirements")) req.add("&8• " + r);
        m.set(13, Util.item(Material.RED_DYE, 1, tag.trim().isEmpty() ? "&d&lMEDIA" : tag, req));
        List<String> how = new ArrayList<>(getConfig().getStringList("media.how-to-apply"));
        m.set(11, Util.item(Material.WRITABLE_BOOK, 1, "&fHow to apply", how));
        List<String> perks = new ArrayList<>(getConfig().getStringList("media.perks"));
        m.set(15, Util.item(Material.NETHER_STAR, 1, "&fWhat you get", perks));
        Rank mine = rankOf(p.getUniqueId());
        if (mine == media && media != null) m.set(22, Util.item(Material.LIME_DYE, "&aYou're Media!", "&7Thanks for making content."));
        m.fill();
        return m;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("media")) {
            if (s instanceof Player p) media(p).open(p);
            else for (String r : getConfig().getStringList("media.requirements")) s.sendMessage(Util.color(r));
            return true;
        }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "shop";
        boolean admin = s.hasPermission("darkranks.admin");
        switch (sub) {
            case "shop", "buy" -> {
                if (s instanceof Player p) shop(p).open(p);
            }
            case "add", "set", "give" -> {
                if (!admin) return say(s, "&cOnly ops can give ranks.");
                if (a.length < 3) return say(s, "&7Usage: /rank add <player> <" + String.join("|", ranks.keySet()) + ">");
                OfflinePlayer t = find(a[1]);
                Rank r = ranks.get(a[2].toLowerCase(Locale.ROOT));
                if (t == null) return say(s, "&cNo player called " + a[1] + ".");
                if (r == null) return say(s, "&cNo rank called " + a[2] + ". Ranks: " + String.join(", ", ranks.keySet()));
                setRank(t.getUniqueId(), r);
                say(s, "&f" + t.getName() + " &7is now " + r.name() + "&7.");
                if (t.getPlayer() != null && t.getPlayer() != s) say(t.getPlayer(), "&7You're now " + r.name() + "&7!");
            }
            case "remove", "reset" -> {
                if (!admin) return true;
                if (a.length < 2) return say(s, "&7Usage: /rank remove <player>");
                OfflinePlayer t = find(a[1]);
                if (t == null) return say(s, "&cNo player called " + a[1] + ".");
                setRank(t.getUniqueId(), null);
                say(s, "&f" + t.getName() + " &7is back to " + (defaultRank() == null ? "no rank" : defaultRank().name()) + "&7.");
            }
            case "list" -> {
                for (Rank r : sorted())
                    s.sendMessage(Util.color("&8 - " + r.prefix() + "&7(" + r.id() + ", " + (r.shop() ? Util.money(r.price()) : "not for sale") + ")"));
            }
            case "info" -> {
                OfflinePlayer t = a.length > 1 ? find(a[1]) : s instanceof Player p ? p : null;
                if (t == null) return say(s, "&7Usage: /rank info <player>");
                Rank r = rankOf(t.getUniqueId());
                say(s, "&f" + t.getName() + "&7: " + (r == null ? "no rank" : r.name()));
            }
            case "edit", "editor" -> {
                if (!admin) return say(s, "&cOnly ops can edit ranks.");
                if (s instanceof Player p) editor(p).open(p);
            }
            case "reload" -> {
                if (!admin) return true;
                reloadConfig();
                load();
                for (Player p : Bukkit.getOnlinePlayers()) apply(p);
                say(s, "&aReloaded. Ranks: " + ranks.keySet());
            }
            case "test" -> {
                if (admin) test(s);
            }
            default -> {
                say(s, "&f/rank shop &8| &f/rank info [player] &8| &f/rank list");
                if (admin) say(s, "&8/rank add <player> <rank> | /rank remove <player> | /rank edit | /rank reload");
            }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID id = UUID.nameUUIDFromBytes("darkranks-test".getBytes());
        String backup = getConfig().saveToString();
        try {
            long inShop = ranks.values().stream().filter(Rank::shop).count();
            if (inShop != 5) fails.add(inShop + " ranks in the shop, not 5");
            for (String staff : List.of("owner", "admin", "mod", "media")) if (!ranks.containsKey(staff) || ranks.get(staff).shop()) fails.add("staff rank " + staff);
            if (!rankOf(id).id().equals(getConfig().getString("default-rank", "member"))) fails.add("default rank");
            Rank legend = ranks.get("legend");
            Set<String> perms = permissions(legend);
            if (!perms.contains("darkperks.craft") || !perms.contains("darkperks.fly") || !perms.contains("darkperks.trail")) fails.add("legend perms " + perms);
            if (permissions(ranks.get("vip")).contains("darkperks.fly")) fails.add("vip shouldn't fly");
            if (!perms.contains("darkshop.multiplier.legend") || !perms.contains("darkshop.multiplier.vip")) fails.add("no sell boost");
            if (!permissions(ranks.get("owner")).contains("*") || !expand("*").contains("darkranks.admin")) fails.add("owner doesn't get everything");
            if (expand("*").contains("darkvc.bypass")) fails.add("* gives bypass permissions");
            if (!permissions(ranks.get("vip")).contains("darkperks.ec")) fails.add("vip should have /ec");
            if (!perms.contains("darkhomes.homes.20") || !perms.contains("darkessentials.starter.legend")) fails.add("legend homes/kit");
            if (!perms.contains("darkshop.premium") || !perms.contains("darkshop.discount.legend")) fails.add("legend premium shop");
            if (getConfig().getStringList("media.requirements").size() < 2) fails.add("media requirements");
            if (!ChatColor.stripColor(prefix(id)).isBlank()) fails.add("member prefix should be empty");
            setRank(id, ranks.get("owner"));
            if (!ChatColor.stripColor(prefix(id)).contains("OWNER")) fails.add("owner prefix " + prefix(id));
            setRank(id, null);
            if (Bukkit.getPluginManager().getPlugin("DarkShop") != null && String.valueOf(call("DarkShop", "economyMode")).startsWith("built-in")) {
                Rank vip = ranks.get("vip");
                if (buy(id, vip) == null) fails.add("bought without money");
                call("DarkShop", "give", id, vip.price() + 10);
                String why = buy(id, vip);
                if (why != null || rankOf(id) != vip) fails.add("buying: " + why);
                Object left = call("DarkShop", "balance", id);
                if (!(left instanceof Number n) || Math.abs(n.doubleValue() - 10) > 1e-6) fails.add("price taken " + left);
                if (buy(id, vip) == null) fails.add("bought the same rank twice");
                call("DarkShop", "take", id, 10.0);
            }
            set("vip", "price", 777.0);
            reloadConfig();
            load();
            if (ranks.get("vip").price() != 777) fails.add("editor price");
            editor(null);
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            setRank(id, null);
            try {
                java.nio.file.Files.writeString(new File(getDataFolder(), "config.yml").toPath(), backup);
            } catch (IOException ignored) {
            }
            reloadConfig();
            load();
        }
        String r = fails.isEmpty() ? "DARKRANKS TEST OK (" + ranks.size() + " ranks)" : "DARKRANKS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("darkranks.admin");
        if (a.length == 1) {
            out.addAll(List.of("shop", "info", "list"));
            if (admin) out.addAll(List.of("add", "remove", "edit", "reload"));
        } else if (a.length == 2 && admin && List.of("add", "set", "remove", "info").contains(a[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 3 && admin && List.of("add", "set").contains(a[0].toLowerCase(Locale.ROOT))) out.addAll(ranks.keySet());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
