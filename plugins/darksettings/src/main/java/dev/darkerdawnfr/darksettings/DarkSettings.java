package dev.darkerdawnfr.darksettings;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dark Settings by DarkerDawnFR: /settings, like DonutSMP's. Each player turns things on or off for themselves:
 * night vision, mobs spawning near them, the scoreboard, the boss bar, chat, private messages, mentions,
 * TPA requests, payments and tips. The other Dark plugins ask this one before doing those things.
 */
public class DarkSettings extends JavaPlugin implements Listener, TabCompleter {
    /** key, name, icon, what it does, needs plugin ("" = always), on by default. */
    public record Setting(String key, String name, Material icon, String desc, String plugin, boolean def) {}

    public static final List<Setting> ALL = List.of(
            new Setting("nightvision", "Night Vision", Material.ENDER_EYE, "See in the dark.", "", false),
            new Setting("mobspawns", "Mob Spawns", Material.ZOMBIE_HEAD, "Monsters spawn around you.", "", true),
            new Setting("scoreboard", "Scoreboard", Material.OAK_SIGN, "The scoreboard on the right.", "DarkScoreboard", true),
            new Setting("bossbar", "Boss Bar", Material.DRAGON_HEAD, "The bar at the top of the screen.", "DarkBossbar", true),
            new Setting("chat", "Chat", Material.PAPER, "See what other players say.", "", true),
            new Setting("msgs", "Private Messages", Material.WRITABLE_BOOK, "Players can /msg you.", "DarkChat", true),
            new Setting("mentions", "Mention Pings", Material.BELL, "A sound when someone says your name.", "DarkChat", true),
            new Setting("tpa", "TPA Requests", Material.ENDER_PEARL, "Players can send you /tpa.", "DarkTPA", true),
            new Setting("pay", "Payments", Material.GOLD_INGOT, "Players can /pay you.", "DarkShop", true),
            new Setting("tips", "Tips", Material.BOOK, "Tips in chat every few minutes.", "DarkTips", true),
            new Setting("worth", "Sell Prices", Material.SUNFLOWER, "Show what the item you hold sells for.", "DarkShop", true));

