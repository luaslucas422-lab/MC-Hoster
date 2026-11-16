package dev.darkerdawnfr.darkrsaddon;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dark RS Add-on by DarkerDawnFR, for RoseStacker (still needed).
 * Each player turns mob/item stacking on or off in /settings ("Stacking"). It counts for the area around them
 * (radius-chunks). Where two players' areas overlap, the player who got there first decides.
 * With stacking off, RoseStacker doesn't stack mobs or items there, and stacks already there are split up.
 * Spawner and block stacks are never touched. Areas with nobody in them stack as normal.
 */
public class DarkRSAddon extends JavaPlugin implements Listener {
    /** chunk -> players whose area covers it, in the order they arrived (read by RoseStacker's thread too) */
    private final Map<String, Set<UUID>> chunks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> areas = new ConcurrentHashMap<>();
    /** players' Stacking setting, refreshed on the main thread */
    private final Map<UUID, Boolean> setting = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method[]> paths = new ConcurrentHashMap<>();
    private final List<String> hooked = new ArrayList<>();
    private int unstacked;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) moved(p, p.getLocation());
        Bukkit.getScheduler().runTask(this, this::hookRoseStacker);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40, Math.max(10, getConfig().getInt("check-ticks", 40)));
        var pc = getCommand("stacking");
        if (pc != null) pc.setExecutor(this);
    }

    // ------------------------------------------------------------------ who covers which chunk

    public static String key(Location l) {
        return l.getWorld().getName() + ":" + (l.getBlockX() >> 4) + ":" + (l.getBlockZ() >> 4);
    }

    public static String key(Chunk c) {
        return c.getWorld().getName() + ":" + c.getX() + ":" + c.getZ();
    }

    public int radius() {
        return Math.max(0, Math.min(8, getConfig().getInt("radius-chunks", 3)));
    }

    /** The chunks around a chunk key, radius r. */
    public static Set<String> around(String chunk, int r) {
        String[] k = chunk.split(":");
        int x = Integer.parseInt(k[k.length - 2]), z = Integer.parseInt(k[k.length - 1]);
        String w = chunk.substring(0, chunk.length() - k[k.length - 2].length() - k[k.length - 1].length() - 2);
        Set<String> out = new LinkedHashSet<>();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) out.add(w + ":" + (x + dx) + ":" + (z + dz));
        return out;
    }

    /** The player now stands in this chunk: their area moves with them, keeping their place where they already were. */
    public void enter(UUID id, String chunk) {
        Set<String> now = around(chunk, radius());
        Set<String> old = areas.put(id, now);
        if (old != null) for (String c : old) if (!now.contains(c)) leaveChunk(id, c);
        for (String c : now) if (old == null || !old.contains(c))
            chunks.computeIfAbsent(c, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(id);
    }

    public void leave(UUID id) {
        Set<String> old = areas.remove(id);
        if (old != null) for (String c : old) leaveChunk(id, c);
        setting.remove(id);
    }

    private void leaveChunk(UUID id, String chunk) {
        Set<UUID> s = chunks.get(chunk);
        if (s == null) return;
        s.remove(id);
        if (s.isEmpty()) chunks.remove(chunk, s);
    }

    /** The player whose setting counts in this chunk (the first one whose area still covers it), or null. */
    public UUID owner(String chunk) {
        Set<UUID> s = chunks.get(chunk);
        if (s == null) return null;
        synchronized (s) {
            return s.isEmpty() ? null : s.iterator().next();
        }
    }

    public boolean on(UUID id) {
        Boolean b = setting.get(id);
        return b == null ? Settings.on(id, "stacking") : b;
    }

    /** Does RoseStacker stack in this chunk? */
    public boolean stacks(String chunk) {
        UUID o = owner(chunk);
        return o == null ? getConfig().getBoolean("empty-chunks-stack", true) : on(o);
    }

    private void moved(Player p, Location to) {
        if (to != null && to.getWorld() != null) enter(p.getUniqueId(), key(to));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e.getTo() != null && (e.getFrom().getBlockX() >> 4 != e.getTo().getBlockX() >> 4
                || e.getFrom().getBlockZ() >> 4 != e.getTo().getBlockZ() >> 4 || e.getFrom().getWorld() != e.getTo().getWorld()))
            moved(e.getPlayer(), e.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        moved(e.getPlayer(), e.getTo());
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        moved(e.getPlayer(), e.getPlayer().getLocation());
    }

    @EventHandler
    public void respawn(PlayerRespawnEvent e) {
        moved(e.getPlayer(), e.getRespawnLocation());
    }

    @EventHandler
    public void world(PlayerChangedWorldEvent e) {
        moved(e.getPlayer(), e.getPlayer().getLocation());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        leave(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ splitting stacks where stacking is off

    /** Every few seconds: refresh settings, then split stacks near players who have stacking off. */
    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) setting.put(p.getUniqueId(), Settings.on(p.getUniqueId(), "stacking"));
        if (!getConfig().getBoolean("split-existing-stacks", true)) return;
        Object api = api();
        if (api == null) return;
        int budget = Math.max(1, getConfig().getInt("split-per-check", 60));
        int maxStack = getConfig().getInt("split-max-stack", 64);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (budget <= 0) break;
            if (on(p.getUniqueId())) continue;
            List<Chunk> list = new ArrayList<>();
            World w = p.getWorld();
            for (String c : areas.getOrDefault(p.getUniqueId(), Set.of())) {
                if (stacks(c)) continue;
                String[] k = c.split(":");
                int x = Integer.parseInt(k[k.length - 2]), z = Integer.parseInt(k[k.length - 1]);
                if (w.isChunkLoaded(x, z)) list.add(w.getChunkAt(x, z));
            }
            if (!list.isEmpty()) budget = split(api, w, list, budget, maxStack);
        }
    }

    private Object api() {
        Plugin rs = Bukkit.getPluginManager().getPlugin("RoseStacker");
        if (rs == null || !rs.isEnabled()) return null;
        try {
            Class<?> c = Class.forName("dev.rosewood.rosestacker.api.RoseStackerAPI", true, rs.getClass().getClassLoader());
            return c.getMethod("getInstance").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Splits mob and item stacks in these chunks, one mob at a time, up to budget splits. Returns what's left of the budget. */
    private int split(Object api, World w, List<Chunk> list, int budget, int maxStack) {
        try {
            Object thread = api.getClass().getMethod("getStackingThread", World.class).invoke(api, w);
            if (thread == null) return budget;
            List<?> ents = (List<?>) api.getClass().getMethod("getStackedEntities", Collection.class).invoke(api, list);
            for (Object se : ents) {
                if (budget <= 0) return 0;
                int size = (int) se.getClass().getMethod("getStackSize").invoke(se);
                if (size <= 1 || (maxStack > 0 && size > maxStack)) continue; // huge farm stacks would lag if split
                Method m = find(thread.getClass(), "splitEntityStack", 1);
                if (m == null) break;
                for (int i = 1; i < size && budget > 0; i++, budget--) {
                    if (m.invoke(thread, se) == null) break;
                    unstacked++;
                }
            }
            List<?> items = (List<?>) api.getClass().getMethod("getStackedItems", Collection.class).invoke(api, list);
            Method si = find(thread.getClass(), "splitItemStack", 2);
            if (si != null) for (Object it : items) {
                if (budget <= 0) return 0;
                int size = (int) it.getClass().getMethod("getStackSize").invoke(it);
                org.bukkit.entity.Item item = (org.bukkit.entity.Item) it.getClass().getMethod("getItem").invoke(it);
                int max = Math.max(1, item.getItemStack().getMaxStackSize());
                if (size <= max || (maxStack > 0 && size > maxStack * max)) continue;
                si.invoke(thread, it, max); // keeps one normal stack here, drops the rest next to it
                budget--;
                unstacked++;
            }
        } catch (Throwable t) {
            getLogger().fine("Couldn't split stacks: " + t);
        }
        return budget;
    }

    private static Method find(Class<?> c, String name, int params) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        return null;
    }

    // ------------------------------------------------------------------ RoseStacker

    /** Finds every cancellable "...StackEvent" in RoseStacker and cancels it in chunks where stacking is off. */
    private void hookRoseStacker() {
        Plugin rs = Bukkit.getPluginManager().getPlugin("RoseStacker");
        if (rs == null || !rs.isEnabled()) {
            getLogger().warning("RoseStacker isn't installed: Dark RS Add-on needs it (tick RoseStacker in MC Hoster).");
            return;
        }
        File jar;
        try {
            Method gf = JavaPlugin.class.getDeclaredMethod("getFile");
            gf.setAccessible(true);
            jar = (File) gf.invoke(rs);
        } catch (Exception e) {
            getLogger().warning("Couldn't read RoseStacker: " + e);
            return;
        }
        try (ZipFile z = new ZipFile(jar)) {
            for (ZipEntry en : java.util.Collections.list(z.entries())) {
                String n = en.getName();
                if (!n.endsWith("StackEvent.class") || n.contains("Unstack") || n.contains("$")) continue;
                if (!getConfig().getBoolean("affect-blocks-and-spawners", false) && (n.contains("BlockStack") || n.contains("SpawnerStack"))) continue;
                String cls = n.substring(0, n.length() - 6).replace('/', '.');
                try {
                    Class<?> c = Class.forName(cls, true, rs.getClass().getClassLoader());
                    if (!Event.class.isAssignableFrom(c) || !Cancellable.class.isAssignableFrom(c) || java.lang.reflect.Modifier.isAbstract(c.getModifiers()))
                        continue;
                    @SuppressWarnings("unchecked") Class<? extends Event> ec = (Class<? extends Event>) c;
                    Bukkit.getPluginManager().registerEvent(ec, this, EventPriority.HIGH, (l, ev) -> {
                        if (!ec.isInstance(ev)) return;
                        Location at = locate(ev);
                        if (at != null && at.getWorld() != null && !stacks(key(at))) ((Cancellable) ev).setCancelled(true);
                    }, this, true);
                    hooked.add(c.getSimpleName());
                } catch (Throwable ignored) {
                }
            }
        } catch (Exception e) {
            getLogger().warning("Couldn't read RoseStacker: " + e);
        }
        getLogger().info("Watching RoseStacker: " + hooked);
    }

    public List<String> hooked() {
        return hooked;
    }

    /** Where a stack event happens: looks for an entity, item, block or location on the event (or one step down). */
    private Location locate(Object ev) {
        Method[] path = paths.computeIfAbsent(ev.getClass(), this::findPath);
        if (path.length == 0) return null;
        try {
            Object o = ev;
            for (Method m : path) o = m.invoke(o);
            return toLocation(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Location toLocation(Object o) {
        if (o instanceof Location l) return l;
        if (o instanceof Entity e) return e.getLocation();
        if (o instanceof Block b) return b.getLocation();
        return null;
    }

    private static boolean useful(Class<?> t) {
        return Location.class.isAssignableFrom(t) || Entity.class.isAssignableFrom(t) || Block.class.isAssignableFrom(t);
    }

    private static List<Method> getters(Class<?> c) {
        List<Method> out = new ArrayList<>();
        for (Method m : c.getMethods())
            if (m.getParameterCount() == 0 && m.getName().startsWith("get") && m.getDeclaringClass() != Object.class
                    && m.getDeclaringClass() != Event.class && !m.getName().equals("getHandlers")) out.add(m);
        return out;
    }

    private Method[] findPath(Class<?> c) {
        for (Method m : getters(c)) if (useful(m.getReturnType())) return new Method[]{m};
        for (Method m : getters(c)) {
            Class<?> r = m.getReturnType();
            if (r.isPrimitive() || r == String.class || java.util.Collection.class.isAssignableFrom(r) || r.isArray()) continue;
            for (Method m2 : getters(r)) if (useful(m2.getReturnType())) return new Method[]{m, m2};
        }
        return new Method[0];
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("test") && s.hasPermission("darkrsaddon.admin")) {
            test(s);
            return true;
        }
        if (s instanceof Player p) {
            String k = key(p.getLocation());
            UUID o = owner(k);
            s.sendMessage(Util.color("&8[&aStacking&8] &7Your setting: " + (Settings.on(p.getUniqueId(), "stacking") ? "&aon" : "&coff")
                    + "&7. Here it " + (stacks(k) ? "&astacks" : "&cdoesn't stack") + "&7"
                    + (o != null && !o.equals(p.getUniqueId()) ? " (" + Bukkit.getOfflinePlayer(o).getName() + " was here first)" : "") + ". &8Change it in /settings."));
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try {
            String c1 = "world:5:5", c2 = "world:50:5", near = "world:7:4";
            if (around("my world:0:0", 3).size() != 49 || !around("my world:0:0", 1).contains("my world:-1:1")) fails.add("area around a chunk");
            if (!stacks(c1)) fails.add("empty chunk should stack");
            enter(a, c1);
            enter(b, c1);
            if (!a.equals(owner(c1)) || !a.equals(owner(near))) fails.add("first player isn't the one who counts (area " + radius() + ")");
            enter(a, c2);
            if (!b.equals(owner(c1)) || !b.equals(owner(near)) || !a.equals(owner(c2))) fails.add("owner didn't move on");
            leave(b);
            if (owner(c1) != null || owner(near) != null) fails.add("empty chunk still has an owner");
            if (Bukkit.getPluginManager().getPlugin("RoseStacker") != null && hooked.isEmpty()) fails.add("no RoseStacker events hooked");
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            leave(a);
            leave(b);
        }
        Object api = api();
        if (!fails.isEmpty() || api == null) {
            report(s, fails, api == null ? "no RoseStacker" : "");
            return;
        }
        // the real thing: RoseStacker stacks some pigs, then a player with stacking off splits them
        World w = Bukkit.getWorlds().get(0);
        Location at = w.getSpawnLocation().clone().add(0.5, 1, 0.5);
        Chunk chunk = at.getChunk();
        chunk.addPluginChunkTicket(this);
        List<Entity> pigs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Entity e = w.spawnEntity(at, org.bukkit.entity.EntityType.PIG);
            if (e instanceof org.bukkit.entity.LivingEntity le) le.setAI(false);
            pigs.add(e);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String note = "";
            UUID fake = UUID.randomUUID();
            try {
                int before = biggest(api, List.of(chunk));
                if (before <= 1) note = "RoseStacker didn't stack the test pigs, split not checked";
                else {
                    setting.put(fake, false);
                    enter(fake, key(chunk));
                    if (stacks(key(chunk))) fails.add("chunk still stacks with stacking off");
                    split(api, w, List.of(chunk), 100, 64);
                    int after = biggest(api, List.of(chunk));
                    if (after > 1) fails.add("stack of " + before + " pigs only split to " + after);
                    else note = "split a stack of " + before + " pigs";
                }
            } catch (Throwable t) {
                fails.add(t.toString());
            } finally {
                leave(fake);
                for (Entity e : at.getWorld().getNearbyEntities(at, 6, 6, 6)) if (e.getType() == org.bukkit.entity.EntityType.PIG) e.remove();
                chunk.removePluginChunkTicket(this);
            }
            report(s, fails, note);
        }, 100);
    }

    private int biggest(Object api, List<Chunk> list) throws Exception {
        int max = 0;
        for (Object se : (List<?>) api.getClass().getMethod("getStackedEntities", Collection.class).invoke(api, list))
            max = Math.max(max, (int) se.getClass().getMethod("getStackSize").invoke(se));
        return max;
    }

    private void report(CommandSender s, List<String> fails, String note) {
        String r = fails.isEmpty() ? "DARKRSADDON TEST OK (RoseStacker events: " + hooked + (note.isEmpty() ? "" : "; " + note) + ")" : "DARKRSADDON TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }
}
