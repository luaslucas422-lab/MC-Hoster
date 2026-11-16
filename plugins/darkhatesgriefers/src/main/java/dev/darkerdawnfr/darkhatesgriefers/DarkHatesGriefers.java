package dev.darkerdawnfr.darkhatesgriefers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dark Hates Griefers by DarkerDawnFR. Protected areas: nobody can break or place blocks there (broken blocks stay put),
 * no explosions, fire, pistons, lava/water flowing in, endermen or item frames being taken.
 * /dhg set 1, /dhg set 2 (or /dhg wand), /dhg create <name>, /dhg allow|deny <player> [area], /dhg on|off <area>.
 * "/dark hates griefers ..." works too.
 */
public class DarkHatesGriefers extends JavaPlugin implements Listener, TabCompleter {
    private Areas areas;
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        areas = new Areas(this, "&c&lGriefer Wand");
        areas.onBoth = this::autoProtect;
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("darkhatesgriefers");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
    }

    private final Map<UUID, String> lastMade = new HashMap<>();

    /** Both corners set: the area is protected right away (named area1, area2...; /dhg create <name> renames it). */
    public void autoProtect(Player p) {
        String[] why = {""};
        Areas.Area ar = areas.create(p, areas.nextName("area"), why);
        areas.clearPicks(p.getUniqueId());
        if (ar == null) {
            say(p, "&c" + why[0]);
            return;
        }
        lastMade.put(p.getUniqueId(), ar.name);
        say(p, "&aProtected! &f" + ar.name + " &a(" + ar.box() + " blocks): nobody can break or place blocks there now, you too.");
        say(p, "&7Name it: &f/dhg create <name>&7. Let someone build: &f/dhg allow <player> " + ar.name + "&7. Undo: &f/dhg delete " + ar.name);
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f);
    }

    public Areas areas() {
        return areas;
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&cHates Griefers&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
    }

    // ------------------------------------------------------------------ who can build where

    /** The protected area (switched on) at this spot, or null. */
    public Areas.Area protectedAt(Location l) {
        for (Areas.Area a : areas.at(l)) if (a.on) return a;
        return null;
    }

    /** Can this player change blocks here? Allowed players (per area, or everywhere with "*") and bypass can. */
    public boolean canBuild(UUID id, Location l, boolean bypass) {
        if (bypass) return true;
        for (Areas.Area a : areas.at(l)) {
            if (!a.on) continue;
            Areas.Area all = areas.get("*");
            if (!a.allowed.contains(id) && (all == null || !all.allowed.contains(id))) return false;
        }
        return true;
    }

    public boolean canBuild(Player p, Location l) {
        return canBuild(p.getUniqueId(), l, p.hasPermission("darkhatesgriefers.bypass"));
    }

    private void deny(Player p, Areas.Area a) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        lastWarn.put(p.getUniqueId(), now);
        String msg = getConfig().getString("deny-message", "&cThis area is protected. &7No breaking or placing here.");
        say(p, msg + (p.hasPermission("darkhatesgriefers.admin") ? " &8(you: /dhg allow " + p.getName() + " " + a.name + ")" : ""));
    }

    /** Puts the block back for the player at once, so it looks like it fixed itself. */
    private void remake(Player p, Block b) {
        BlockData d = b.getBlockData();
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline()) p.sendBlockChange(b.getLocation(), d);
            if (getConfig().getBoolean("remake-effect", true))
                b.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0);
        });
    }

    // ------------------------------------------------------------------ protection

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        Areas.Area a = protectedAt(e.getBlock().getLocation());
        if (a == null || canBuild(e.getPlayer(), e.getBlock().getLocation())) return;
        e.setCancelled(true);
        remake(e.getPlayer(), e.getBlock());
        deny(e.getPlayer(), a);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Areas.Area a = protectedAt(e.getBlock().getLocation());
        if (a == null || canBuild(e.getPlayer(), e.getBlock().getLocation())) return;
        e.setCancelled(true);
        deny(e.getPlayer(), a);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent e) {
        Location l = e.getBlockClicked().getRelative(e.getBlockFace()).getLocation();
        Areas.Area a = protectedAt(l);
        if (a == null || canBuild(e.getPlayer(), l)) return;
        e.setCancelled(true);
        deny(e.getPlayer(), a);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void bucketFill(PlayerBucketFillEvent e) {
        Areas.Area a = protectedAt(e.getBlockClicked().getLocation());
        if (a == null || canBuild(e.getPlayer(), e.getBlockClicked().getLocation())) return;
        e.setCancelled(true);
        deny(e.getPlayer(), a);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        Areas.Area a = protectedAt(b.getLocation());
        if (a == null || canBuild(e.getPlayer(), b.getLocation())) return;
        Material m = b.getType();
        if (e.getAction() == Action.PHYSICAL) { // trampling farmland, turtle eggs
            if (m == Material.FARMLAND || m == Material.TURTLE_EGG) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        boolean container = b.getState() instanceof InventoryHolder;
        if (container && getConfig().getBoolean("protect-containers", true)) {
            e.setCancelled(true);
            deny(e.getPlayer(), a);
            return;
        }
        // things that change blocks when clicked: flint and steel, bone meal, hoes, axes stripping logs, buckets...
        if (e.getItem() != null) {
            String it = e.getItem().getType().name();
            if (it.equals("FLINT_AND_STEEL") || it.equals("FIRE_CHARGE") || it.equals("BONE_MEAL") || it.endsWith("_HOE")
                    || it.endsWith("_AXE") || it.endsWith("_SHOVEL") || it.equals("ARMOR_STAND") || it.endsWith("_BOAT")
                    || it.endsWith("_MINECART") || it.equals("END_CRYSTAL") || it.endsWith("_SPAWN_EGG")) {
                e.setCancelled(true);
                deny(e.getPlayer(), a);
                return;
            }
        }
        String bn = m.name();
        if (bn.endsWith("_SIGN") || bn.equals("REPEATER") || bn.equals("COMPARATOR") || bn.equals("NOTE_BLOCK") || bn.equals("DAYLIGHT_DETECTOR")
                || bn.endsWith("CANDLE") || bn.endsWith("CAKE") || bn.equals("FLOWER_POT") || bn.startsWith("POTTED_") || bn.equals("JUKEBOX")
                || bn.equals("COMPOSTER") || bn.equals("RESPAWN_ANCHOR") || bn.equals("CHISELED_BOOKSHELF") || bn.equals("DECORATED_POT")) {
            e.setCancelled(true);
            deny(e.getPlayer(), a);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void entityClick(PlayerInteractEntityEvent e) {
        String t = e.getRightClicked().getType().name();
        if (!t.contains("ITEM_FRAME") && !t.equals("ARMOR_STAND") && !t.equals("PAINTING")) return;
        Location l = e.getRightClicked().getLocation();
        Areas.Area a = protectedAt(l);
        if (a == null || canBuild(e.getPlayer(), l)) return;
        e.setCancelled(true);
        deny(e.getPlayer(), a);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void hanging(HangingBreakByEntityEvent e) {
        Areas.Area a = protectedAt(e.getEntity().getLocation());
        if (a == null) return;
        Player p = player(e.getRemover());
        if (p != null && canBuild(p, e.getEntity().getLocation())) return;
        e.setCancelled(true);
        if (p != null) deny(p, a);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void frameDamage(EntityDamageByEntityEvent e) {
        String t = e.getEntity().getType().name();
        if (!t.contains("ITEM_FRAME") && !t.equals("ARMOR_STAND")) return;
        Areas.Area a = protectedAt(e.getEntity().getLocation());
        if (a == null) return;
        Player p = player(e.getDamager());
        if (p != null && canBuild(p, e.getEntity().getLocation())) return;
        e.setCancelled(true);
    }

    private static Player player(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /** Explosions don't break protected blocks. */
    public void keepProtected(List<Block> blocks) {
        blocks.removeIf(b -> protectedAt(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) {
        keepProtected(e.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void explodeBlock(BlockExplodeEvent e) {
        keepProtected(e.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) {
        if (protectedAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void ignite(BlockIgniteEvent e) {
        if (protectedAt(e.getBlock().getLocation()) == null) return;
        if (e.getPlayer() != null && canBuild(e.getPlayer(), e.getBlock().getLocation())) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void spread(BlockSpreadEvent e) {
        if (e.getSource().getType() == Material.FIRE && protectedAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void flow(BlockFromToEvent e) {
        // water or lava from outside can't flow in
        if (protectedAt(e.getToBlock().getLocation()) != null && protectedAt(e.getBlock().getLocation()) == null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void pistonOut(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks())
            if (protectedAt(b.getRelative(e.getDirection()).getLocation()) != null || protectedAt(b.getLocation()) != null) {
                if (protectedAt(e.getBlock().getLocation()) == null) {
                    e.setCancelled(true);
                    return;
                }
            }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void pistonIn(BlockPistonRetractEvent e) {
        if (protectedAt(e.getBlock().getLocation()) != null) return;
        for (Block b : e.getBlocks())
            if (protectedAt(b.getLocation()) != null) {
                e.setCancelled(true);
                return;
            }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void mobBlocks(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player p) {
            if (!canBuild(p, e.getBlock().getLocation())) e.setCancelled(true);
            return;
        }
        // endermen, withers, ravagers, silverfish, sheep eating grass is fine
        if (e.getEntity().getType().name().equals("SHEEP")) return;
        if (e.getEntity().getType().name().equals("FALLING_BLOCK") && protectedAt(e.getBlock().getLocation()) == null) return;
        if (protectedAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    /** "/dark hates griefers set 1" and "/darkhates griefers ..." go to /dhg. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void longName(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage();
        String low = m.toLowerCase(Locale.ROOT).replaceAll(" +", " ");
        for (String pre : List.of("/dark hates griefers", "/darkhates griefers", "/dark hatesgriefers"))
            if (low.equals(pre) || low.startsWith(pre + " ")) {
                e.setMessage("/darkhatesgriefers" + low.substring(pre.length()));
                return;
            }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission("darkhatesgriefers.admin");
        if (sub.equals("test") && admin) {
            test(s);
            return true;
        }
        if (!admin) {
            if (s instanceof Player p) {
                Areas.Area here = protectedAt(p.getLocation());
                return say(p, here == null ? "&7You're not in a protected area." : "&7You're in the protected area &f" + here.name
                        + (canBuild(p, p.getLocation()) ? " &a(you can build here)" : " &c(no building)") + "&7.");
            }
            return true;
        }
        switch (sub) {
            case "set", "pos", "corner" -> {
                if (!(s instanceof Player p)) return true;
                String n = a.length > 1 ? a[a.length - 1] : ""; // "set 1" and "set block 1" both work
                if (!n.equals("1") && !n.equals("2")) return say(p, "&7Usage: /dhg set 1  then  /dhg set 2  &8(the block you look at)");
                Location l = areas.pick(p, Integer.parseInt(n));
                say(p, "&7Corner &f" + n + " &7set: &f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ()
                        + (areas.bothSet(p.getUniqueId()) ? "" : "&7. Now: &f/dhg set " + (n.equals("1") ? 2 : 1)));
                if (areas.bothSet(p.getUniqueId())) autoProtect(p);
            }
            case "wand" -> {
                if (!(s instanceof Player p)) return true;
                for (ItemStack rest : p.getInventory().addItem(areas.wand()).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
                say(p, "&7Left-click a block for corner 1, right-click for corner 2, then &f/dhg create <name>&7.");
            }
            case "create", "save", "protect" -> {
                if (!(s instanceof Player p)) return true;
                String name = a.length > 1 ? a[1] : areas.nextName("area");
                String last = lastMade.get(p.getUniqueId());
                if (!areas.bothSet(p.getUniqueId()) && last != null && areas.get(last) != null && a.length > 1) {
                    if (!areas.rename(last, name)) return say(p, "&cCan't use that name (taken or no letters).");
                    lastMade.put(p.getUniqueId(), name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", ""));
                    return say(p, "&a" + last + " is now called &f" + name + "&a.");
                }
                String[] why = {""};
                Areas.Area ar = areas.create(p, name, why);
                if (ar == null) return say(p, "&c" + why[0]);
                say(p, "&aProtected &f" + ar.name + " &a(" + ar.box() + " blocks). Nobody can break or place there now.");
                say(p, "&7Let someone build: &f/dhg allow <player> " + ar.name + "&7. Switch it: &f/dhg off " + ar.name);
            }
            case "delete", "remove" -> {
                if (a.length < 2) return say(s, "&7Usage: /dhg delete <area>");
                say(s, areas.delete(a[1]) ? "&7Area &f" + a[1] + " &7deleted." : "&cNo area called " + a[1] + ".");
            }
            case "on", "off" -> {
                Areas.Area ar = a.length > 1 ? areas.get(a[1]) : s instanceof Player p ? first(p) : null;
                if (ar == null) return say(s, "&7Usage: /dhg " + sub + " <area>");
                ar.on = sub.equals("on");
                areas.save();
                say(s, "&7Protection of &f" + ar.name + " &7is now " + (ar.on ? "&aON" : "&cOFF") + "&7.");
            }
            case "allow", "deny", "trust", "untrust" -> {
                if (a.length < 2) return say(s, "&7Usage: /dhg " + sub + " <player> [area]  &8(no area = every area)");
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                boolean allow = sub.equals("allow") || sub.equals("trust");
                Areas.Area ar;
                if (a.length > 2) {
                    ar = areas.get(a[2]);
                    if (ar == null) return say(s, "&cNo area called " + a[2] + ".");
                } else {
                    ar = areas.get("*");
                    if (ar == null) { // "*" holds who may build in every area
                        ar = new Areas.Area("*", "__none__", 0, 0, 0, 0, 0, 0);
                        areas.all().put("*", ar);
                    }
                }
                if (allow) ar.allowed.add(t.getUniqueId());
                else ar.allowed.remove(t.getUniqueId());
                areas.save();
                say(s, "&f" + a[1] + (allow ? " &acan now build in " : " &ccan't build in ") + (ar.name.equals("*") ? "every protected area" : "&f" + ar.name) + "&7.");
            }
            case "list" -> {
                if (areas.all().isEmpty()) return say(s, "&7No protected areas yet. &f/dhg set 1&7, &f/dhg set 2&7, &f/dhg create <name>");
                for (Areas.Area ar : areas.all().values()) {
                    if (ar.name.equals("*")) {
                        say(s, "&7Can build everywhere: &f" + names(ar));
                        continue;
                    }
                    say(s, "&f" + ar.name + " &7" + ar.world + " " + ar.x1 + "," + ar.y1 + "," + ar.z1 + " → " + ar.x2 + "," + ar.y2 + "," + ar.z2
                            + " " + (ar.on ? "&aON" : "&cOFF") + (ar.allowed.isEmpty() ? "" : " &7allowed: &f" + names(ar)));
                }
            }
            case "info", "here" -> {
                if (!(s instanceof Player p)) return true;
                List<Areas.Area> here = areas.at(p.getLocation());
                if (here.isEmpty()) return say(p, "&7No protected area here.");
                for (Areas.Area ar : here) say(p, "&7Here: &f" + ar.name + " " + (ar.on ? "&aON" : "&cOFF") + " &7(" + ar.box() + ")");
            }
            case "reload" -> {
                reloadConfig();
                areas.load();
                say(s, "&aReloaded. Areas: " + areas.all().keySet());
            }
            default -> {
                say(s, "&fDark Hates Griefers &7- protected areas where nobody breaks or places blocks.");
                say(s, "&f/dhg set 1 &7and &f/dhg set 2 &7(the block you look at) or &f/dhg wand");
                say(s, "&f/dhg create <name>  &f/dhg on|off <area>  &f/dhg allow|deny <player> [area]  &f/dhg list  &f/dhg delete <area>");
            }
        }
        return true;
    }

    private Areas.Area first(Player p) {
        List<Areas.Area> l = areas.at(p.getLocation());
        return l.isEmpty() ? null : l.get(0);
    }

    private static String names(Areas.Area a) {
        List<String> n = new ArrayList<>();
        for (UUID u : a.allowed) {
            OfflinePlayer o = Bukkit.getOfflinePlayer(u);
            n.add(o.getName() != null ? o.getName() : u.toString().substring(0, 8));
        }
        return n.isEmpty() ? "nobody" : String.join(", ", n);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("darkhatesgriefers.admin")) return out;
        if (a.length == 1) out.addAll(List.of("set", "wand", "create", "delete", "on", "off", "allow", "deny", "list", "info", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("set")) out.addAll(List.of("1", "2"));
        else if (a.length == 2 && List.of("delete", "on", "off").contains(a[0].toLowerCase(Locale.ROOT))) {
            for (String k : areas.all().keySet()) if (!k.equals("*")) out.add(k);
        } else if (a.length == 2 && List.of("allow", "deny").contains(a[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 3 && List.of("allow", "deny").contains(a[0].toLowerCase(Locale.ROOT))) {
            for (String k : areas.all().keySet()) if (!k.equals("*")) out.add(k);
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ test

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID builder = UUID.randomUUID(), griefer = UUID.randomUUID();
        String name = "dhgtest";
        try {
            areas.delete(name);
            org.bukkit.World w = Bukkit.getWorlds().get(0);
            Location sp = w.getSpawnLocation();
            int bx = sp.getBlockX(), by = sp.getBlockY(), bz = sp.getBlockZ(); // near spawn: those chunks are loaded already
            Location c1 = new Location(w, bx + 10, by - 10, bz + 5), c2 = new Location(w, bx, by + 10, bz + 15);
            String[] why = {""};
            Areas.Area ar = areas.create(name, c1, c2, why);
            if (ar == null) throw new IllegalStateException(why[0]);
            Location in = new Location(w, bx + 5, by, bz + 10), out = new Location(w, bx + 11, by, bz + 10);
            if (protectedAt(in) == null || protectedAt(out) != null) fails.add("area box");
            if (!ar.box().equals("11x21x11")) fails.add("box " + ar.box());
            if (canBuild(griefer, in, false) || !canBuild(griefer, out, false) || !canBuild(griefer, in, true)) fails.add("build rules");
            ar.allowed.add(builder);
            areas.save();
            areas.load();
            Areas.Area back = areas.get(name);
            if (back == null || !back.allowed.contains(builder) || back.x1 != bx || back.y2 != by + 10) fails.add("not saved");
            if (!canBuild(builder, in, false) || canBuild(griefer, in, false)) fails.add("allowed player");
            back.on = false;
            if (protectedAt(in) != null || !canBuild(griefer, in, false)) fails.add("turning it off");
            if (areas.create(name, c1, c2, why) != null) fails.add("same name twice");
            if (!areas.rename(name, "dhgtest2") || areas.get("dhgtest2") == null || areas.get(name) != null) fails.add("rename");
            areas.rename("dhgtest2", name);
            back = areas.get(name);
            // a real block that breaking can't remove: the event is cancelled
            back.on = true;
            Block b = w.getBlockAt(bx + 5, by, bz + 10);
            Material was = b.getType();
            b.setType(Material.STONE);
            List<Block> boom = new ArrayList<>(List.of(b, w.getBlockAt(bx + 11, by, bz + 10)));
            keepProtected(boom);
            if (boom.size() != 1 || boom.get(0).getX() != bx + 11) fails.add("explosion not stopped");
            b.setType(was);
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            areas.delete(name);
        }
        String r = fails.isEmpty() ? "DARKHATESGRIEFERS TEST OK" : "DARKHATESGRIEFERS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }
}
