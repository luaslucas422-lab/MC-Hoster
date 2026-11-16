package dev.darkerdawnfr.darkafkzone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dark AFK Zone by DarkerDawnFR. Stand in the AFK zone and get shards: 10 every minute (Dark Shards).
 * Ops make it like Dark Hates Griefers: /afkzone set 1, /afkzone set 2 (or /afkzone wand), /afkzone create <name>.
 * Players: /afkzone to go there.
 */
public class DarkAFKZone extends JavaPlugin implements Listener, TabCompleter {
    private Areas areas;
    private Tp tp;
    /** seconds each player has been in a zone since their last shards */
    private final Map<UUID, Integer> seconds = new ConcurrentHashMap<>();
    private final Map<UUID, String> inside = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        areas = new Areas(this, "&b&lAFK Zone Wand");
        tp = new Tp(this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("afkzone");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::second, 20, 20);
        if (Bukkit.getPluginManager().getPlugin("DarkShards") == null)
            getLogger().warning("Dark Shards isn't installed: the AFK zone has no shards to give (tick Dark Shards in MC Hoster).");
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&bAFK Zone&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
    }

    public int every() {
        return Math.max(5, getConfig().getInt("every-seconds", 60));
    }

    public long shards() {
        return Math.max(0, getConfig().getLong("shards", 10));
    }

    public Areas.Area zoneAt(Location l) {
        for (Areas.Area a : areas.at(l)) if (a.on) return a;
        return null;
    }

    /** One second in the zone. Returns the shards earned now (0 most seconds). */
    public long tick(UUID id, boolean inZone) {
        if (!inZone) {
            seconds.remove(id);
            return 0;
        }
        int s = seconds.merge(id, 1, Integer::sum);
        if (s < every()) return 0;
        seconds.put(id, 0);
        return shards();
    }

    public int left(UUID id) {
        return Math.max(0, every() - seconds.getOrDefault(id, 0));
    }

    private void second() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Areas.Area z = zoneAt(p.getLocation());
            String was = inside.get(p.getUniqueId());
            if (z != null && was == null) {
                inside.put(p.getUniqueId(), z.name);
                p.sendTitle(Util.color("&b&lAFK ZONE"), Util.color("&7+&d" + shards() + " shards &7every " + time(every())), 5, 40, 10);
            } else if (z == null && was != null) {
                inside.remove(p.getUniqueId());
                bar(p, "&7You left the AFK zone.");
            }
            long got = tick(p.getUniqueId(), z != null);
            if (z == null) continue;
            if (got > 0) {
                if (giveShards(p.getUniqueId(), got)) {
                    say(p, "&d+✦ " + got + " shards &7for being in the AFK zone.");
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
                }
            }
            bar(p, getConfig().getString("action-bar", "&b&lAFK ZONE &8| &7+&d✦ {shards} &7in &f{time}")
                    .replace("{shards}", String.valueOf(shards())).replace("{time}", time(left(p.getUniqueId()))));
        }
    }

    private static String time(int s) {
        return s >= 60 ? s / 60 + "m" + (s % 60 > 0 ? " " + s % 60 + "s" : "") : s + "s";
    }

    private void bar(Player p, String text) {
        String t = Util.color(text);
        try {
            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(t));
        } catch (Throwable e) {
            try {
                p.sendActionBar(t);
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean giveShards(UUID id, long n) {
        Plugin p = Bukkit.getPluginManager().getPlugin("DarkShards");
        if (p == null || !p.isEnabled()) return false;
        for (Method m : p.getClass().getMethods()) {
            if (!m.getName().equals("giveShards") || m.getParameterCount() != 2) continue;
            try {
                m.invoke(p, id, m.getParameterTypes()[1] == int.class ? (Object) (int) n : (Object) n);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        seconds.remove(e.getPlayer().getUniqueId());
        inside.remove(e.getPlayer().getUniqueId());
        tp.cancel(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    private Location spawnOf(Areas.Area a) {
        Object w = a.extra.get("spawn");
        if (w instanceof String s) {
            String[] p = s.split(";");
            org.bukkit.World world = Bukkit.getWorld(p[0]);
            if (world != null && p.length >= 6)
                return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        }
        Location c = a.center();
        return c == null ? null : c.getWorld().getHighestBlockAt(c).getLocation().add(0.5, 1, 0.5);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission("darkafkzone.admin");
        if (sub.isEmpty() || sub.equals("go") || sub.equals("tp")) {
            if (!(s instanceof Player p)) return true;
            Areas.Area z = areas.all().values().stream().filter(x -> x.on).findFirst().orElse(null);
            if (z == null) return say(p, admin ? "&7No AFK zone yet. &f/afkzone set 1&7, &f/afkzone set 2&7, &f/afkzone create <name>" : "&7There's no AFK zone yet.");
            Location to = spawnOf(z);
            if (to == null) return say(p, "&cThe AFK zone's world isn't loaded.");
            if (tp.busy(p.getUniqueId())) return say(p, "&cYou're already teleporting.");
            tp.start(p, getConfig().getInt("countdown-seconds", 5), true, () -> to, prefix(),
                    () -> say(p, "&7Welcome to the AFK zone: &d+✦ " + shards() + " &7every " + time(every()) + "."));
            return true;
        }
        if (sub.equals("test") && admin) {
            test(s);
            return true;
        }
        if (!admin) return say(s, "&f/afkzone &7- go to the AFK zone and get &d✦ " + shards() + " shards &7every " + time(every()) + ".");
        switch (sub) {
            case "set", "pos", "corner" -> {
                if (!(s instanceof Player p)) return true;
                String n = a.length > 1 ? a[a.length - 1] : "";
                if (!n.equals("1") && !n.equals("2")) return say(p, "&7Usage: /afkzone set 1  then  /afkzone set 2  &8(the block you look at)");
                Location l = areas.pick(p, Integer.parseInt(n));
                say(p, "&7Corner &f" + n + " &7set: &f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
            }
            case "wand" -> {
                if (!(s instanceof Player p)) return true;
                for (ItemStack rest : p.getInventory().addItem(areas.wand()).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
                say(p, "&7Left-click a block for corner 1, right-click for corner 2, then &f/afkzone create <name>&7.");
            }
            case "create", "save" -> {
                if (!(s instanceof Player p)) return true;
                String[] why = {""};
                Areas.Area z = areas.create(p, a.length > 1 ? a[1] : areas.nextName("afk"), why);
                if (z == null) return say(p, "&c" + why[0]);
                say(p, "&aAFK zone &f" + z.name + " &amade (" + z.box() + "). Players in it get &d✦ " + shards() + " &aevery " + time(every()) + ".");
                say(p, "&7Where /afkzone sends players: stand there and type &f/afkzone setspawn " + z.name);
            }
            case "setspawn" -> {
                if (!(s instanceof Player p)) return true;
                Areas.Area z = a.length > 1 ? areas.get(a[1]) : zoneAt(p.getLocation());
                if (z == null) return say(p, "&7Stand in the zone, or: /afkzone setspawn <zone>");
                Location l = p.getLocation();
                z.extra.put("spawn", l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
                areas.save();
                say(p, "&7/afkzone now sends players here.");
            }
            case "delete", "remove" -> {
                if (a.length < 2) return say(s, "&7Usage: /afkzone delete <zone>");
                say(s, areas.delete(a[1]) ? "&7AFK zone &f" + a[1] + " &7deleted." : "&cNo zone called " + a[1] + ".");
            }
            case "on", "off" -> {
                Areas.Area z = a.length > 1 ? areas.get(a[1]) : null;
                if (z == null) return say(s, "&7Usage: /afkzone " + sub + " <zone>");
                z.on = sub.equals("on");
                areas.save();
                say(s, "&7AFK zone &f" + z.name + " &7is now " + (z.on ? "&aON" : "&cOFF") + "&7.");
            }
            case "list" -> {
                if (areas.all().isEmpty()) return say(s, "&7No AFK zones yet.");
                for (Areas.Area z : areas.all().values())
                    say(s, "&f" + z.name + " &7" + z.world + " " + z.x1 + "," + z.y1 + "," + z.z1 + " → " + z.x2 + "," + z.y2 + "," + z.z2 + " " + (z.on ? "&aON" : "&cOFF"));
            }
            case "reload" -> {
                reloadConfig();
                areas.load();
                say(s, "&aReloaded.");
            }
            default -> {
                say(s, "&f/afkzone &7(go there)  &f/afkzone set 1|2 &7or &f/afkzone wand&7, &f/afkzone create <name>&7, &f/afkzone setspawn&7,");
                say(s, "&f/afkzone on|off|delete <zone>&7, &f/afkzone list&7. Shards and time: config.yml");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("go");
            if (s.hasPermission("darkafkzone.admin")) out.addAll(List.of("set", "wand", "create", "setspawn", "on", "off", "delete", "list", "reload"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("set")) out.addAll(List.of("1", "2"));
        else if (a.length == 2 && List.of("on", "off", "delete", "setspawn").contains(a[0].toLowerCase(Locale.ROOT))) out.addAll(areas.all().keySet());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID id = UUID.randomUUID();
        String name = "afktest";
        try {
            areas.delete(name);
            org.bukkit.World w = Bukkit.getWorlds().get(0);
            String[] why = {""};
            Areas.Area z = areas.create(name, new Location(w, 2000, 60, 2000), new Location(w, 2004, 64, 2004), why);
            if (z == null) throw new IllegalStateException(why[0]);
            if (zoneAt(new Location(w, 2002, 62, 2002)) == null || zoneAt(new Location(w, 2010, 62, 2002)) != null) fails.add("zone box");
            if (shards() != 10 || every() != 60) fails.add("defaults: " + shards() + " every " + every());
            long got = 0;
            for (int i = 0; i < 59; i++) got += tick(id, true);
            if (got != 0 || left(id) != 1) fails.add("counted wrong");
            if (tick(id, true) != 10) fails.add("no shards after a minute");
            for (int i = 0; i < 30; i++) tick(id, true);
            tick(id, false); // leaving starts over
            if (left(id) != 60) fails.add("leaving didn't reset");
            Plugin sh = Bukkit.getPluginManager().getPlugin("DarkShards");
            if (sh != null && java.util.Arrays.stream(sh.getClass().getMethods()).noneMatch(m -> m.getName().equals("giveShards") && m.getParameterCount() == 2))
                fails.add("Dark Shards has no giveShards");
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            areas.delete(name);
        }
        String r = fails.isEmpty() ? "DARKAFKZONE TEST OK" : "DARKAFKZONE TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }
}
