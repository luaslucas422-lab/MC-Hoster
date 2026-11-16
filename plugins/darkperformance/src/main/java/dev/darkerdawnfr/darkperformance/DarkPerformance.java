package dev.darkerdawnfr.darkperformance;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dark Performance by DarkerDawnFR: keeps a server on a home PC running smoothly.
 * /tps shows how the server is doing; dropped items are cleared every 10 minutes (with warnings);
 * too many mobs in one chunk stop spawning; and when the server lags, the view distance goes down
 * a little by itself and comes back when it's smooth again.
 */
public class DarkPerformance extends JavaPlugin implements Listener, TabCompleter {
    private final ArrayDeque<Double> samples = new ArrayDeque<>();
    private long lastTick = System.nanoTime();
    private int clearIn;
    private int lowChecks, highChecks;
    private final Map<String, Integer> normalView = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("tps", "clearitems", "darkperformance")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        for (World w : Bukkit.getWorlds()) normalView.put(w.getName(), viewDistance(w));
        Bukkit.getScheduler().runTaskTimer(this, this::measure, 20, 20);
        clearIn = clearEvery();
        Bukkit.getScheduler().runTaskTimer(this, this::clearCountdown, 20, 20);
        Bukkit.getScheduler().runTaskTimer(this, this::autoView, 200, 200);
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&aPerformance&8] "));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
        return true;
    }

    // ------------------------------------------------------------------ TPS

    private void measure() {
        long now = System.nanoTime();
        double sec = (now - lastTick) / 1e9;
        lastTick = now;
        samples.addLast(Math.min(20.0, 20.0 / Math.max(sec, 0.001)));
        while (samples.size() > 60) samples.removeFirst();
    }

    /** Average ticks per second over the last minute (20 = perfect). */
    public double tps() {
        if (samples.isEmpty()) return 20;
        double sum = 0;
        for (double d : samples) sum += d;
        return sum / samples.size();
    }

    public double tpsRecent() {
        if (samples.isEmpty()) return 20;
        double sum = 0;
        int n = 0;
        var it = samples.descendingIterator();
        while (it.hasNext() && n < 10) {
            sum += it.next();
            n++;
        }
        return sum / n;
    }

    private static String color(double tps) {
        return tps >= 18 ? "&a" : tps >= 15 ? "&e" : "&c";
    }

    private static double mspt() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (Throwable notPaper) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ clearing dropped items

    private int clearEvery() {
        return Math.max(0, getConfig().getInt("clear-items.every-minutes", 10)) * 60;
    }

    private void clearCountdown() {
        if (clearEvery() == 0) return;
        clearIn--;
        if (getConfig().getIntegerList("clear-items.warn-at").contains(clearIn))
            Bukkit.broadcastMessage(prefix() + Util.color(getConfig().getString("clear-items.warning", "&7Items on the ground are cleared in &c{time}s&7."))
                    .replace("{time}", String.valueOf(clearIn)));
        if (clearIn <= 0) {
            int n = clearItems();
            clearIn = clearEvery();
            if (n > 0 || getConfig().getBoolean("clear-items.always-announce", false))
                Bukkit.broadcastMessage(prefix() + Util.color(getConfig().getString("clear-items.done", "&7Cleared &f{count} &7items."))
                        .replace("{count}", String.valueOf(n)));
        }
    }

    /** Removes dropped items (and arrows stuck in the ground). Returns how many. */
    public int clearItems() {
        boolean keepNamed = getConfig().getBoolean("clear-items.keep-named", true);
        boolean arrows = getConfig().getBoolean("clear-items.arrows", true);
        List<String> skip = getConfig().getStringList("clear-items.skip-worlds");
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            if (skip.contains(w.getName())) continue;
            for (Entity e : w.getEntities()) {
                if (e instanceof Item i) {
                    ItemStack st = i.getItemStack();
                    if (keepNamed && st.hasItemMeta() && st.getItemMeta() != null && st.getItemMeta().hasDisplayName()) continue;
                    e.remove();
                    n++;
                } else if (arrows && e instanceof AbstractArrow a && a.isInBlock()) {
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ mob limits

    public static String group(Entity e) {
        if (e instanceof Villager) return "villagers";
        if (e instanceof Animals) return "animals";
        if (e instanceof Monster) return "monsters";
        if (e instanceof WaterMob) return "water";
        return null;
    }

    private static final Set<CreatureSpawnEvent.SpawnReason> LIMITED = Set.of(CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.SPAWNER, CreatureSpawnEvent.SpawnReason.BREEDING, CreatureSpawnEvent.SpawnReason.EGG,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG, CreatureSpawnEvent.SpawnReason.CHUNK_GEN, CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);

    /** True if a chunk already has as many of this kind as allowed. */
    public boolean full(Chunk c, String group) {
        int limit = getConfig().getInt("mob-limits." + group, -1);
        if (limit < 0) return false;
        int n = 0;
        for (Entity x : c.getEntities()) if (group.equals(group(x)) && ++n >= limit) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent e) {
        if (!getConfig().getBoolean("mob-limits.enabled", true) || !LIMITED.contains(e.getSpawnReason())) return;
        String g = group(e.getEntity());
        if (g != null && full(e.getLocation().getChunk(), g)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ view distance when it lags

    private static int viewDistance(World w) {
        try {
            return w.getViewDistance();
        } catch (Throwable t) {
            return Bukkit.getViewDistance();
        }
    }

    private static boolean setView(World w, int d) {
        try {
            w.setViewDistance(d);
            return true;
        } catch (Throwable notPaper) {
            return false;
        }
    }

    private void autoView() {
        if (!getConfig().getBoolean("auto-view-distance.enabled", true)) return;
        double t = tpsRecent();
        if (t < getConfig().getDouble("auto-view-distance.lag-below", 17)) {
            highChecks = 0;
            if (++lowChecks >= 3) {
                lowChecks = 0;
                change(-1);
            }
        } else if (t > 19.5) {
            lowChecks = 0;
            if (++highChecks >= 12) {
                highChecks = 0;
                change(1);
            }
        } else {
            lowChecks = 0;
            highChecks = 0;
        }
    }

    /** Moves every world's view distance one step (down when lagging, back up toward normal). */
    public int change(int step) {
        int min = getConfig().getInt("auto-view-distance.min", 6), changed = 0;
        for (World w : Bukkit.getWorlds()) {
            int now = viewDistance(w), normal = normalView.getOrDefault(w.getName(), now);
            int want = Math.max(min, Math.min(normal, now + step));
            if (want != now && setView(w, want)) {
                changed++;
                for (Player p : Bukkit.getOnlinePlayers())
                    if (p.hasPermission("darkperformance.admin"))
                        say(p, "&7" + w.getName() + " view distance " + (step < 0 ? "&clowered" : "&araised") + " &7to &f" + want
                                + " &8(TPS " + String.format(Locale.ROOT, "%.1f", tpsRecent()) + ")");
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "tps" -> {
                double t = tps(), r = tpsRecent(), ms = mspt();
                Runtime rt = Runtime.getRuntime();
                long used = (rt.totalMemory() - rt.freeMemory()) / 1048576, max = rt.maxMemory() / 1048576;
                say(s, "&fTPS " + color(r) + String.format(Locale.ROOT, "%.1f", r) + " &7now, " + color(t)
                        + String.format(Locale.ROOT, "%.1f", t) + " &7last minute &8(20 = perfect)");
                if (ms >= 0) say(s, "&fTick time " + (ms < 40 ? "&a" : ms < 50 ? "&e" : "&c") + String.format(Locale.ROOT, "%.1f", ms) + "ms &8(under 50 is fine)");
                say(s, "&fRAM " + (used * 100 / Math.max(1, max) < 85 ? "&a" : "&c") + used + "&7/" + max + " MB");
                for (World w : Bukkit.getWorlds())
                    s.sendMessage(Util.color("&8 - &7" + w.getName() + ": &f" + w.getPlayers().size() + " &7players, &f"
                            + w.getEntities().size() + " &7entities, &f" + w.getLoadedChunks().length + " &7chunks, view &f" + viewDistance(w)));
                if (clearEvery() > 0) s.sendMessage(Util.color("&8 - &7Items clear in &f" + clearIn + "s"));
            }
            case "clearitems" -> {
                int n = clearItems();
                clearIn = clearEvery();
                Bukkit.broadcastMessage(prefix() + Util.color(getConfig().getString("clear-items.done", "&7Cleared &f{count} &7items."))
                        .replace("{count}", String.valueOf(n)));
            }
            case "darkperformance" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("test")) test(s);
                else if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    clearIn = clearEvery();
                    say(s, "&aReloaded.");
                } else say(s, "&7Dark Performance " + getDescription().getVersion() + " by DarkerDawnFR. /darkperformance reload|test");
            }
            default -> { return false; }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        try {
            double t = tps();
            if (t <= 0 || t > 20.01) fails.add("tps " + t);
            World w = Bukkit.getWorlds().get(0);
            Location at = w.getSpawnLocation().clone().add(0, 2, 0);
            at.getChunk().load(true);
            at.getChunk().addPluginChunkTicket(this);
            for (int i = 0; i < 5; i++) w.dropItem(at, new ItemStack(Material.COBBLESTONE, 3));
            ItemStack named = new ItemStack(Material.DIAMOND_SWORD);
            var meta = named.getItemMeta();
            meta.setDisplayName("Keep me");
            named.setItemMeta(meta);
            Item keep = w.dropItem(at, named);
            int n = clearItems();
            if (n < 5) fails.add("cleared only " + n);
            if (keep.isDead() || !keep.isValid()) fails.add("named item was cleared");
            keep.remove();
            at.getChunk().removePluginChunkTicket(this);
            Chunk c = at.getChunk();
            int limit = getConfig().getInt("mob-limits.animals", 30);
            getConfig().set("mob-limits.animals", 0);
            if (!full(c, "animals")) fails.add("limit 0 should be full");
            getConfig().set("mob-limits.animals", 10000);
            if (full(c, "animals")) fails.add("limit 10000 shouldn't be full");
            getConfig().set("mob-limits.animals", limit);
            int before = viewDistance(w);
            normalView.put(w.getName(), Math.max(before, 8));
            boolean paper = setView(w, before);
            if (paper) {
                change(-1);
                if (before > getConfig().getInt("auto-view-distance.min", 6) && viewDistance(w) != before - 1) fails.add("view distance didn't go down");
                change(1);
                if (viewDistance(w) != before) fails.add("view distance didn't come back: " + viewDistance(w));
            }
        } catch (Throwable ex) {
            fails.add(ex.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", ex);
        }
        String r = fails.isEmpty() ? "DARKPERFORMANCE TEST OK (tps " + String.format(Locale.ROOT, "%.1f", tps()) + ")" : "DARKPERFORMANCE TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        return new ArrayList<>();
    }
}
