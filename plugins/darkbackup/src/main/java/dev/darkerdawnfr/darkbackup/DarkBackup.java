package dev.darkerdawnfr.darkbackup;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Dark Backup by DarkerDawnFR: saves a zip of every world every few hours (and with /backup),
 * keeping the newest ones. The server keeps running while it backs up.
 */
public class DarkBackup extends JavaPlugin implements TabCompleter {
    private volatile boolean running;
    private long next;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginCommand pc = getCommand("backup");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        schedule();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (hours() > 0 && System.currentTimeMillis() >= next && !running) {
                if (Bukkit.getOnlinePlayers().isEmpty() && getConfig().getBoolean("skip-when-empty", true) && !changedSinceLast()) {
                    schedule();
                    return;
                }
                backup(null, f -> schedule());
            }
        }, 20 * 60, 20 * 60);
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void join(org.bukkit.event.player.PlayerJoinEvent e) {
                playedSinceBackup = true;
            }
        }, this);
    }

    private boolean playedSinceBackup = true;

    private boolean changedSinceLast() {
        return playedSinceBackup;
    }

    private double hours() {
        return getConfig().getDouble("every-hours", 6);
    }

    private void schedule() {
        next = System.currentTimeMillis() + (long) (Math.max(0.1, hours()) * 3_600_000L);
    }

    public File folder() {
        File f = new File(getConfig().getString("folder", "backups"));
        if (!f.isAbsolute()) f = new File(Bukkit.getWorldContainer(), f.getPath());
        return f;
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&9Backup&8] "));
    }

    private void tell(CommandSender s, String text) {
        String t = prefix() + Util.color(text);
        if (s != null) s.sendMessage(t);
        else getLogger().info(org.bukkit.ChatColor.stripColor(t));
    }

    // ------------------------------------------------------------------ backing up

    /** Saves the worlds, zips them in the background, then deletes old backups. done gets the zip (null on failure). */
    public void backup(CommandSender who, Consumer<File> done) {
        if (running) {
            tell(who, "&cA backup is already running.");
            return;
        }
        running = true;
        playedSinceBackup = !Bukkit.getOnlinePlayers().isEmpty();
        if (getConfig().getBoolean("announce", true)) Bukkit.broadcastMessage(prefix() + Util.color("&7Backing up the world..."));
        Map<World, Boolean> autosave = new HashMap<>();
        List<File> worlds = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (getConfig().getStringList("skip-worlds").contains(w.getName())) continue;
            w.save();
            autosave.put(w, w.isAutoSave());
            w.setAutoSave(false); // nothing is written while we copy
            worlds.add(w.getWorldFolder());
        }
        String name = "backup-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".zip";
        File out = new File(folder(), name);
        long t0 = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            File result = null;
            int skipped = 0;
            try {
                folder().mkdirs();
                skipped = zip(worlds, out);
                result = out;
            } catch (IOException e) {
                getLogger().warning("Backup failed: " + e);
                out.delete();
            }
            File r = result;
            int sk = skipped;
            Bukkit.getScheduler().runTask(this, () -> {
                autosave.forEach(World::setAutoSave);
                running = false;
                if (r != null) {
                    int removed = prune();
                    String msg = "&7Backup done: &f" + r.getName() + " &7(" + (r.length() / 1048576) + " MB, "
                            + (System.currentTimeMillis() - t0) / 1000 + "s" + (sk > 0 ? ", " + sk + " files in use skipped" : "")
                            + (removed > 0 ? ", " + removed + " old removed" : "") + ")";
                    if (getConfig().getBoolean("announce", true)) Bukkit.broadcastMessage(prefix() + Util.color(msg));
                    else tell(who, msg);
                } else tell(who, "&cThe backup failed, check the console.");
                done.accept(r);
            });
        });
    }

    /** Zips the world folders; returns how many files couldn't be read (e.g. session.lock). */
    private static int zip(List<File> worlds, File out) throws IOException {
        int skipped = 0;
        java.util.Set<String> added = new java.util.HashSet<>();
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(tmp.toPath()))) {
            for (File w : worlds) {
                Path base = w.toPath().getParent();
                try (Stream<Path> files = Files.walk(w.toPath())) {
                    for (Path p : (Iterable<Path>) files::iterator) {
                        if (Files.isDirectory(p) || p.getFileName().toString().equals("session.lock")) continue;
                        String entry = base.relativize(p).toString().replace('\\', '/');
                        if (!added.add(entry)) continue; // a world inside another world's folder
                        try (InputStream in = Files.newInputStream(p)) {
                            z.putNextEntry(new ZipEntry(entry));
                            in.transferTo(z);
                            z.closeEntry();
                        } catch (IOException e) {
                            skipped++;
                        }
                    }
                }
            }
        }
        Files.move(tmp.toPath(), out.toPath());
        return skipped;
    }

    public List<File> backups() {
        File[] fs = folder().listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        List<File> l = new ArrayList<>(fs == null ? List.of() : Arrays.asList(fs));
        l.sort(Comparator.comparing(File::getName).reversed());
        return l;
    }

    /** Keeps the newest `keep` backups. Returns how many were deleted. */
    public int prune() {
        int keep = Math.max(1, getConfig().getInt("keep", 10)), removed = 0;
        List<File> all = backups();
        for (int i = keep; i < all.size(); i++) if (all.get(i).delete()) removed++;
        return removed;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "now", "" -> {
                if (sub.isEmpty()) {
                    long mins = Math.max(0, (next - System.currentTimeMillis()) / 60000);
                    tell(s, "&7Backups every &f" + hours() + "h&7, next in &f" + mins + " min&7. Keeping the newest &f"
                            + getConfig().getInt("keep", 10) + "&7. Saved in &f" + folder().getPath());
                    tell(s, "&7/backup now  &8|  &7/backup list  &8|  &7/backup restore");
                    return true;
                }
                backup(s, f -> { });
            }
            case "list" -> {
                List<File> l = backups();
                if (l.isEmpty()) tell(s, "&7No backups yet. &f/backup now &7makes one.");
                for (File f : l) s.sendMessage(Util.color("&8 - &f" + f.getName() + " &7(" + f.length() / 1048576 + " MB)"));
            }
            case "restore" -> {
                tell(s, "&7To restore a backup: stop the server, open &f" + folder().getPath() + "&7, and unzip the backup over the"
                        + " server folder (replace the world folders). Then start the server again.");
            }
            case "reload" -> {
                reloadConfig();
                schedule();
                tell(s, "&aReloaded.");
            }
            case "test" -> test(s);
            default -> tell(s, "&7/backup now  &8|  &7/backup list  &8|  &7/backup restore");
        }
        return true;
    }

    private void test(CommandSender s) {
        List<File> fake = new ArrayList<>();
        try {
            folder().mkdirs();
            for (int i = 0; i < getConfig().getInt("keep", 10) + 3; i++) {
                File f = new File(folder(), "backup-2000-01-" + String.format("%02d", i + 1) + "_00-00-00.zip");
                Files.write(f.toPath(), new byte[]{0});
                fake.add(f);
            }
        } catch (IOException e) {
            report(s, "DARKBACKUP TEST FAIL: " + e);
            return;
        }
        backup(s, zip -> {
            List<String> fails = new ArrayList<>();
            if (zip == null || !zip.exists()) fails.add("no backup made");
            else {
                try (ZipFile z = new ZipFile(zip)) {
                    for (World w : Bukkit.getWorlds())
                        if (z.getEntry(w.getWorldFolder().getName() + "/level.dat") == null) fails.add(w.getName() + "/level.dat missing");
                } catch (IOException e) {
                    fails.add("zip unreadable: " + e);
                }
                if (!backups().contains(zip)) fails.add("new backup was pruned");
            }
            if (backups().size() > getConfig().getInt("keep", 10)) fails.add("old backups not removed: " + backups().size());
            for (World w : Bukkit.getWorlds()) if (!w.isAutoSave()) fails.add("autosave left off in " + w.getName());
            for (File f : fake) f.delete();
            report(s, fails.isEmpty() ? "DARKBACKUP TEST OK (" + (zip == null ? 0 : zip.length() / 1024) + " KB)" : "DARKBACKUP TEST FAIL: " + fails);
        });
    }

    private void report(CommandSender s, String r) {
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("now", "list", "restore", "reload"));
        out.removeIf(v -> !v.startsWith(a[a.length - 1].toLowerCase(Locale.ROOT)));
        return out;
    }

    @SuppressWarnings("unused")
    private static void copy(InputStream in, OutputStream out) throws IOException {
        in.transferTo(out);
    }
}