    private final Map<UUID, Map<String, Boolean>> values = new ConcurrentHashMap<>();
    private File file;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "players.yml");
        load();
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("settings");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            Map<String, Boolean> m = new ConcurrentHashMap<>();
            var c = y.getConfigurationSection(k);
            if (c == null) continue;
            for (String s : c.getKeys(false)) m.put(s, c.getBoolean(s));
            try {
                values.put(UUID.fromString(k), m);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        values.forEach((id, m) -> m.forEach((k, v) -> y.set(id + "." + k, v)));
        try {
            getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            getLogger().warning("Could not save settings: " + e);
        }
    }

    // ------------------------------------------------------------------ API (the other Dark plugins call this)

    public boolean enabled(UUID id, String key) {
        Map<String, Boolean> m = values.get(id);
        if (m != null && m.containsKey(key)) return m.get(key);
        for (Setting s : ALL) if (s.key().equals(key)) return getConfig().getBoolean("defaults." + key, s.def());
        return true;
    }

    public void set(UUID id, String key, boolean on) {
        values.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(key, on);
        save();
        Player p = Bukkit.getPlayer(id);
        if (p != null && key.equals("nightvision")) nightVision(p);
    }

    public List<Setting> available() {
        List<Setting> out = new ArrayList<>();
        for (Setting s : ALL) {
            if (getConfig().getStringList("hidden").contains(s.key())) continue;
            if (s.plugin().isEmpty() || Bukkit.getPluginManager().getPlugin(s.plugin()) != null) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ the menu

    public Menu menu(Player p) {
        List<Setting> list = available();
        Menu m = new Menu(6, "&8" + Util.small("Settings"));
        int[] icons = {10, 11, 12, 13, 14, 15, 16, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < list.size() && i < icons.length; i++) {
            Setting s = list.get(i);
            boolean on = enabled(p.getUniqueId(), s.key());
            Runnable toggle = () -> {
                set(p.getUniqueId(), s.key(), !on);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, on ? 0.8f : 1.3f);
                menu(p).open(p);
            };
            m.set(icons[i], Util.item(s.icon(), 1, (on ? "&a" : "&c") + s.name(), List.of("&7" + s.desc(), "",
                    on ? "&aEnabled" : "&cDisabled", "&eClick to " + (on ? "turn off" : "turn on"))), e -> toggle.run());
            m.set(icons[i] + 9, Util.item(on ? Material.LIME_DYE : Material.GRAY_DYE, on ? "&aEnabled" : "&cDisabled", "&eClick to switch"), e -> toggle.run());
        }
        m.fill();
        return m;
    }

    // ------------------------------------------------------------------ the settings this plugin does itself

    private void nightVision(Player p) {
        if (enabled(p.getUniqueId(), "nightvision"))
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        else {
            PotionEffect e = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
            if (e != null && (e.isInfinite() || e.getDuration() > 20 * 60 * 60)) p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (e.getPlayer().isOnline()) nightVision(e.getPlayer());
        }, 10);
    }

    @EventHandler
    public void respawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (e.getPlayer().isOnline()) nightVision(e.getPlayer());
        }, 5);
    }

    @EventHandler
    public void milk(PlayerItemConsumeEvent e) {
        if (e.getItem().getType() == Material.MILK_BUCKET)
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (e.getPlayer().isOnline()) nightVision(e.getPlayer());
            }, 2);
    }

    /** Monsters don't spawn naturally near a player who turned Mob Spawns off (the closest player decides). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(e.getEntity() instanceof Monster)) return;
        double r = getConfig().getDouble("mob-spawns-radius", 64), best = r * r;
        Player closest = null;
        for (Player p : e.getLocation().getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(e.getLocation());
            if (d < best) {
                best = d;
                closest = p;
            }
        }
        if (closest != null && !enabled(closest.getUniqueId(), "mobspawns")) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("test") && s.hasPermission("darksettings.admin")) {
            test(s);
            return true;
        }
        if (!(s instanceof Player p)) {
            s.sendMessage("Settings are per player.");
            return true;
        }
        if (a.length > 0) {
            for (Setting st : available())
                if (st.key().equalsIgnoreCase(a[0]) || st.name().replace(" ", "").equalsIgnoreCase(a[0])) {
                    boolean now = !enabled(p.getUniqueId(), st.key());
                    set(p.getUniqueId(), st.key(), now);
                    p.sendMessage(Util.color("&8[&bSettings&8] &f" + st.name() + " " + (now ? "&aon" : "&coff")));
                    return true;
                }
        }
        menu(p).open(p);
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID id = UUID.randomUUID();
        try {
            if (enabled(id, "nightvision") || !enabled(id, "tpa") || !enabled(id, "chat")) fails.add("defaults");
            set(id, "tpa", false);
            set(id, "nightvision", true);
            values.clear();
            load();
            if (enabled(id, "tpa") || !enabled(id, "nightvision")) fails.add("not saved");
            if (available().size() < 3) fails.add("menu has " + available().size() + " settings");
            Map<String, Boolean> seen = new HashMap<>();
            // ask the way the other plugins do
            Object r = getClass().getMethod("enabled", UUID.class, String.class).invoke(this, id, "tpa");
            seen.put("tpa", (Boolean) r);
            if (!Boolean.FALSE.equals(seen.get("tpa"))) fails.add("API");
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            values.remove(id);
            save();
        }
        String r = fails.isEmpty() ? "DARKSETTINGS TEST OK (" + available().size() + " settings)" : "DARKSETTINGS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (Setting st : available()) out.add(st.key());
        out.removeIf(v -> !v.startsWith(a[0].toLowerCase()));
        return out;
    }
}
