package dev.darkerdawnfr.darkrtp;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Finds a safe random spot: solid ground, two blocks of air, no lava/water/cactus, inside the world border.
 * On Paper chunks load in the background (no lag); on Spigot they load normally.
 */
public class Finder {
    private final DarkRTP plugin;

    public Finder(DarkRTP plugin) {
        this.plugin = plugin;
    }

    public static String key(World.Environment env) {
        return switch (env) {
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "overworld";
        };
    }

    /** Calls done on the main thread with a safe spot, or null if none was found. */
    public void find(World w, Consumer<Location> done) {
        attempt(w, 0, done);
    }

    private void attempt(World w, int n, Consumer<Location> done) {
        String k = key(w.getEnvironment());
        int max = plugin.getConfig().getInt("worlds." + k + ".attempts", k.equals("end") ? 40 : 20);
        if (n >= max) {
            done.accept(null);
            return;
        }
        int[] xz = randomXZ(w);
        int cx = xz[0] >> 4, cz = xz[1] >> 4;
        load(w, cx, cz, chunk -> {
            Location l = chunk == null ? null : search(w, cx, cz);
            if (l != null) done.accept(l);
            else Bukkit.getScheduler().runTask(plugin, () -> attempt(w, n + 1, done));
        });
    }

    private void load(World w, int cx, int cz, Consumer<Chunk> then) {
        try {
            w.getChunkAtAsync(cx, cz).whenComplete((c, err) -> {
                if (Bukkit.isPrimaryThread()) then.accept(err == null ? c : null);
                else Bukkit.getScheduler().runTask(plugin, () -> then.accept(err == null ? c : null));
            });
        } catch (NoSuchMethodError | NoClassDefFoundError notPaper) {
            then.accept(w.getChunkAt(cx, cz));
        }
    }

    private int[] randomXZ(World w) {
        String k = key(w.getEnvironment());
        WorldBorder b = w.getWorldBorder();
        int bx = b.getCenter().getBlockX(), bz = b.getCenter().getBlockZ();
        int half = (int) Math.max(32, b.getSize() / 2 - 16);
        int max = Math.min(half, plugin.getConfig().getInt("worlds." + k + ".radius", k.equals("nether") ? 2500 : 5000));
        int min = Math.min(max - 1, Math.max(0, plugin.getConfig().getInt("worlds." + k + ".min-radius", k.equals("end") ? 1100 : 200)));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x, z;
        do {
            x = r.nextInt(-max, max + 1);
            z = r.nextInt(-max, max + 1);
        } while (Math.max(Math.abs(x), Math.abs(z)) < min);
        return new int[]{bx + x, bz + z};
    }

    /** Tries 16 spots in the chunk, in random order. */
    private Location search(World w, int cx, int cz) {
        List<int[]> spots = new ArrayList<>();
        for (int x = 2; x < 16; x += 4)
            for (int z = 2; z < 16; z += 4) spots.add(new int[]{(cx << 4) + x, (cz << 4) + z});
        Collections.shuffle(spots);
        for (int[] s : spots) {
            Location l = column(w, s[0], s[1]);
            if (l != null) return l;
        }
        return null;
    }

    private Location column(World w, int x, int z) {
        if (w.getEnvironment() == World.Environment.NETHER) {
            for (int y = 31; y < 116; y++) {
                Block b = w.getBlockAt(x, y, z);
                if (safe(b)) return spot(b);
            }
            return null;
        }
        Block b = w.getHighestBlockAt(x, z);
        if (b.getY() <= w.getMinHeight()) return null;
        return safe(b) ? spot(b) : null;
    }

    private static Location spot(Block ground) {
        return ground.getLocation().add(0.5, 1, 0.5);
    }

    /** Ground you can stand on with two blocks of air above, inside the border. */
    public static boolean safe(Block ground) {
        if (!ground.getWorld().getWorldBorder().isInside(ground.getLocation())) return false;
        Material m = ground.getType();
        if (!m.isSolid() || bad(m)) return false;
        Block feet = ground.getRelative(0, 1, 0), head = ground.getRelative(0, 2, 0);
        return feet.getType().isAir() && head.getType().isAir();
    }

    private static boolean bad(Material m) {
        String n = m.name().toLowerCase(Locale.ROOT);
        return n.contains("leaves") || n.contains("magma") || n.contains("cactus") || n.contains("campfire")
                || n.contains("powder_snow") || n.contains("dripstone") || n.equals("bedrock") || n.contains("lava")
                || n.contains("water") || n.contains("fire") || n.contains("berry_bush");
    }
}
