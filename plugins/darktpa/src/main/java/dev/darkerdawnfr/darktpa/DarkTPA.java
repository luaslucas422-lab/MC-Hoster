package dev.darkerdawnfr.darktpa;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Dark TPA by DarkerDawnFR: the DonutSMP teleport commands.
 * /tpa, /tpahere, /tpaccept, /tpdeny, /tpacancel, /tpauto (auto-accept), and for ops /tp and /tphere.
 * Every teleport is a 5-second countdown you can't move in.
 */
public class DarkTPA extends JavaPlugin implements Listener, TabCompleter {
    /** from asked to; here = from wants to pull to over (otherwise from goes to to). */
    public record Request(UUID from, UUID to, boolean here, long expires) {}

    private final List<Request> requests = new ArrayList<>();
    private final Set<UUID> auto = new HashSet<>();
    private File autoFile;
    private Tp tp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tp = new Tp(this);
        autoFile = new File(getDataFolder(), "tpauto.yml");
        for (String s : YamlConfiguration.loadConfiguration(autoFile).getStringList("auto")) {
            try {
                auto.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel", "tpauto", "tp", "tphere", "darktpa")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&dTPA&8] "));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
        return true;
    }

    private int seconds() {
        return Math.max(0, getConfig().getInt("countdown-seconds", 5));
    }

    // ------------------------------------------------------------------ requests

    private void clean() {
        long now = System.currentTimeMillis();
        requests.removeIf(r -> r.expires() < now);
    }

    public Request find(UUID to, UUID from) {
        clean();
        for (int i = requests.size() - 1; i >= 0; i--) {
            Request r = requests.get(i);
            if (r.to().equals(to) && (from == null || r.from().equals(from))) return r;
        }
        return null;
    }

    public Request request(UUID from, UUID to, boolean here) {
        clean();
        requests.removeIf(r -> r.from().equals(from) && r.to().equals(to));
        Request r = new Request(from, to, here, System.currentTimeMillis() + getConfig().getLong("expire-seconds", 60) * 1000);
        requests.add(r);
        return r;
    }

    public boolean remove(Request r) {
        return requests.remove(r);
    }

    public boolean autoAccept(UUID id) {
        return auto.contains(id);
    }

    public boolean toggleAuto(UUID id) {
        boolean on = auto.add(id);
        if (!on) auto.remove(id);
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (UUID u : auto) l.add(u.toString());
        y.set("auto", l);
        try {
            getDataFolder().mkdirs();
            y.save(autoFile);
        } catch (IOException ignored) {
        }
        return on;
    }

    /** Accepting: whoever moves gets the countdown and lands where the other one is when it ends. */
    private void accept(Request r) {
        remove(r);
        Player from = Bukkit.getPlayer(r.from()), to = Bukkit.getPlayer(r.to());
        if (from == null || to == null) return;
        Player mover = r.here() ? to : from, target = r.here() ? from : to;
        say(from, "&a" + to.getName() + " accepted your request.");
        if (mover != to) say(to, "&aYou accepted " + from.getName() + "'s request.");
        go(mover, target, true);
    }

    private void go(Player mover, Player target, boolean cancelOnMove) {
        if (tp.busy(mover.getUniqueId())) {
            say(mover, "&cYou're already teleporting.");
            return;
        }
        UUID tid = target.getUniqueId();
        tp.start(mover, seconds(), cancelOnMove, () -> {
            Player t = Bukkit.getPlayer(tid);
            if (t == null) {
                say(mover, "&c" + target.getName() + " left, so you weren't teleported.");
                return null;
            }
            return t.getLocation();
        }, prefix(), () -> say(mover, "&aTeleported to " + target.getName() + "."));
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        tp.cancel(id);
        requests.removeIf(r -> r.from().equals(id) || r.to().equals(id));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("darktpa")) {
            if (a.length > 0 && a[0].equalsIgnoreCase("test")) test(s);
            else if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                say(s, "&aReloaded.");
            } else say(s, "&7Dark TPA " + getDescription().getVersion() + " by DarkerDawnFR. /darktpa reload|test");
            return true;
        }
        if (name.equals("tp") && (!(s instanceof Player) || a.length != 1)) {
            // command blocks, the console, coordinates and /tp a b still go to Minecraft's own /tp
            Bukkit.dispatchCommand(s, "minecraft:tp " + String.join(" ", a));
            return true;
        }
        if (!(s instanceof Player p)) return say(s, "&cOnly players can teleport.");
        switch (name) {
            case "tpa", "tpahere" -> {
                if (a.length < 1) return say(p, "&7Usage: /" + name + " <player>");
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) t = Bukkit.getPlayer(a[0]);
                if (t == null) return say(p, "&c" + a[0] + " isn't online.");
                if (t.equals(p)) return say(p, "&cYou can't teleport to yourself.");
                if (!Settings.on(t.getUniqueId(), "tpa")) return say(p, "&c" + t.getName() + " doesn't take teleport requests (/settings).");
                boolean here = name.equals("tpahere");
                Request r = request(p.getUniqueId(), t.getUniqueId(), here);
                if (autoAccept(t.getUniqueId())) {
                    say(t, "&7Auto-accepted &f" + p.getName() + "&7's request &8(/tpauto to turn off)");
                    accept(r);
                    return true;
                }
                say(p, "&7Request sent to &f" + t.getName() + "&7. It expires in " + getConfig().getLong("expire-seconds", 60) + "s. &8/tpacancel");
                Chat.buttons(t, prefix() + Util.color("&f" + p.getName() + (here ? " &7wants you to teleport to them." : " &7wants to teleport to you.")),
                        new String[][]{
                                {Util.color("&a&l[ACCEPT]"), "/tpaccept " + p.getName(), Util.color("&aClick to accept")},
                                {Util.color("&c&l[DENY]"), "/tpdeny " + p.getName(), Util.color("&cClick to deny")}});
            }
            case "tpaccept", "tpdeny" -> {
                UUID from = null;
                if (a.length > 0) {
                    Player f = Bukkit.getPlayerExact(a[0]);
                    if (f == null) return say(p, "&c" + a[0] + " isn't online.");
                    from = f.getUniqueId();
                }
                Request r = find(p.getUniqueId(), from);
                if (r == null) return say(p, "&7You don't have a teleport request" + (a.length > 0 ? " from " + a[0] : "") + ".");
                if (name.equals("tpaccept")) accept(r);
                else {
                    remove(r);
                    say(p, "&7Request denied.");
                    Player f = Bukkit.getPlayer(r.from());
                    if (f != null) say(f, "&c" + p.getName() + " denied your request.");
                }
            }
            case "tpacancel" -> {
                boolean any = requests.removeIf(r -> r.from().equals(p.getUniqueId()));
                say(p, any ? "&7Your requests were cancelled." : "&7You have no requests to cancel.");
            }
            case "tpauto" -> {
                boolean on = toggleAuto(p.getUniqueId());
                say(p, on ? "&aTP auto is ON: &7requests to you are accepted by themselves." : "&7TP auto is OFF: &7you'll get [ACCEPT] [DENY] again.");
            }
            case "tp", "tphere" -> {
                if (a.length < 1) return say(p, "&7Usage: /" + name + " <player>");
                Player t = Bukkit.getPlayer(a[0]);
                if (t == null && name.equals("tp") && a[0].startsWith("@")) {
                    Bukkit.dispatchCommand(p, "minecraft:tp " + a[0]);
                    return true;
                }
                if (t == null) return say(p, "&c" + a[0] + " isn't online.");
                if (t.equals(p)) return say(p, "&cThat's you.");
                if (name.equals("tp")) {
                    say(p, "&7Teleporting to &f" + t.getName() + "&7...");
                    go(p, t, true);
                } else {
                    say(p, "&7Bringing &f" + t.getName() + " &7to you...");
                    say(t, "&f" + p.getName() + " &7is bringing you to them.");
                    go(t, p, getConfig().getBoolean("tphere-cancel-on-move", false));
                }
            }
            default -> { return false; }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID x = UUID.randomUUID(), y = UUID.randomUUID(), z = UUID.randomUUID();
        try {
            Request r1 = request(x, y, false);
            Request r2 = request(z, y, true);
            if (find(y, null) != r2 || find(y, x) != r1 || find(x, null) != null) fails.add("finding requests");
            request(x, y, false);
            int fromX = 0;
            for (Request r : requests) if (r.from().equals(x)) fromX++;
            if (fromX != 1) fails.add("duplicate request kept");
            requests.add(new Request(z, x, false, System.currentTimeMillis() - 1));
            if (find(x, z) != null) fails.add("expired request still there");
            boolean on = toggleAuto(x);
            if (!on || !autoAccept(x) || toggleAuto(x) || autoAccept(x)) fails.add("tpauto toggle");
            if (seconds() != 5) fails.add("countdown is " + seconds() + "s");
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            for (Iterator<Request> it = requests.iterator(); it.hasNext(); ) {
                Request r = it.next();
                if (r.from().equals(x) || r.from().equals(z)) it.remove();
            }
            auto.remove(x);
        }
        String r = fails.isEmpty() ? "DARKTPA TEST OK" : "DARKTPA TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (a.length == 1 && !n.equals("tpauto") && !n.equals("tpacancel") && !n.equals("darktpa"))
            for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(s)) out.add(p.getName());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
