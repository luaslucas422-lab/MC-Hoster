package dev.darkerdawnfr.darkessentials;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dark Essentials by DarkerDawnFR: the basics every server needs.
 * /spawn, /setspawn, /back, kits (/kit, /kits), join / leave / first-join messages and a welcome title,
 * /afk with auto-AFK (and an optional AFK kick), /rules.
 */
public class DarkEssentials extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, Location> back = new HashMap<>();
    private final Map<UUID, Long> active = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    private final Set<UUID> backing = new HashSet<>();
    private YamlConfiguration data;
    private File dataFile;
    private Tp tp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tp = new Tp(this);
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("spawn", "setspawn", "back", "kit", "afk", "rules", "darkessentials")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::afkCheck, 20 * 20, 20 * 20);
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&bServer&8] "));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
        return true;
    }

    private void saveData() {
        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e);
        }
    }

    // ------------------------------------------------------------------ spawn

    public Location spawn() {
        ConfigurationSection c = data.getConfigurationSection("spawn");
        if (c != null) {
            World w = Bukkit.getWorld(c.getString("world", ""));
            if (w != null) return new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), (float) c.getDouble("pitch"));
        }
        World w = Bukkit.getWorlds().get(0);
        return w.getSpawnLocation().add(0.5, 0, 0.5);
    }

    public void setSpawn(Location l) {
        data.set("spawn.world", l.getWorld().getName());
        data.set("spawn.x", l.getX());
        data.set("spawn.y", l.getY());
        data.set("spawn.z", l.getZ());
        data.set("spawn.yaw", l.getYaw());
        data.set("spawn.pitch", l.getPitch());
        saveData();
        try {
            l.getWorld().setSpawnLocation(l);
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void respawn(PlayerRespawnEvent e) {
        if (!getConfig().getBoolean("spawn.respawn-at-spawn", true) || !data.isSet("spawn")) return;
        try {
            if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        } catch (Throwable ignored) {
        }
        e.setRespawnLocation(spawn());
    }

    // ------------------------------------------------------------------ /back

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(PlayerTeleportEvent e) {
        if (backing.contains(e.getPlayer().getUniqueId())) return;
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c == PlayerTeleportEvent.TeleportCause.COMMAND || c == PlayerTeleportEvent.TeleportCause.PLUGIN)
            if (e.getFrom().getWorld() != e.getTo().getWorld() || e.getFrom().distanceSquared(e.getTo()) > 25)
                back.put(e.getPlayer().getUniqueId(), e.getFrom());
    }

    @EventHandler
    public void died(PlayerDeathEvent e) {
        back.put(e.getEntity().getUniqueId(), e.getEntity().getLocation());
        if (getConfig().getBoolean("back.tell-on-death", true))
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (e.getEntity().isOnline()) say(e.getEntity(), "&7Type &f/back &7to go back to where you died.");
            }, 40);
    }

    public Location backOf(UUID id) {
        return back.get(id);
    }

    public void rememberBack(UUID id, Location l) {
        back.put(id, l);
    }

    // ------------------------------------------------------------------ kits

    public List<String> kits() {
        ConfigurationSection c = getConfig().getConfigurationSection("kits");
        return c == null ? List.of() : new ArrayList<>(c.getKeys(false));
    }

    public List<ItemStack> kitItems(String kit) {
        List<ItemStack> out = new ArrayList<>();
        for (String s : getConfig().getStringList("kits." + kit + ".items")) {
            String[] p = s.split(":");
            Material m = Util.material(p[0]);
            if (m == null || !m.isItem()) continue;
            int n = 1;
            try {
                if (p.length > 1) n = Integer.parseInt(p[1].trim());
            } catch (NumberFormatException ignored) {
            }
            while (n > 0) {
                int stack = Math.min(n, m.getMaxStackSize());
                out.add(new ItemStack(m, stack));
                n -= stack;
            }
        }
        return out;
    }

    /** Milliseconds until this kit can be claimed again (0 = now, -1 = never again). */
    public long kitWait(UUID id, String kit) {
        long last = data.getLong("kits." + id + "." + kit, 0);
        if (last == 0) return 0;
        long cd = getConfig().getLong("kits." + kit + ".cooldown-hours", 24);
        if (cd < 0) return -1;
        return Math.max(0, last + cd * 3_600_000L - System.currentTimeMillis());
    }

    public boolean giveKit(Player p, String kit, boolean force) {
        if (!force && kitWait(p.getUniqueId(), kit) != 0) return false;
        for (ItemStack it : kitItems(kit))
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        data.set("kits." + p.getUniqueId() + "." + kit, System.currentTimeMillis());
        saveData();
        return true;
    }

    public Menu kitMenu(Player p) {
        List<String> ks = kits();
        Menu m = new Menu(3, "&8" + Util.small("Kits"));
        int slot = 10;
        for (String k : ks) {
            if (slot > 16) break;
            String perm = getConfig().getString("kits." + k + ".permission", "");
            if (!perm.isEmpty() && !p.hasPermission(perm)) continue;
            Material icon = Util.material(getConfig().getString("kits." + k + ".icon", "CHEST"));
            long wait = kitWait(p.getUniqueId(), k);
            List<String> lore = new ArrayList<>();
            for (ItemStack it : kitItems(k)) lore.add("&7" + it.getAmount() + "x " + Util.nice(it.getType()));
            lore.add("");
            lore.add(wait == 0 ? "&eClick to claim" : wait < 0 ? "&cAlready claimed" : "&cReady in " + time(wait));
            m.set(slot++, Util.item(icon == null ? Material.CHEST : icon, 1, getConfig().getString("kits." + k + ".name", k), lore), e -> {
                if (giveKit(p, k, false)) {
                    say(p, "&aYou got the " + getConfig().getString("kits." + k + ".name", k) + "&a kit.");
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1f);
                    p.closeInventory();
                } else say(p, "&cYou can't claim that kit right now.");
            });
        }
        m.fill();
        return m;
    }

    private static String time(long ms) {
        long m = ms / 60000, h = m / 60;
        return h > 0 ? h + "h " + (m % 60) + "m" : Math.max(1, m) + "m";
    }

    // ------------------------------------------------------------------ starter gear

    /** Gray bold "Starter ..." gear for anyone who spawns with nothing. It can't be sold or put on /ah. */
    public List<ItemStack> starterGear() {
        List<ItemStack> out = new ArrayList<>();
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(this, "starter");
        for (String s : getConfig().getStringList("starter-gear.items")) {
            String[] p = s.split(":");
            Material m = Util.material(p[0]);
            if (m == null || !m.isItem()) continue;
            int n = 1;
            try {
                if (p.length > 1) n = Integer.parseInt(p[1].trim());
            } catch (NumberFormatException ignored) {
            }
            ItemStack it = new ItemStack(m, Math.max(1, Math.min(n, m.getMaxStackSize())));
            org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Util.color(getConfig().getString("starter-gear.name", "&7&lStarter {item}").replace("{item}", Util.nice(m))));
                meta.setLore(List.of(Util.color("&8Can't be sold or auctioned")));
                meta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                it.setItemMeta(meta);
            }
            out.add(it);
        }
        return out;
    }

    public static boolean empty(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() != Material.AIR) return false;
        return true;
    }

    /** Gives the starter gear (armour goes straight on). */
    public void giveStarter(Player p) {
        var inv = p.getInventory();
        for (ItemStack it : starterGear()) {
            String n = it.getType().name();
            if (n.endsWith("_HELMET") && inv.getHelmet() == null) inv.setHelmet(it);
            else if (n.endsWith("_CHESTPLATE") && inv.getChestplate() == null) inv.setChestplate(it);
            else if (n.endsWith("_LEGGINGS") && inv.getLeggings() == null) inv.setLeggings(it);
            else if (n.endsWith("_BOOTS") && inv.getBoots() == null) inv.setBoots(it);
            else inv.addItem(it);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawned(PlayerRespawnEvent e) {
        if (!getConfig().getBoolean("starter-gear.enabled", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && empty(p)) giveStarter(p);
        }, 2);
    }

    // ------------------------------------------------------------------ join / leave

    @EventHandler(priority = EventPriority.HIGH)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        active.put(p.getUniqueId(), System.currentTimeMillis());
        boolean first = !p.hasPlayedBefore();
        String msg = getConfig().getString(first ? "join.first-message" : "join.message", "");
        int number = Bukkit.getOfflinePlayers().length;
        e.setJoinMessage(msg == null || msg.isEmpty() ? null : Util.color(msg.replace("{player}", p.getName()).replace("{number}", String.valueOf(number))));
        String title = getConfig().getString("join.title", ""), sub = getConfig().getString("join.subtitle", "");
        if (title != null && !title.isEmpty())
            p.sendTitle(Util.color(title.replace("{player}", p.getName())), Util.color(sub.replace("{player}", p.getName())), 10, 60, 20);
        for (String line : getConfig().getStringList("join.motd")) p.sendMessage(Util.color(line.replace("{player}", p.getName())));
        if (getConfig().getBoolean("starter-gear.enabled", true))
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && !p.isDead() && empty(p)) giveStarter(p);
            }, 4);
        if (first) {
            if (data.isSet("spawn") && getConfig().getBoolean("spawn.new-players-to-spawn", true))
                Bukkit.getScheduler().runTaskLater(this, () -> p.teleport(spawn()), 2);
            String kit = getConfig().getString("join.first-kit", "");
            if (kit != null && !kit.isEmpty() && kits().contains(kit)) Bukkit.getScheduler().runTaskLater(this, () -> giveKit(p, kit, true), 5);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        String msg = getConfig().getString("join.leave-message", "");
        e.setQuitMessage(msg == null || msg.isEmpty() ? null : Util.color(msg.replace("{player}", e.getPlayer().getName())));
        afk.remove(e.getPlayer().getUniqueId());
        active.remove(e.getPlayer().getUniqueId());
        tp.cancel(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ AFK

    public boolean isAfk(UUID id) {
        return afk.contains(id);
    }

    public void setAfk(Player p, boolean on) {
        if (on == afk.contains(p.getUniqueId())) return;
        if (on) afk.add(p.getUniqueId());
        else {
            afk.remove(p.getUniqueId());
            active.put(p.getUniqueId(), System.currentTimeMillis());
        }
        if (getConfig().getBoolean("afk.announce", true))
            Bukkit.broadcastMessage(Util.color(getConfig().getString(on ? "afk.now-afk" : "afk.back", "")).replace("{player}", p.getName()));
    }

    private void activity(Player p) {
        active.put(p.getUniqueId(), System.currentTimeMillis());
        if (afk.contains(p.getUniqueId())) Bukkit.getScheduler().runTask(this, () -> setAfk(p, false));
    }

    @EventHandler(ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        if (e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch()
                || e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockZ() != e.getTo().getBlockZ())
            activity(e.getPlayer());
    }

    @EventHandler
    public void chat(AsyncPlayerChatEvent e) {
        activity(e.getPlayer());
    }

    @EventHandler
    public void command(PlayerCommandPreprocessEvent e) {
        if (!e.getMessage().toLowerCase(Locale.ROOT).startsWith("/afk")) activity(e.getPlayer());
    }

    @EventHandler
    public void interact(PlayerInteractEvent e) {
        activity(e.getPlayer());
    }

    private void afkCheck() {
        long now = System.currentTimeMillis();
        long afkAfter = getConfig().getLong("afk.minutes", 5) * 60_000L, kickAfter = getConfig().getLong("afk.kick-minutes", 0) * 60_000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            long idle = now - active.getOrDefault(p.getUniqueId(), now);
            if (afkAfter > 0 && idle >= afkAfter) setAfk(p, true);
            if (kickAfter > 0 && idle >= kickAfter && !p.hasPermission("darkessentials.afk.bypass"))
                p.kickPlayer(Util.color(getConfig().getString("afk.kick-message", "&cYou were AFK for too long.")));
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("darkessentials")) {
            if (a.length > 0 && a[0].equalsIgnoreCase("test")) test(s);
            else if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                say(s, "&aReloaded.");
            } else say(s, "&7Dark Essentials " + getDescription().getVersion() + " by DarkerDawnFR. /darkessentials reload|test");
            return true;
        }
        if (name.equals("rules")) {
            for (String l : getConfig().getStringList("rules")) s.sendMessage(Util.color(l));
            return true;
        }
        if (!(s instanceof Player p)) return say(s, "&cOnly players can do that.");
        switch (name) {
            case "spawn" -> {
                if (tp.busy(p.getUniqueId())) return say(p, "&cYou're already teleporting.");
                tp.start(p, getConfig().getInt("countdown-seconds", 5), true, this::spawn, prefix(), () -> say(p, "&aWelcome to spawn."));
            }
            case "setspawn" -> {
                setSpawn(p.getLocation());
                say(p, "&aSpawn set where you stand.");
            }
            case "back" -> {
                Location l = back.get(p.getUniqueId());
                if (l == null || l.getWorld() == null) return say(p, "&7There's nowhere to go back to.");
                if (tp.busy(p.getUniqueId())) return say(p, "&cYou're already teleporting.");
                Location from = p.getLocation();
                tp.start(p, getConfig().getInt("countdown-seconds", 5), true, () -> {
                    backing.add(p.getUniqueId());
                    Bukkit.getScheduler().runTask(this, () -> backing.remove(p.getUniqueId()));
                    back.put(p.getUniqueId(), from);
                    return l;
                }, prefix(), () -> say(p, "&aTeleported back."));
            }
            case "kit" -> {
                if (a.length == 0) {
                    kitMenu(p).open(p);
                    return true;
                }
                String k = a[0].toLowerCase(Locale.ROOT);
                if (!kits().contains(k)) return say(p, "&cNo kit called " + a[0] + ". Kits: " + String.join(", ", kits()));
                String perm = getConfig().getString("kits." + k + ".permission", "");
                if (!perm.isEmpty() && !p.hasPermission(perm)) return say(p, "&cYou can't use that kit.");
                long wait = kitWait(p.getUniqueId(), k);
                if (wait != 0) return say(p, wait < 0 ? "&cYou already claimed that kit." : "&cYou can claim it again in " + time(wait) + ".");
                giveKit(p, k, false);
                say(p, "&aYou got the " + getConfig().getString("kits." + k + ".name", k) + "&a kit.");
            }
            case "afk" -> setAfk(p, !afk.contains(p.getUniqueId()));
            default -> { return false; }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        String backup = data.saveToString();
        UUID id = UUID.randomUUID();
        try {
            World w = Bukkit.getWorlds().get(0);
            Location before = spawn();
            setSpawn(new Location(w, 12.5, 80, -7.5, 45, 0));
            data = YamlConfiguration.loadConfiguration(dataFile);
            Location sp = spawn();
            if (sp.getBlockX() != 12 || sp.getBlockZ() != -8 || sp.getYaw() != 45) fails.add("spawn not saved: " + sp);
            setSpawn(before);
            if (!kits().contains("starter") || kitItems("starter").isEmpty()) fails.add("starter kit");
            if (kitWait(id, "daily") != 0) fails.add("new player can't claim");
            data.set("kits." + id + ".daily", System.currentTimeMillis());
            long wait = kitWait(id, "daily");
            if (wait < 23 * 3_600_000L || wait > 24 * 3_600_000L) fails.add("daily cooldown " + wait);
            data.set("kits." + id + ".starter", System.currentTimeMillis());
            if (kitWait(id, "starter") != -1) fails.add("starter can be claimed twice");
            rememberBack(id, new Location(w, 1, 64, 1));
            List<ItemStack> gear = starterGear();
            if (gear.size() < 9) fails.add("starter gear has " + gear.size() + " items");
            for (ItemStack it : gear) {
                String n = org.bukkit.ChatColor.stripColor(it.getItemMeta().getDisplayName());
                if (!n.startsWith("Starter ") || !it.getItemMeta().getDisplayName().startsWith(org.bukkit.ChatColor.GRAY.toString() + org.bukkit.ChatColor.BOLD))
                    fails.add("starter name " + it.getItemMeta().getDisplayName());
                if (!it.getItemMeta().getPersistentDataContainer().has(new org.bukkit.NamespacedKey(this, "starter"), org.bukkit.persistence.PersistentDataType.BYTE))
                    fails.add("starter tag");
            }
            if (gear.stream().noneMatch(i -> i.getType() == Material.CHAINMAIL_CHESTPLATE)
                    || gear.stream().noneMatch(i -> i.getType() == Material.COOKED_BEEF && i.getAmount() == 16)) fails.add("starter gear contents");
            org.bukkit.plugin.Plugin shop = Bukkit.getPluginManager().getPlugin("DarkShop");
            if (shop != null) {
                Object w2 = shop.getClass().getMethod("starter", ItemStack.class).invoke(null, gear.get(0));
                if (!Boolean.TRUE.equals(w2)) fails.add("Dark Shop doesn't see starter items");
            }
            if (backOf(id) == null) fails.add("/back");
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            try {
                YamlConfiguration fresh = new YamlConfiguration();
                fresh.loadFromString(backup);
                data = fresh;
            } catch (Exception ignored) {
            }
            back.remove(id);
            saveData();
        }
        String r = fails.isEmpty() ? "DARKESSENTIALS TEST OK" : "DARKESSENTIALS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1 && cmd.getName().equalsIgnoreCase("kit")) out.addAll(kits());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.startsWith(last));
        return out;
    }
}
