package dev.darkerdawnfr.darkperks;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dark Perks by DarkerDawnFR: commands only rank holders get (from Dark Ranks, or any permissions plugin).
 * /fly, /ec, /craft, /hat, /feed, /heal, /repair, /nick, /glow, /trail. Each needs darkperks.<name>.
 */
public class DarkPerks extends JavaPlugin implements Listener, TabCompleter {
    public static final Map<String, String> PERKS = new LinkedHashMap<>();

    static {
        PERKS.put("craft", "A crafting table anywhere");
        PERKS.put("hat", "Wear the item you hold");
        PERKS.put("ec", "Your ender chest anywhere");
        PERKS.put("feed", "Fill your hunger");
        PERKS.put("fly", "Fly (not in combat)");
        PERKS.put("nick", "A coloured nickname");
        PERKS.put("repair", "Fix the item you hold");
        PERKS.put("glow", "Glow for everyone");
        PERKS.put("heal", "Full health");
        PERKS.put("trail", "Particle trails");
    }

    public static final List<String> TRAILS = List.of("hearts", "flames", "notes", "portal", "sparkle", "purple", "clouds");

    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, String> trails = new HashMap<>();
    private final Map<UUID, Location> lastSpot = new HashMap<>();
    private YamlConfiguration data;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : new ArrayList<>(PERKS.keySet()) {{ add("perks"); }}) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::trailTick, 3, 3);
    }

    private void saveData() {
        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException ignored) {
        }
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&6Perks&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
    }

    private static boolean inCombat(Player p) {
        Plugin c = Bukkit.getPluginManager().getPlugin("DarkCombat");
        if (c == null || !c.isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(c.getClass().getMethod("inCombat", UUID.class).invoke(c, p.getUniqueId()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Seconds until the perk can be used again (0 = now). Starts the cooldown when it's 0. */
    public long cooldown(UUID id, String perk, boolean start) {
        long secs = getConfig().getLong("cooldowns." + perk, 0);
        if (secs <= 0) return 0;
        String k = id + ":" + perk;
        long left = (cooldowns.getOrDefault(k, 0L) - System.currentTimeMillis() + 999) / 1000;
        if (left > 0) return left;
        if (start) cooldowns.put(k, System.currentTimeMillis() + secs * 1000);
        return 0;
    }

    /** A nickname with & colours, at most 16 letters you can see. Null if it isn't allowed. */
    public static String cleanNick(String nick) {
        String visible = org.bukkit.ChatColor.stripColor(Util.color(nick));
        if (visible.isEmpty() || visible.length() > 16 || !visible.matches("[A-Za-z0-9_ .\\-]+")) return null;
        return Util.color(nick.replace("&k", ""));
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String nick = data.getString("nick." + p.getUniqueId());
        if (nick != null && p.hasPermission("darkperks.nick")) p.setDisplayName(nick);
        String trail = data.getString("trail." + p.getUniqueId());
        if (trail != null && p.hasPermission("darkperks.trail")) trails.put(p.getUniqueId(), trail);
        if (data.getBoolean("glow." + p.getUniqueId()) && p.hasPermission("darkperks.glow")) p.setGlowing(true);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        trails.remove(e.getPlayer().getUniqueId());
        lastSpot.remove(e.getPlayer().getUniqueId());
    }

    private static final Particle.DustOptions PURPLE = new Particle.DustOptions(Color.fromRGB(170, 70, 255), 1.2f);

    private void trailTick() {
        for (Map.Entry<UUID, String> e : trails.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            Location now = p.getLocation(), before = lastSpot.put(p.getUniqueId(), now);
            if (before != null && before.getWorld() == now.getWorld() && before.distanceSquared(now) < 0.01) continue;
            Location at = now.clone().add(0, 0.2, 0);
            switch (e.getValue()) {
                case "hearts" -> p.getWorld().spawnParticle(Particle.HEART, at, 1, 0.2, 0.1, 0.2, 0);
                case "flames" -> p.getWorld().spawnParticle(Particle.FLAME, at, 3, 0.15, 0.05, 0.15, 0.01);
                case "notes" -> p.getWorld().spawnParticle(Particle.NOTE, at.add(0, 0.3, 0), 1, 0.2, 0.1, 0.2, 1);
                case "portal" -> p.getWorld().spawnParticle(Particle.PORTAL, at, 8, 0.2, 0.2, 0.2, 0.3);
                case "sparkle" -> p.getWorld().spawnParticle(Particle.END_ROD, at, 2, 0.15, 0.05, 0.15, 0.01);
                case "purple" -> p.getWorld().spawnParticle(Particle.DUST, at, 3, 0.2, 0.05, 0.2, 0, PURPLE);
                case "clouds" -> p.getWorld().spawnParticle(Particle.CLOUD, at, 2, 0.15, 0.02, 0.15, 0.01);
                default -> { }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("perks")) {
            if (a.length > 0 && a[0].equalsIgnoreCase("test") && s.hasPermission("darkperks.admin")) {
                test(s);
                return true;
            }
            say(s, "&fRank perks &7(buy a rank in &f/rank shop&7):");
            for (Map.Entry<String, String> e : PERKS.entrySet())
                s.sendMessage(Util.color((s.hasPermission("darkperks." + e.getKey()) ? "&a✔ " : "&c✖ ") + "&f/" + e.getKey() + " &7- " + e.getValue()));
            return true;
        }
        if (!(s instanceof Player p)) return say(s, "&cOnly players can use perks.");
        if (!p.hasPermission("darkperks." + name)) return say(p, "&cYou need a rank for /" + name + ". See &f/rank shop&c.");
        long wait = cooldown(p.getUniqueId(), name, false);
        if (wait > 0) return say(p, "&cYou can use /" + name + " again in " + (wait >= 60 ? wait / 60 + "m " + wait % 60 + "s" : wait + "s") + ".");
        switch (name) {
            case "craft" -> p.openWorkbench(null, true);
            case "ec" -> p.openInventory(p.getEnderChest());
            case "hat" -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) return say(p, "&7Hold the item you want to wear.");
                ItemStack old = p.getInventory().getHelmet();
                p.getInventory().setHelmet(hand);
                p.getInventory().setItemInMainHand(old);
                say(p, "&aEnjoy your new hat.");
            }
            case "feed" -> {
                p.setFoodLevel(20);
                p.setSaturation(20);
                say(p, "&aYou're full.");
            }
            case "heal" -> {
                try {
                    var max = p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                    p.setHealth(max == null ? 20 : max.getValue());
                } catch (Throwable t) {
                    p.setHealth(Math.min(20, p.getHealth() + 20));
                }
                p.setFoodLevel(20);
                p.setFireTicks(0);
                say(p, "&aHealed.");
            }
            case "fly" -> {
                if (!p.getAllowFlight() && inCombat(p)) return say(p, "&cYou can't fly in combat.");
                boolean on = !p.getAllowFlight();
                p.setAllowFlight(on);
                if (!on) p.setFlying(false);
                say(p, on ? "&aFlying on." : "&7Flying off.");
            }
            case "repair" -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                ItemMeta meta = hand.getItemMeta();
                if (!(meta instanceof Damageable d) || !d.hasDamage()) return say(p, "&7There's nothing to repair in your hand.");
                d.setDamage(0);
                hand.setItemMeta(meta);
                say(p, "&aRepaired.");
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
            }
            case "nick" -> {
                if (a.length == 0 || a[0].equalsIgnoreCase("off")) {
                    p.setDisplayName(p.getName());
                    data.set("nick." + p.getUniqueId(), null);
                    saveData();
                    return say(p, "&7Nickname removed.");
                }
                String nick = cleanNick(String.join(" ", a));
                if (nick == null) return say(p, "&cNicknames are 1-16 letters, numbers, spaces or _ (colours with & are fine).");
                p.setDisplayName(nick);
                data.set("nick." + p.getUniqueId(), nick);
                saveData();
                say(p, "&7You're now " + nick + "&7.");
            }
            case "glow" -> {
                boolean on = !p.isGlowing();
                p.setGlowing(on);
                data.set("glow." + p.getUniqueId(), on);
                saveData();
                say(p, on ? "&aYou're glowing." : "&7Glow off.");
            }
            case "trail" -> {
                String t = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
                if (t.equals("off")) {
                    trails.remove(p.getUniqueId());
                    data.set("trail." + p.getUniqueId(), null);
                    saveData();
                    return say(p, "&7Trail off.");
                }
                if (!TRAILS.contains(t)) return say(p, "&7Trails: &f" + String.join(", ", TRAILS) + " &7or off. &8(/trail hearts)");
                trails.put(p.getUniqueId(), t);
                data.set("trail." + p.getUniqueId(), t);
                saveData();
                say(p, "&aTrail: " + t);
            }
            default -> { return false; }
        }
        cooldown(p.getUniqueId(), name, true);
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID id = UUID.randomUUID();
        try {
            for (String perk : PERKS.keySet()) {
                if (getCommand(perk) == null) fails.add("no /" + perk);
                if (Bukkit.getPluginManager().getPermission("darkperks." + perk) == null) fails.add("no permission darkperks." + perk);
            }
            var all = Bukkit.getPluginManager().getPermission("darkperks.*");
            if (all == null || !all.getChildren().keySet().containsAll(PERKS.keySet().stream().map(k -> "darkperks." + k).toList())) fails.add("darkperks.*");
            if (cooldown(id, "heal", true) != 0 || cooldown(id, "heal", false) <= 0) fails.add("heal cooldown");
            if (cooldown(id, "craft", true) != 0 || cooldown(id, "craft", false) != 0) fails.add("craft shouldn't have a cooldown");
            if (cleanNick("&cGeo") == null || cleanNick("&aaaaaaaaaaaaaaaaaaaaaaaaa") != null || cleanNick("<script>") != null) fails.add("nicknames");
        } catch (Throwable t) {
            fails.add(t.toString());
        }
        String r = fails.isEmpty() ? "DARKPERKS TEST OK (" + PERKS.size() + " perks)" : "DARKPERKS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("trail") && a.length == 1) {
            out.addAll(TRAILS);
            out.add("off");
        }
        if (cmd.getName().equalsIgnoreCase("nick") && a.length == 1) out.add("off");
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.startsWith(last));
        return out;
    }
}
