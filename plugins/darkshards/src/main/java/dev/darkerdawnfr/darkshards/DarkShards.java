package dev.darkerdawnfr.darkshards;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dark Shards by DarkerDawnFR: DonutSMP-style shards. You earn them by playing and by killing players,
 * and spend them in /shards on 24-hour shard items (the Shard Pickaxe is an amethyst shard that mines 3x3).
 */
public class DarkShards extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, Long> shards = new HashMap<>();
    private final Map<UUID, Integer> activeMinutes = new HashMap<>();
    private final Map<UUID, Location> lastSeen = new HashMap<>();
    private final Map<String, Long> lastKill = new HashMap<>();
    private File file;
    private boolean dirty;
    private ShardItems items;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "shards.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            try {
                shards.put(UUID.fromString(k), y.getLong(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
        items = new ShardItems(this);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(items, this);
        PluginCommand pc = getCommand("shards");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::everyMinute, 20 * 60, 20 * 60);
        Bukkit.getScheduler().runTaskTimer(this, () -> Bukkit.getOnlinePlayers().forEach(items::sweep), 20 * 30, 20 * 30);
        Bukkit.getScheduler().runTaskTimer(this, items::heldParticles, 10, 10);
        Bukkit.getScheduler().runTaskTimer(this, this::save, 20 * 60, 20 * 60);
        if (!items.problems().isEmpty()) getLogger().warning("Shard items skipped: " + items.problems());
    }

    @Override
    public void onDisable() {
        save();
    }

    public String msg(String key) {
        return Util.color(getConfig().getString("prefix", "") + getConfig().getString("messages." + key, key));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(Util.color(getConfig().getString("prefix", "") + text));
        return true;
    }

    // ------------------------------------------------------------------ API (Dark Scoreboard uses shards)

    public long shards(UUID id) {
        return shards.getOrDefault(id, 0L);
    }

    public void giveShards(UUID id, long n) {
        if (n <= 0) return;
        shards.merge(id, n, Long::sum);
        dirty = true;
    }

    public boolean takeShards(UUID id, long n) {
        if (n < 0 || shards(id) < n) return false;
        shards.put(id, shards(id) - n);
        dirty = true;
        return true;
    }

    public void setShards(UUID id, long n) {
        shards.put(id, Math.max(0, n));
        dirty = true;
    }

    public ShardItems items() {
        return items;
    }

    // ------------------------------------------------------------------ earning

    /** Every minute: active (not AFK) players get closer to their next shard. */
    private void everyMinute() {
        int every = Math.max(1, getConfig().getInt("earn.minutes", 5));
        long amount = getConfig().getLong("earn.amount", 1);
        boolean afkCheck = getConfig().getBoolean("earn.skip-afk", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location now = p.getLocation(), before = lastSeen.put(p.getUniqueId(), now);
            boolean active = !afkCheck || before == null || before.getWorld() != now.getWorld()
                    || before.distanceSquared(now) > 1 || before.getYaw() != now.getYaw();
            if (!active) continue;
            int m = activeMinutes.merge(p.getUniqueId(), 1, Integer::sum);
            if (m >= every) {
                activeMinutes.put(p.getUniqueId(), 0);
                giveShards(p.getUniqueId(), amount);
                Chat.actionBar(p, Util.color(getConfig().getString("messages.earned-bar", "&d+{amount} ✦ Shard")
                        .replace("{amount}", String.valueOf(amount))));
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
            }
        }
    }

    /** Shards for killing a player; nothing for killing the same person again too soon. Returns what was given. */
    public long killReward(UUID killer, UUID victim) {
        if (killer == null || killer.equals(victim)) return 0;
        long reward = getConfig().getLong("earn.kill", 5);
        long cooldown = getConfig().getLong("earn.same-player-cooldown-minutes", 10) * 60_000L;
        String key = killer + ">" + victim;
        long now = System.currentTimeMillis();
        Long last = lastKill.get(key);
        if (last != null && now - last < cooldown) return 0;
        lastKill.put(key, now);
        giveShards(killer, reward);
        return reward;
    }

    @EventHandler
    public void death(PlayerDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        long got = killReward(killer.getUniqueId(), e.getEntity().getUniqueId());
        if (got > 0) {
            killer.sendMessage(msg("kill").replace("{amount}", String.valueOf(got)).replace("{player}", e.getEntity().getName()));
            killer.playSound(killer.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (e.getPlayer().isOnline()) items.sweep(e.getPlayer());
        }, 20);
    }

    // ------------------------------------------------------------------ shop

    public Menu shop(Player p) {
        Menu m = new Menu(Math.max(3, Math.min(6, getConfig().getInt("shop-rows", 3))), "&8" + Util.small("Shard shop"));
        long mine = shards(p.getUniqueId());
        m.set(4, Util.item(Material.AMETHYST_CLUSTER, "&d✦ " + mine + " shards", "&7Earn shards by playing",
                "&7(&d" + getConfig().getLong("earn.amount", 1) + "&7 every &d" + getConfig().getInt("earn.minutes", 5) + " min&7)",
                "&7and killing players (&d" + getConfig().getLong("earn.kill", 5) + "&7 each)."));
        for (ShardItems.Def d : items.defs().values()) {
            ItemStack show = items.create(d);
            var meta = show.getItemMeta();
            List<String> lore = new ArrayList<>();
            for (String s : d.lore()) lore.add(Util.color(s.replace("{size}", d.size() + "x" + d.size())));
            lore.add("");
            lore.add(Util.color("&7Price: &d✦ " + d.price() + " shards"));
            lore.add(Util.color("&7Lasts: &f" + hours(d.hours())));
            lore.add("");
            lore.add(Util.color(mine >= d.price() ? "&eClick to buy" : "&cYou need " + (d.price() - mine) + " more shards"));
            meta.setLore(lore);
            show.setItemMeta(meta);
            int slot = Math.max(0, Math.min(m.size() - 1, d.slot()));
            m.set(slot, show, e -> confirm(p, d).open(p));
        }
        m.fill();
        return m;
    }

    private static String hours(double h) {
        return h == Math.floor(h) ? (long) h + " hours" : h + " hours";
    }

    private Menu confirm(Player p, ShardItems.Def d) {
        Menu m = new Menu(3, "&8" + Util.small("Buy " + org.bukkit.ChatColor.stripColor(Util.color(d.name())) + "?"));
        m.set(13, items.create(d));
        m.set(11, Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lConfirm", "&7Pay &d✦ " + d.price() + " shards"), e -> {
            if (buy(p, d)) p.closeInventory();
        });
        m.set(15, Util.item(Material.RED_STAINED_GLASS_PANE, "&c&lCancel"), e -> shop(p).open(p));
        m.fill();
        return m;
    }

    public boolean buy(Player p, ShardItems.Def d) {
        if (p.getInventory().firstEmpty() == -1) {
            say(p, "&cMake room in your inventory first.");
            return false;
        }
        if (!takeShards(p.getUniqueId(), d.price())) {
            say(p, "&cYou need &d✦ " + d.price() + " shards&c, you have " + shards(p.getUniqueId()) + ".");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return false;
        }
        p.getInventory().addItem(items.create(d));
        say(p, "&aYou bought " + d.name() + "&a. It lasts " + hours(d.hours()) + ".");
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
        items.sparkle(p.getLocation().add(0, 1, 0), 20);
        return true;
    }

    // ------------------------------------------------------------------ commands

    @SuppressWarnings("deprecation")
    private OfflinePlayer find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on;
        OfflinePlayer p = Bukkit.getOfflinePlayer(name);
        return p.hasPlayedBefore() ? p : null;
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission("darkshards.admin");
        switch (sub) {
            case "" , "shop" -> {
                if (s instanceof Player p) shop(p).open(p);
                else say(s, "&7/shards balance|give|take|set|giveitem|reload|test");
            }
            case "balance", "bal" -> {
                if (a.length > 1) {
                    OfflinePlayer t = find(a[1]);
                    if (t == null) return say(s, "&cNo player called " + a[1] + ".");
                    return say(s, "&f" + t.getName() + " &7has &d✦ " + shards(t.getUniqueId()) + " shards&7.");
                }
                if (s instanceof Player p) say(s, "&7You have &d✦ " + shards(p.getUniqueId()) + " shards&7.");
            }
            case "pay" -> {
                if (!(s instanceof Player p) || a.length < 3) return say(s, "&7Usage: /shards pay <player> <amount>");
                OfflinePlayer t = find(a[1]);
                long n = num(a[2]);
                if (t == null || t.getUniqueId().equals(p.getUniqueId())) return say(s, "&cPick another player.");
                if (n <= 0) return say(s, "&c" + a[2] + " is not a number.");
                if (!takeShards(p.getUniqueId(), n)) return say(s, "&cYou don't have " + n + " shards.");
                giveShards(t.getUniqueId(), n);
                say(s, "&7You gave &f" + t.getName() + " &d✦ " + n + " shards&7.");
                if (t.getPlayer() != null) say(t.getPlayer(), "&f" + p.getName() + " &7gave you &d✦ " + n + " shards&7.");
            }
            case "give", "take", "set" -> {
                if (!admin) return say(s, "&cOnly ops can do that.");
                if (a.length < 3) return say(s, "&7Usage: /shards " + sub + " <player> <amount>");
                OfflinePlayer t = find(a[1]);
                long n = num(a[2]);
                if (t == null) return say(s, "&cNo player called " + a[1] + ".");
                if (n < 0) return say(s, "&c" + a[2] + " is not a number.");
                switch (sub) {
                    case "give" -> giveShards(t.getUniqueId(), n);
                    case "take" -> setShards(t.getUniqueId(), shards(t.getUniqueId()) - n);
                    default -> setShards(t.getUniqueId(), n);
                }
                say(s, "&f" + t.getName() + " &7now has &d✦ " + shards(t.getUniqueId()) + " shards&7.");
            }
            case "giveitem" -> {
                if (!admin) return say(s, "&cOnly ops can do that.");
                if (a.length < 3) return say(s, "&7Usage: /shards giveitem <player> <" + String.join("|", items.defs().keySet()) + ">");
                Player t = Bukkit.getPlayerExact(a[1]);
                ShardItems.Def d = items.defs().get(a[2].toLowerCase(Locale.ROOT));
                if (t == null || d == null) return say(s, "&cPlayer not online or no item called " + a[2] + ".");
                t.getInventory().addItem(items.create(d));
                say(s, "&7Gave " + d.name() + " &7to &f" + t.getName() + "&7.");
            }
            case "reload" -> {
                if (!admin) return true;
                reloadConfig();
                items.reload();
                say(s, "&aReloaded. Items: " + items.defs().keySet() + (items.problems().isEmpty() ? "" : " &cProblems: " + items.problems()));
            }
            case "test" -> {
                if (admin) test(s);
            }
            default -> say(s, "&7/shards  /shards balance  /shards pay <player> <amount>");
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try {
            if (!items.problems().isEmpty()) fails.add("item problems " + items.problems());
            for (String need : List.of("pickaxe", "axe", "shovel", "hoe")) if (!items.defs().containsKey(need)) fails.add("no " + need);
            ShardItems.Def pick = items.defs().get("pickaxe");
            if (pick != null) {
                if (pick.material() != Material.AMETHYST_SHARD) fails.add("pickaxe is " + pick.material());
                ItemStack it = items.create(pick);
                if (items.def(it) == null || !items.def(it).id().equals("pickaxe")) fails.add("pickaxe not recognised");
                long left = items.expires(it) - System.currentTimeMillis();
                if (Math.abs(left - 24 * 3_600_000L) > 60_000) fails.add("pickaxe doesn't last 24h");
                if (items.expired(it)) fails.add("new pickaxe expired");
                if (!items.expired(items.create(pick, System.currentTimeMillis() - 1))) fails.add("old pickaxe not expired");
                String lore = String.join("|", it.getItemMeta().getLore());
                if (!lore.contains("Expires in") || !lore.contains("3x3")) fails.add("lore " + lore);
                boolean tool;
                try {
                    tool = it.getItemMeta().hasTool() && !it.getItemMeta().getTool().getRules().isEmpty();
                } catch (Throwable notThere) {
                    tool = true; // older version: no tool components to check
                }
                if (!tool) fails.add("pickaxe can't mine like a pickaxe");
            }
            for (ShardItems.Def d : items.defs().values()) if (d.hours() != 24) fails.add(d.id() + " lasts " + d.hours() + "h");
            giveShards(a, 100);
            if (!takeShards(a, 60) || takeShards(a, 60) || shards(a) != 40) fails.add("shards " + shards(a));
            if (killReward(b, a) != 5 || killReward(b, a) != 0 || killReward(b, b) != 0 || shards(b) != 5) fails.add("kill shards");
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            shards.remove(a);
            shards.remove(b);
        }
        String r = fails.isEmpty() ? "DARKSHARDS TEST OK (items: " + items.defs().keySet() + ")" : "DARKSHARDS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    private void save() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : shards.entrySet()) y.set(e.getKey().toString(), e.getValue());
        try {
            getDataFolder().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            getLogger().warning("Could not save shards: " + e);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("balance", "pay"));
            if (s.hasPermission("darkshards.admin")) out.addAll(List.of("give", "take", "set", "giveitem", "reload"));
        } else if (a.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("giveitem")) {
            out.addAll(items.defs().keySet());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
