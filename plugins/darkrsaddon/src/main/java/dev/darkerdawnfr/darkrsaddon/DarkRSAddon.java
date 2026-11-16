package dev.darkerdawnfr.darkrsaddon;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dark RS Add-on by DarkerDawnFR, for RoseStacker (still needed).
 * Each player turns mob/item stacking on or off in /settings ("Stacking"). A chunk stacks or not depending on the
 * player who got there first: if they have it off, RoseStacker doesn't stack anything in that chunk.
 * Chunks with nobody in them stack as normal.
 */
public class DarkRSAddon extends JavaPlugin implements Listener {
    /** chunk -> players in it, in the order they arrived */
    private final Map<String, LinkedHashSet<UUID>> chunks = new HashMap<>();
    private final Map<UUID, String> where = new HashMap<>();
    private final Map<Class<?>, Method[]> paths = new HashMap<>();
    private final List<String> hooked = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) moved(p, p.getLocation());
        Bukkit.getScheduler().runTask(this, this::hookRoseStacker);
        var pc = getCommand("stacking");
        if (pc != null) pc.setExecutor(this);
    }

    // ------------------------------------------------------------------ who is in which chunk

    public static String key(Location l) {
        return l.getWorld().getName() + ":" + (l.getBlockX() >> 4) + ":" + (l.getBlockZ() >> 4);
    }

    public static String key(Chunk c) {
        return c.getWorld().getName() + ":" + c.getX() + ":" + c.getZ();
    }

    public void enter(UUID id, String chunk) {
        String old = where.put(id, chunk);
        if (chunk.equals(old)) return;
        if (old != null) leaveChunk(id, old);
        chunks.computeIfAbsent(chunk, k -> new LinkedHashSet<>()).add(id);
    }

    public void leave(UUID id) {
        String old = where.remove(id);
        if (old != null) leaveChunk(id, old);
    }

    private void leaveChunk(UUID id, String chunk) {
        Set<UUID> s = chunks.get(chunk);
        if (s == null) return;
        s.remove(id);
        if (s.isEmpty()) chunks.remove(chunk);
    }

    /** The player whose setting counts in this chunk (the first one who's still there), or null. */
    public UUID owner(String chunk) {
        Set<UUID> s = chunks.get(chunk);
        return s == null || s.isEmpty() ? null : s.iterator().next();
    }

    /** Does RoseStacker stack in this chunk? */
    public boolean stacks(String chunk) {
        UUID o = owner(chunk);
        return o == null ? getConfig().getBoolean("empty-chunks-stack", true) : Settings.on(o, "stacking");
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
                    + "&7. This chunk " + (stacks(k) ? "&astacks" : "&cdoesn't stack") + "&7"
                    + (o != null && !o.equals(p.getUniqueId()) ? " (" + Bukkit.getOfflinePlayer(o).getName() + " was here first)" : "") + ". &8Change it in /settings."));
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try {
            String c1 = "world:5:5", c2 = "world:6:5";
            if (!stacks(c1)) fails.add("empty chunk should stack");
            enter(a, c1);
            enter(b, c1);
            if (!a.equals(owner(c1))) fails.add("first player isn't the one who counts");
            enter(a, c2);
            if (!b.equals(owner(c1)) || !a.equals(owner(c2))) fails.add("owner didn't move on");
            leave(b);
            if (owner(c1) != null) fails.add("empty chunk still has an owner");
            if (Bukkit.getPluginManager().getPlugin("RoseStacker") != null && hooked.isEmpty()) fails.add("no RoseStacker events hooked");
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            leave(a);
            leave(b);
        }
        String r = fails.isEmpty() ? "DARKRSADDON TEST OK (RoseStacker events: " + hooked + ")" : "DARKRSADDON TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }
}
