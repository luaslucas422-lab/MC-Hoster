package dev.darkerdawnfr.darknpcs;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dark NPCs by DarkerDawnFR: Hypixel-style NPCs that run a command when clicked.
 * /npc create: type a player (for the skin), pick a command in a menu, and it stands where you are, with its name and
 * "CLICK ME" above it. /npc delete (the one you look at, or by id), /npc list, /npc edit. On Minecraft 1.21.9+ it's a
 * real player-shaped mannequin with the skin; on older versions an armor stand wearing that player's head.
 */
public class DarkNPCs extends JavaPlugin implements Listener, TabCompleter {
    public static final class Npc {
        public final String id;
        public String name, skin, command;
        public boolean console;
        public Location at;

        Npc(String id) {
            this.id = id;
        }
    }

    static final String TAG = "darknpc";
    private final Map<String, Npc> npcs = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private File file;
    private Ask ask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "npcs.yml");
        ask = new Ask(this);
        load();
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("darknpc");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTask(this, this::spawnAll);
        Bukkit.getScheduler().runTaskTimer(this, this::lookAtPlayers, 40, 5);
    }

    @Override
    public void onDisable() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&eNPCs&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
    }

    public Map<String, Npc> npcs() {
        return npcs;
    }

    // ------------------------------------------------------------------ spawning

    /** True if this server has player-shaped mannequins (Minecraft 1.21.9+). */
    public static boolean mannequins() {
        try {
            EntityType.valueOf("MANNEQUIN");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void spawnAll() {
        for (Npc n : npcs.values()) if (n.at != null && n.at.getWorld() != null && n.at.isChunkLoaded()) spawn(n);
    }

    private static String tagOf(Npc n) {
        return TAG + ":" + n.id;
    }

    public void despawn(Npc n) {
        if (n.at == null || n.at.getWorld() == null) return;
        for (Entity e : n.at.getWorld().getNearbyEntities(n.at, 4, 6, 4))
            if (e.getScoreboardTags().contains(tagOf(n))) e.remove();
    }

    public Entity spawn(Npc n) {
        despawn(n);
        World w = n.at.getWorld();
        Entity body;
        if (mannequins()) {
            body = w.spawnEntity(n.at, EntityType.valueOf("MANNEQUIN"));
            skin(body, n.skin);
        } else {
            ArmorStand a = w.spawn(n.at, ArmorStand.class);
            a.setArms(true);
            a.setBasePlate(false);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(n.skin));
            head.setItemMeta(sm);
            a.getEquipment().setHelmet(head);
            a.getEquipment().setChestplate(leather(Material.LEATHER_CHESTPLATE));
            a.getEquipment().setLeggings(leather(Material.LEATHER_LEGGINGS));
            a.getEquipment().setBoots(leather(Material.LEATHER_BOOTS));
            body = a;
        }
        body.setInvulnerable(true);
        body.setPersistent(false);
        body.setGravity(false);
        body.setSilent(true);
        body.setCustomNameVisible(false);
        body.addScoreboardTag(TAG);
        body.addScoreboardTag(tagOf(n));
        if (body instanceof LivingEntity le) {
            le.setAI(false);
            le.setRemoveWhenFarAway(false);
            le.setCollidable(false);
        }
        // the name, and "CLICK ME" under it
        String[] lines = {n.name, getConfig().getString("click-me", "&e&lCLICK ME")};
        for (int i = 0; i < lines.length; i++) {
            Location l = n.at.clone().add(0, 2.25 - i * 0.28, 0);
            TextDisplay t = w.spawn(l, TextDisplay.class);
            t.setText(Util.color(lines[i]));
            t.setBillboard(Display.Billboard.CENTER);
            t.setPersistent(false);
            t.setShadowed(true);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.addScoreboardTag(TAG);
            t.addScoreboardTag(tagOf(n));
        }
        return body;
    }

    private static ItemStack leather(Material m) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta lm = (LeatherArmorMeta) it.getItemMeta();
        lm.setColor(Color.fromRGB(0x2B3A55));
        it.setItemMeta(lm);
        return it;
    }

    /** Puts a player's skin on a mannequin (Paper and Spigot do it differently, so both are tried). */
    private void skin(Entity body, String name) {
        try {
            for (Method m : body.getClass().getMethods()) {
                if (m.getParameterCount() != 1) continue;
                String mn = m.getName();
                Class<?> t = m.getParameterTypes()[0];
                if (mn.equals("setPlayerProfile") && t.isAssignableFrom(org.bukkit.profile.PlayerProfile.class)) {
                    // look the skin up without freezing the server, then put it on
                    Bukkit.createPlayerProfile(name).update().thenAccept(pp -> Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            if (body.isValid()) m.invoke(body, pp);
                        } catch (Throwable ignored) {
                        }
                    }));
                    return;
                }
                if (mn.equals("setProfile")) {
                    // Paper: ResolvableProfile.resolvableProfile(PlayerProfile) from Bukkit.createProfile(name)
                    Object paper = Bukkit.class.getMethod("createProfile", String.class).invoke(null, name);
                    for (Method f : t.getMethods())
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getParameterCount() == 1
                                && f.getParameterTypes()[0].isInstance(paper) && t.isAssignableFrom(f.getReturnType())) {
                            m.invoke(body, f.invoke(null, paper));
                            return;
                        }
                }
            }
        } catch (Throwable e) {
            getLogger().fine("No skin for " + name + ": " + e);
        }
    }

    @EventHandler
    public void chunk(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        for (Npc n : npcs.values())
            if (n.at != null && n.at.getWorld() == c.getWorld() && n.at.getBlockX() >> 4 == c.getX() && n.at.getBlockZ() >> 4 == c.getZ())
                Bukkit.getScheduler().runTask(this, () -> spawn(n));
    }

    /** NPCs turn their head to the nearest player. */
    private void lookAtPlayers() {
        if (!getConfig().getBoolean("look-at-players", true)) return;
        for (Npc n : npcs.values()) {
            if (n.at == null || n.at.getWorld() == null || !n.at.isChunkLoaded()) continue;
            Player near = null;
            double best = 64;
            for (Player p : n.at.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(n.at);
                if (d < best) {
                    best = d;
                    near = p;
                }
            }
            if (near == null) continue;
            org.bukkit.util.Vector dir = near.getEyeLocation().toVector().subtract(n.at.clone().add(0, 1.6, 0).toVector());
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            float pitch = (float) -Math.toDegrees(Math.atan2(dir.getY(), Math.hypot(dir.getX(), dir.getZ())));
            for (Entity e : n.at.getWorld().getNearbyEntities(n.at, 1, 3, 1))
                if (e.getScoreboardTags().contains(tagOf(n)) && !(e instanceof TextDisplay)) {
                    e.setRotation(yaw, pitch);
                    if (e instanceof ArmorStand a) a.setHeadPose(new org.bukkit.util.EulerAngle(Math.toRadians(pitch), 0, 0));
                }
        }
    }

    // ------------------------------------------------------------------ clicking

    public Npc npcOf(Entity e) {
        for (String t : e.getScoreboardTags())
            if (t.startsWith(TAG + ":")) return npcs.get(t.substring(TAG.length() + 1));
        return null;
    }

    /** The command an NPC runs for a player ({player} = their name). */
    public static String commandFor(Npc n, String player) {
        String c = n.command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        return c.replace("{player}", player);
    }

    private void use(Player p, Npc n) {
        long now = System.currentTimeMillis();
        Long last = cooldown.get(p.getUniqueId());
        if (last != null && now - last < 600) return; // one click, not three
        cooldown.put(p.getUniqueId(), now);
        if (p.isSneaking() && p.hasPermission("darknpcs.admin")) {
            editor(p, n).open(p);
            return;
        }
        String cmd = commandFor(n, p.getName());
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
        if (n.console) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        else p.performCommand(cmd);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void clickAt(PlayerInteractAtEntityEvent e) {
        Npc n = npcOf(e.getRightClicked());
        if (n == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) use(e.getPlayer(), n);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void click(PlayerInteractEntityEvent e) {
        Npc n = npcOf(e.getRightClicked());
        if (n == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) use(e.getPlayer(), n);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void hit(EntityDamageByEntityEvent e) {
        Npc n = npcOf(e.getEntity());
        if (n == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) use(p, n);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void hurt(EntityDamageEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void stand(PlayerArmorStandManipulateEvent e) {
        if (e.getRightClicked().getScoreboardTags().contains(TAG)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ making and changing NPCs

    public Npc create(String name, String skin, String command, boolean console, Location at) {
        String base = name.replaceAll("&.", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (base.isEmpty()) base = "npc";
        String id = base;
        for (int i = 2; npcs.containsKey(id); i++) id = base + i;
        Npc n = new Npc(id);
        n.name = name;
        n.skin = skin;
        n.command = command;
        n.console = console;
        n.at = at.clone();
        npcs.put(id, n);
        save();
        spawn(n);
        return n;
    }

    public boolean delete(String id) {
        Npc n = npcs.remove(id);
        if (n == null) return false;
        despawn(n);
        save();
        return true;
    }

    /** The NPC the player looks at (within 6 blocks), or the nearest within 4. */
    public Npc target(Player p) {
        var hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 6, 0.3, e -> e != p && npcOf(e) != null);
        if (hit != null && hit.getHitEntity() != null) return npcOf(hit.getHitEntity());
        Npc best = null;
        double d = 16;
        for (Npc n : npcs.values())
            if (n.at != null && n.at.getWorld() == p.getWorld() && n.at.distanceSquared(p.getLocation()) < d) {
                d = n.at.distanceSquared(p.getLocation());
                best = n;
            }
        return best;
    }

    // [label, command, icon] picked from what's installed
    private List<String[]> presets() {
        String[][] all = {
                {"Random teleport", "rtp", "ENDER_PEARL", "DarkRTP"}, {"Shop", "shop", "EMERALD", "DarkShop"}, {"Sell", "sell", "CHEST", "DarkShop"},
                {"Auction house", "ah", "GOLD_INGOT", "DarkAH"}, {"Hubs", "hub", "RED_BED", "DarkHub"}, {"Spawn", "spawn", "COMPASS", "DarkEssentials"},
                {"Shard shop", "shards", "AMETHYST_SHARD", "DarkShards"}, {"Crates", "crate", "TRIPWIRE_HOOK", "DarkCrates"},
                {"Rank shop", "rank shop", "NETHER_STAR", "DarkRanks"}, {"Store", "store", "TOTEM_OF_UNDYING", "DarkRanks"},
                {"Settings", "settings", "COMPARATOR", "DarkSettings"}, {"Duels", "duel", "IRON_SWORD", "DarkDuels"},
                {"Homes", "home", "WHITE_BED", "DarkHomes"}, {"Teams", "team", "BLUE_BANNER", "DarkTeams"},
                {"Fish collection", "fish", "FISHING_ROD", "DarkFishing"}, {"AFK zone", "afkzone", "CLOCK", "DarkAFKZone"},
                {"Rename items", "dn", "NAME_TAG", "DarkNames"}, {"Discord", "dc", "PURPLE_DYE", "DarkDiscord"},
                {"Rules", "rules", "BOOK", ""}, {"Spawners", "spawners", "SPAWNER", "DarkSpawners"}};
        List<String[]> out = new ArrayList<>();
        for (String[] a : all) if (a[3].isEmpty() || Bukkit.getPluginManager().getPlugin(a[3]) != null) out.add(a);
        return out;
    }

    /** "Pick the command": presets from the installed plugins, or type your own. */
    public Menu commandPicker(Player p, java.util.function.BiConsumer<String, Boolean> then) {
        List<String[]> ps = presets();
        Menu m = new Menu(Math.min(6, Math.max(3, (ps.size() + 8) / 9 + 2)), "&8" + Util.small("Pick the command"));
        int slot = 0;
        for (String[] c : ps) {
            Material icon = Util.material(c[2]);
            m.set(slot++, Util.item(icon == null ? Material.PAPER : icon, "&e" + c[0], "&7Runs &f/" + c[1], "", "&eClick"), e -> then.accept(c[1], false));
        }
        int last = m.size() - 1;
        m.set(last - 1, Util.item(Material.WRITABLE_BOOK, "&fType your own", "&7Any command, like &f/warp pvp", "&7{player} = who clicked"),
                e -> ask.ask(p, "the command (without /, {player} = who clicked)", t -> then.accept(t.trim(), false), null));
        m.set(last, Util.item(Material.COMMAND_BLOCK, "&cType your own (as the console)", "&7Runs as the server, so players",
                "&7don't need the permission. &f{player} &7= who clicked"),
                e -> ask.ask(p, "the console command ({player} = who clicked)", t -> then.accept(t.trim(), true), null));
        m.fill();
        return m;
    }

    private void startCreate(Player p) {
        ask.ask(p, "the player whose skin the NPC gets (like Notch)", skin -> {
            String s = skin.trim();
            if (!s.matches("[A-Za-z0-9_]{3,16}")) {
                say(p, "&cThat isn't a Minecraft name.");
                return;
            }
            Location at = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
            at.setYaw(p.getLocation().getYaw() + 180);
            at.setPitch(0);
            commandPicker(p, (cmd, console) -> {
                p.closeInventory();
                ask.ask(p, "the NPC's name (& colours work, or 'skip' to use " + s + ")", nm -> {
                    String name = nm.trim().equalsIgnoreCase("skip") ? "&f" + s : nm.trim();
                    Npc n = create(name, s, cmd, console, at);
                    say(p, "&aMade the NPC &f" + n.id + "&a: clicking it runs &f/" + commandFor(n, "{player}") + (console ? " &7(as the console)" : "") + "&a.");
                    say(p, "&7Change it: sneak + right-click it. Remove it: &f/npc delete " + n.id);
                }, null);
            }).open(p);
        }, null);
    }

    public Menu editor(Player p, Npc n) {
        Menu m = new Menu(3, "&8" + Util.small("NPC " + n.id));
        m.set(10, Util.item(Material.NAME_TAG, "&eName", "&7Now: " + n.name), e ->
                ask.ask(p, "the new name", t -> {
                    n.name = t.trim();
                    save();
                    spawn(n);
                }, () -> editor(p, n).open(p)));
        m.set(11, Util.item(Material.PLAYER_HEAD, "&eSkin", "&7Now: &f" + n.skin), e ->
                ask.ask(p, "the player whose skin it gets", t -> {
                    if (t.trim().matches("[A-Za-z0-9_]{3,16}")) {
                        n.skin = t.trim();
                        save();
                        spawn(n);
                    }
                }, () -> editor(p, n).open(p)));
        m.set(13, Util.item(Material.COMMAND_BLOCK, "&eCommand", "&7Now: &f/" + n.command + (n.console ? " &7(console)" : "")), e ->
                commandPicker(p, (cmd, console) -> {
                    n.command = cmd;
                    n.console = console;
                    save();
                    say(p, "&7The NPC now runs &f/" + cmd + "&7.");
                    editor(p, n).open(p);
                }).open(p));
        m.set(15, Util.item(Material.COMPASS, "&eMove it here", "&7To where you stand"), e -> {
            despawn(n);
            n.at = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
            n.at.setYaw(p.getLocation().getYaw() + 180);
            save();
            spawn(n);
            p.closeInventory();
        });
        m.set(16, Util.item(Material.BARRIER, "&cDelete", "&7Shift-click"), e -> {
            if (!e.isShiftClick()) return;
            delete(n.id);
            p.closeInventory();
            say(p, "&7NPC deleted.");
        });
        m.fill();
        return m;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        if (!s.hasPermission("darknpcs.admin")) return say(s, "&cOnly ops can make NPCs.");
        if (sub.equals("test")) {
            test(s);
            return true;
        }
        switch (sub) {
            case "", "create", "new", "add" -> {
                if (!(s instanceof Player p)) return say(s, "&7In game: /npc create");
                startCreate(p);
            }
            case "delete", "remove" -> {
                Npc n = a.length > 1 ? npcs.get(a[1].toLowerCase(Locale.ROOT)) : s instanceof Player p ? target(p) : null;
                if (n == null) return say(s, "&7Look at the NPC, or: /npc delete <id> &8(" + String.join(", ", npcs.keySet()) + ")");
                delete(n.id);
                say(s, "&7Deleted the NPC &f" + n.id + "&7.");
            }
            case "edit" -> {
                if (!(s instanceof Player p)) return true;
                Npc n = a.length > 1 ? npcs.get(a[1].toLowerCase(Locale.ROOT)) : target(p);
                if (n == null) return say(p, "&7Look at the NPC, or: /npc edit <id>");
                editor(p, n).open(p);
            }
            case "list" -> {
                if (npcs.isEmpty()) return say(s, "&7No NPCs yet: /npc create");
                for (Npc n : npcs.values())
                    say(s, "&f" + n.id + " &7" + n.name + " &7→ &f/" + n.command + (n.console ? " &8(console)" : "")
                            + " &8" + (n.at == null || n.at.getWorld() == null ? "?" : n.at.getWorld().getName() + " " + n.at.getBlockX() + " " + n.at.getBlockY() + " " + n.at.getBlockZ()));
            }
            case "reload" -> {
                reloadConfig();
                for (Npc n : npcs.values()) despawn(n);
                load();
                spawnAll();
                say(s, "&aReloaded.");
            }
            default -> say(s, "&f/npc create &7(type a skin, pick a command)  &f/npc delete [id]  &f/npc edit [id]  &f/npc list");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("darknpcs.admin")) return out;
        if (a.length == 1) out.addAll(List.of("create", "delete", "edit", "list", "reload"));
        else if (a.length == 2 && (a[0].equalsIgnoreCase("delete") || a[0].equalsIgnoreCase("edit"))) out.addAll(npcs.keySet());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        npcs.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = y.getConfigurationSection("npcs");
        if (cs == null) return;
        for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null) continue;
            Npc n = new Npc(id);
            n.name = c.getString("name", id);
            n.skin = c.getString("skin", "Steve");
            n.command = c.getString("command", "help");
            n.console = c.getBoolean("console", false);
            World w = Bukkit.getWorld(c.getString("world", ""));
            if (w != null) n.at = new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), 0);
            npcs.put(id, n);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Npc n : npcs.values()) {
            String k = "npcs." + n.id + ".";
            y.set(k + "name", n.name);
            y.set(k + "skin", n.skin);
            y.set(k + "command", n.command);
            y.set(k + "console", n.console);
            if (n.at != null && n.at.getWorld() != null) {
                y.set(k + "world", n.at.getWorld().getName());
                y.set(k + "x", n.at.getX());
                y.set(k + "y", n.at.getY());
                y.set(k + "z", n.at.getZ());
                y.set(k + "yaw", n.at.getYaw());
            }
        }
        try {
            getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            getLogger().warning("Couldn't save npcs.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ test

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        Npc n = null;
        try {
            World w = Bukkit.getWorlds().get(0);
            Location at = w.getSpawnLocation().clone().add(2.5, 1, 2.5);
            n = create("&bTest RTP", "Notch", "/rtp", false, at);
            if (!"rtp".equals(commandFor(n, "Geo")) || !"give Geo diamond".equals(commandFor(fake("give {player} diamond"), "Geo"))) fails.add("command");
            List<Entity> mine = new ArrayList<>();
            for (Entity e : w.getNearbyEntities(at, 3, 4, 3)) if (e.getScoreboardTags().contains(tagOf(n))) mine.add(e);
            long texts = mine.stream().filter(e -> e instanceof TextDisplay).count();
            Entity body = mine.stream().filter(e -> !(e instanceof TextDisplay)).findFirst().orElse(null);
            if (body == null || texts != 2) fails.add("spawned " + mine.size() + " (" + texts + " lines)");
            else {
                if (npcOf(body) != n) fails.add("click finds the NPC");
                String want = mannequins() ? "MANNEQUIN" : "ARMOR_STAND";
                if (!body.getType().name().equals(want)) fails.add("body " + body.getType() + " not " + want);
                boolean click = mine.stream().anyMatch(e -> e instanceof TextDisplay t && t.getText().contains("CLICK ME"));
                if (!click) fails.add("no CLICK ME");
            }
            String id = n.id;
            npcs.clear();
            load();
            Npc back = npcs.get(id);
            if (back == null || !back.command.equals("/rtp") || !back.skin.equals("Notch") || back.at == null) fails.add("not saved");
            if (!delete(id)) fails.add("delete");
            long left = w.getNearbyEntities(at, 3, 4, 3).stream().filter(e -> e.getScoreboardTags().contains(TAG + ":" + id)).count();
            if (left > 0) fails.add(left + " entities left after delete");
            n = null;
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            if (n != null) delete(n.id);
        }
        String r = fails.isEmpty() ? "DARKNPCS TEST OK (" + (mannequins() ? "mannequins with skins" : "armor stands with heads") + ")" : "DARKNPCS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    private static Npc fake(String cmd) {
        Npc n = new Npc("x");
        n.command = cmd;
        return n;
    }
}
