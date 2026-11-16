package dev.darkerdawnfr.darkrifts;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Dark Rifts by DarkerDawnFR (an original, not a DonutSMP copy).
 *
 * Every so often a purple rift tears open somewhere in the world. Everyone sees where on a boss bar.
 * Stand inside it to charge it, but it only charges while ONE player holds it (two or more = contested,
 * so you have to fight for it), and waves of rift mobs keep coming. At 100% it collapses: whoever held it
 * longest gets money (Dark Shop), shards (Dark Shards) and loot; everyone who helped gets some shards.
 */
public class DarkRifts extends JavaPlugin implements Listener, TabCompleter {
    public static final class Rift {
        public final Location center;
        public final long closes;
        public double progress;
        public final Map<UUID, Integer> held = new HashMap<>();
        public final Set<UUID> mobs = new HashSet<>();
        public int wave, sinceWave;
        public String state = "Waiting for someone";
        BossBar bar;

        Rift(Location center, long closes) {
            this.center = center;
            this.closes = closes;
        }
    }

    private Rift rift;
    private long nextRift;
    private boolean finding;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("rift");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        schedule();
        Bukkit.getScheduler().runTaskTimer(this, this::second, 20, 20);
        Bukkit.getScheduler().runTaskTimer(this, this::particles, 5, 5);
    }

    @Override
    public void onDisable() {
        if (rift != null) close(false, true);
    }

    private void schedule() {
        nextRift = System.currentTimeMillis() + Math.max(1, getConfig().getLong("every-minutes", 45)) * 60_000L;
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&5✦ Rift&8] "));
    }

    private void broadcast(String text) {
        Bukkit.broadcastMessage(prefix() + Util.color(text));
    }

    // ------------------------------------------------------------------ API (Dark Scoreboard: {rift})

    public String status() {
        if (rift == null) return Util.color(getConfig().getString("scoreboard-none", "none"));
        return rift.center.getBlockX() + ", " + rift.center.getBlockZ() + " (" + (int) rift.progress + "%)";
    }

    public Rift current() {
        return rift;
    }

    // ------------------------------------------------------------------ finding a spot

    public World world() {
        World w = Bukkit.getWorld(getConfig().getString("world", ""));
        if (w != null) return w;
        for (World x : Bukkit.getWorlds()) if (x.getEnvironment() == World.Environment.NORMAL) return x;
        return Bukkit.getWorlds().get(0);
    }

    public void find(World w, int attempt, Consumer<Location> done) {
        if (attempt > 30) {
            done.accept(null);
            return;
        }
        WorldBorder b = w.getWorldBorder();
        int half = (int) Math.max(64, b.getSize() / 2 - 32);
        int max = Math.min(half, getConfig().getInt("radius", 3000)), min = Math.min(max - 1, getConfig().getInt("min-radius", 300));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x, z;
        do {
            x = r.nextInt(-max, max + 1);
            z = r.nextInt(-max, max + 1);
        } while (Math.max(Math.abs(x), Math.abs(z)) < min);
        int bx = b.getCenter().getBlockX() + x, bz = b.getCenter().getBlockZ() + z;
        Consumer<Chunk> then = c -> {
            Block top = w.getHighestBlockAt(bx, bz);
            Material m = top.getType();
            boolean ok = m.isSolid() && !m.name().contains("LEAVES") && !m.name().contains("ICE") && top.getY() > w.getMinHeight()
                    && top.getRelative(0, 1, 0).getType().isAir() && b.isInside(top.getLocation());
            if (ok) done.accept(top.getLocation().add(0.5, 1, 0.5));
            else Bukkit.getScheduler().runTask(this, () -> find(w, attempt + 1, done));
        };
        try {
            w.getChunkAtAsync(bx >> 4, bz >> 4).whenComplete((c, err) -> {
                if (Bukkit.isPrimaryThread()) then.accept(c);
                else Bukkit.getScheduler().runTask(this, () -> then.accept(c));
            });
        } catch (NoSuchMethodError | NoClassDefFoundError spigot) {
            then.accept(w.getChunkAt(bx >> 4, bz >> 4));
        }
    }

    // ------------------------------------------------------------------ opening and closing

    public void open(Location at) {
        if (rift != null) close(false, true);
        rift = new Rift(at, System.currentTimeMillis() + getConfig().getLong("open-minutes", 10) * 60_000L);
        rift.bar = Bukkit.createBossBar(Util.color("&5✦ Rift"), BarColor.PURPLE, BarStyle.SEGMENTED_10);
        rift.bar.setProgress(0);
        for (Player p : Bukkit.getOnlinePlayers()) rift.bar.addPlayer(p);
        broadcast("&d&lA RIFT HAS OPENED &7at &d" + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ()
                + " &7in the &d" + at.getWorld().getName() + "&7! Hold it alone to charge it. It closes in "
                + getConfig().getLong("open-minutes", 10) + " minutes. &8/rift");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(Util.color("&5&l✦ RIFT OPENED"), Util.color("&d" + at.getBlockX() + ", " + at.getBlockZ() + " &7- /rift"), 10, 60, 20);
            p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.4f, 1.4f);
        }
        at.getWorld().playSound(at, Sound.BLOCK_END_PORTAL_SPAWN, 3f, 0.8f);
    }

    /** Ends the rift. won = someone charged it; quiet = no message (plugin stopping). */
    public void close(boolean won, boolean quiet) {
        Rift r = rift;
        if (r == null) return;
        rift = null;
        if (r.bar != null) r.bar.removeAll();
        for (UUID id : r.mobs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                r.center.getWorld().spawnParticle(Particle.PORTAL, e.getLocation(), 30, 0.3, 0.6, 0.3, 0.5);
                e.remove();
            }
        }
        r.mobs.clear();
        schedule();
        if (!won && !quiet) broadcast("&7The rift at &d" + r.center.getBlockX() + ", " + r.center.getBlockZ() + " &7collapsed. Nobody claimed it.");
    }

    /** Rewards for a finished rift. Works for offline players too (money and shards by UUID). Returns the winner. */
    public UUID finish(Rift r) {
        UUID winner = null;
        int best = -1;
        for (Map.Entry<UUID, Integer> e : r.held.entrySet())
            if (e.getValue() > best) {
                best = e.getValue();
                winner = e.getKey();
            }
        Location c = r.center;
        c.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.7f);
        c.getWorld().spawnParticle(Particle.REVERSE_PORTAL, c.clone().add(0, 1, 0), 400, 2, 2, 2, 0.2);
        c.getWorld().spawnParticle(Particle.END_ROD, c.clone().add(0, 1, 0), 80, 1.5, 3, 1.5, 0.1);
        if (winner != null) {
            double money = getConfig().getDouble("rewards.money", 25000);
            long shards = getConfig().getLong("rewards.shards", 25);
            call("DarkShop", "give", winner, money);
            call("DarkShards", "giveShards", winner, shards);
            Player w = Bukkit.getPlayer(winner);
            if (w != null) {
                for (String s : getConfig().getStringList("rewards.items")) {
                    String[] parts = s.split(":");
                    Material m = Util.material(parts[0]);
                    if (m == null || !m.isItem()) continue;
                    int n = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
                    for (ItemStack left : w.getInventory().addItem(new ItemStack(m, n)).values())
                        w.getWorld().dropItemNaturally(w.getLocation(), left);
                }
                w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                w.sendTitle(Util.color("&d&lRIFT CLAIMED"), Util.color("&7You held it the longest"), 10, 60, 20);
            }
            String name = Bukkit.getOfflinePlayer(winner).getName();
            broadcast("&d" + (name == null ? "Someone" : name) + " &7claimed the rift! &8(+" + Util.money(money)
                    + ", +" + shards + " shards and loot)");
            long helper = getConfig().getLong("rewards.helper-shards", 5);
            int minSecs = getConfig().getInt("rewards.helper-min-seconds", 10);
            for (Map.Entry<UUID, Integer> e : r.held.entrySet())
                if (!e.getKey().equals(winner) && e.getValue() >= minSecs) {
                    call("DarkShards", "giveShards", e.getKey(), helper);
                    Player h = Bukkit.getPlayer(e.getKey());
                    if (h != null) h.sendMessage(prefix() + Util.color("&7You helped charge the rift: &d+" + helper + " shards"));
                }
        }
        return winner;
    }

    private static Object call(String plugin, String method, Object... args) {
        Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        if (p == null || !p.isEnabled()) return null;
        for (Method m : p.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            try {
                Object[] a = args.clone();
                for (int i = 0; i < a.length; i++) {
                    Class<?> t = m.getParameterTypes()[i];
                    if (t == long.class && a[i] instanceof Number n) a[i] = n.longValue();
                    if (t == double.class && a[i] instanceof Number n) a[i] = n.doubleValue();
                }
                return m.invoke(p, a);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ every second

    private boolean holding(Player p, Rift r) {
        if (p.isDead() || p.getWorld() != r.center.getWorld()) return false;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return false;
        double rad = getConfig().getDouble("size", 6);
        Location l = p.getLocation();
        double dx = l.getX() - r.center.getX(), dz = l.getZ() - r.center.getZ(), dy = l.getY() - r.center.getY();
        return dx * dx + dz * dz <= rad * rad && Math.abs(dy) <= 8;
    }

    private void second() {
        if (rift == null) {
            if (!finding && getConfig().getBoolean("automatic", true) && System.currentTimeMillis() >= nextRift
                    && Bukkit.getOnlinePlayers().size() >= getConfig().getInt("min-players", 1)) {
                finding = true;
                find(world(), 0, loc -> {
                    finding = false;
                    if (loc != null) open(loc);
                    else schedule();
                });
            }
            return;
        }
        Rift r = rift;
        long left = r.closes - System.currentTimeMillis();
        if (left <= 0) {
            close(false, false);
            return;
        }
        List<Player> in = new ArrayList<>();
        for (Player p : r.center.getWorld().getPlayers()) if (holding(p, r)) in.add(p);
        for (Player p : in) r.held.merge(p.getUniqueId(), 1, Integer::sum);
        boolean contested = in.size() > 1 && getConfig().getBoolean("contested-pauses", true);
        if (in.isEmpty()) r.state = "Waiting for someone";
        else if (contested) r.state = "&cContested! &7(" + in.size() + " players)";
        else {
            r.progress = Math.min(100, r.progress + 100.0 / Math.max(10, getConfig().getInt("charge-seconds", 90)));
            r.state = "&d" + (in.size() == 1 ? in.get(0).getName() : in.size() + " players") + " &7is charging";
        }
        r.mobs.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || e.isDead();
        });
        if (!in.isEmpty() && ++r.sinceWave >= getConfig().getInt("waves.every-seconds", 20)) {
            r.sinceWave = 0;
            spawnWave(r);
        }
        if (r.bar != null) {
            r.bar.setProgress(Math.max(0, Math.min(1, r.progress / 100)));
            r.bar.setColor(contested ? BarColor.RED : BarColor.PURPLE);
            r.bar.setTitle(Util.color("&5✦ Rift &d" + r.center.getBlockX() + ", " + r.center.getBlockZ() + " &8| &f" + (int) r.progress
                    + "% &8| " + r.state + " &8| &7" + (left / 60000) + ":" + String.format("%02d", (left / 1000) % 60)));
        }
        if (r.progress >= 100) {
            finish(r);
            close(true, false);
        }
    }

    public int spawnWave(Rift r) {
        r.wave++;
        int count = Math.min(getConfig().getInt("waves.max", 12), getConfig().getInt("waves.mobs", 3) + r.wave - 1);
        List<String> types = getConfig().getStringList("waves.types");
        int made = 0;
        for (int i = 0; i < count && r.mobs.size() < getConfig().getInt("waves.alive-limit", 25); i++) {
            EntityType t;
            try {
                t = EntityType.valueOf(types.isEmpty() ? "ZOMBIE" : types.get(ThreadLocalRandom.current().nextInt(types.size())).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                t = EntityType.ZOMBIE;
            }
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), d = getConfig().getDouble("size", 6) + 2;
            Location at = r.center.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            at.setY(at.getWorld().getHighestBlockYAt(at) + 1);
            Entity e = at.getWorld().spawnEntity(at, t);
            e.setCustomName(Util.color(getConfig().getString("waves.name", "&5Rift Walker")));
            e.setCustomNameVisible(true);
            e.setPersistent(false);
            if (e instanceof LivingEntity le) le.setRemoveWhenFarAway(true);
            at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.05);
            r.mobs.add(e.getUniqueId());
            made++;
        }
        r.center.getWorld().playSound(r.center, Sound.ENTITY_ENDERMAN_SCREAM, 1.5f, 0.6f);
        return made;
    }

    private static final Particle.DustOptions PURPLE = new Particle.DustOptions(Color.fromRGB(150, 40, 255), 1.6f);
    private double spin;

    private void particles() {
        Rift r = rift;
        if (r == null) return;
        World w = r.center.getWorld();
        double rad = getConfig().getDouble("size", 6);
        spin += 0.25;
        for (int i = 0; i < 24; i++) {
            double a = spin + i * Math.PI * 2 / 24;
            w.spawnParticle(Particle.DUST, r.center.clone().add(Math.cos(a) * rad, 0.2, Math.sin(a) * rad), 1, 0, 0, 0, 0, PURPLE);
        }
        for (int y = 0; y < 30; y += 2)
            w.spawnParticle(Particle.DUST, r.center.clone().add(Math.cos(spin + y) * 0.6, y, Math.sin(spin + y) * 0.6), 1, 0, 0, 0, 0, PURPLE);
        w.spawnParticle(Particle.PORTAL, r.center.clone().add(0, 1, 0), 30, 0.6, 1, 0.6, 0.8);
        if (r.progress > 0) w.spawnParticle(Particle.END_ROD, r.center.clone().add(0, 1 + r.progress / 10, 0), 2, 0.2, 0.2, 0.2, 0.01);
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        if (rift != null && rift.bar != null) rift.bar.addPlayer(e.getPlayer());
    }

    @EventHandler
    public void world(PlayerChangedWorldEvent e) {
        if (rift != null && rift.bar != null) rift.bar.addPlayer(e.getPlayer());
    }

    // ------------------------------------------------------------------ commands

    private boolean say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission("darkrifts.admin");
        switch (sub) {
            case "start" -> {
                if (!admin) return say(s, "&cOnly ops can open a rift.");
                if (a.length > 1 && a[1].equalsIgnoreCase("here") && s instanceof Player p) {
                    open(p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5));
                    return true;
                }
                say(s, "&7Finding a spot for a rift...");
                find(world(), 0, loc -> {
                    if (loc == null) say(s, "&cCouldn't find a spot, try again.");
                    else open(loc);
                });
            }
            case "stop" -> {
                if (!admin) return true;
                if (rift == null) return say(s, "&7There's no rift open.");
                close(false, false);
            }
            case "reload" -> {
                if (!admin) return true;
                reloadConfig();
                schedule();
                say(s, "&aReloaded. Next rift in " + getConfig().getLong("every-minutes", 45) + " minutes.");
            }
            case "test" -> {
                if (admin) test(s);
            }
            default -> {
                if (rift == null) {
                    long m = Math.max(0, (nextRift - System.currentTimeMillis()) / 60000);
                    return say(s, "&7No rift right now. The next one opens in about &d" + m + " minutes&7.");
                }
                Rift r = rift;
                say(s, "&7Rift at &d" + r.center.getBlockX() + ", " + r.center.getBlockY() + ", " + r.center.getBlockZ()
                        + " &7(" + r.center.getWorld().getName() + "), &f" + (int) r.progress + "% &7charged.");
                if (s instanceof Player p) {
                    p.setCompassTarget(r.center);
                    say(p, "&7You've held it for &f" + r.held.getOrDefault(p.getUniqueId(), 0) + "s&7. Your compass points to it now.");
                    if (p.getWorld() == r.center.getWorld())
                        say(p, "&7It's &f" + (int) p.getLocation().distance(r.center) + " blocks &7away.");
                }
                if (admin) say(s, "&8/rift start [here]  /rift stop  /rift reload");
            }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID a = UUID.nameUUIDFromBytes("darkrifts-test-a".getBytes()), b = UUID.nameUUIDFromBytes("darkrifts-test-b".getBytes());
        Object moneyBefore = call("DarkShop", "balance", a);
        Object shardsBefore = call("DarkShards", "shards", a), shardsBeforeB = call("DarkShards", "shards", b);
        try {
            World w = world();
            Location at = w.getHighestBlockAt(w.getSpawnLocation()).getLocation().add(0.5, 1, 0.5);
            open(at);
            if (rift == null || rift.bar == null) fails.add("rift didn't open");
            if (!status().contains(at.getBlockX() + ", " + at.getBlockZ())) fails.add("status " + status());
            int made = spawnWave(rift);
            if (made < 1 || rift.mobs.isEmpty()) fails.add("no wave mobs");
            particles();
            rift.held.put(a, 40);
            rift.held.put(b, 15);
            rift.progress = 100;
            UUID winner = finish(rift);
            if (!a.equals(winner)) fails.add("wrong winner");
            Rift r = rift;
            close(true, false);
            if (rift != null || r.mobs.size() != 0) fails.add("rift not cleaned up");
            if (moneyBefore instanceof Number n && String.valueOf(call("DarkShop", "economyMode")).startsWith("built-in")) {
                Object now = call("DarkShop", "balance", a);
                if (!(now instanceof Number m) || m.doubleValue() - n.doubleValue() != getConfig().getDouble("rewards.money", 25000))
                    fails.add("winner money " + now);
            }
            if (shardsBefore instanceof Number n) {
                Object now = call("DarkShards", "shards", a), nowB = call("DarkShards", "shards", b);
                if (!(now instanceof Number m) || m.longValue() - n.longValue() != getConfig().getLong("rewards.shards", 25)) fails.add("winner shards " + now);
                if (!(nowB instanceof Number m) || m.longValue() - ((Number) shardsBeforeB).longValue() != getConfig().getLong("rewards.helper-shards", 5))
                    fails.add("helper shards " + nowB);
            }
            // finding a random spot
            final boolean[] found = {false};
            find(w, 0, loc -> {
                found[0] = loc != null;
                String res = found[0] && fails.isEmpty() ? "DARKRIFTS TEST OK (spot " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ() + ")"
                        : "DARKRIFTS TEST FAIL: " + fails + (found[0] ? "" : " no random spot");
                getLogger().info(res);
                if (!(s instanceof ConsoleCommandSender)) s.sendMessage(res);
            });
            return;
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            call("DarkShop", "take", a, getConfig().getDouble("rewards.money", 25000));
            call("DarkShards", "takeShards", a, getConfig().getLong("rewards.shards", 25));
            call("DarkShards", "takeShards", b, getConfig().getLong("rewards.helper-shards", 5));
        }
        String res = "DARKRIFTS TEST FAIL: " + fails;
        getLogger().info(res);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(res);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1 && s.hasPermission("darkrifts.admin")) out.addAll(List.of("start", "stop", "reload"));
        if (a.length == 2 && a[0].equalsIgnoreCase("start")) out.add("here");
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.startsWith(last));
        return out;
    }
}
