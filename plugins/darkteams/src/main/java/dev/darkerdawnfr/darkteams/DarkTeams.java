package dev.darkerdawnfr.darkteams;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dark Teams by DarkerDawnFR: DonutSMP-style teams.
 * /team create, invite, join, leave, kick, disband, a team home, team chat, and no friendly fire.
 */
public class DarkTeams extends JavaPlugin implements Listener, TabCompleter {

    public static final class Team {
        String name;
        UUID owner;
        final Set<UUID> members = new LinkedHashSet<>(); // includes the owner
        Location home;
        boolean pvp;
        long created = System.currentTimeMillis();

        public String name() { return name; }
        public UUID owner() { return owner; }
        public Set<UUID> members() { return members; }
    }

    /** lower-case name -> team */
    private final Map<String, Team> teams = new HashMap<>();
    private final Map<UUID, Team> byPlayer = new ConcurrentHashMap<>();
    /** player -> lower-case team names that invited them */
    private final Map<UUID, Set<String>> invites = new HashMap<>();
    private final Set<UUID> chatOn = ConcurrentHashMap.newKeySet();
    private File file;
    private Tp tp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tp = new Tp(this);
        file = new File(getDataFolder(), "teams.yml");
        load();
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("team", "teamchat", "darkteams")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&9Team&8] "));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(prefix() + Util.color(text));
        return true;
    }

    private void tell(Team t, String text) {
        for (UUID id : t.members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) say(p, text);
        }
    }

    private static String name(UUID id) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(id);
        return o.getName() != null ? o.getName() : id.toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ API (Dark Scoreboard and others use these by reflection)

    public Team teamOf(UUID id) {
        return byPlayer.get(id);
    }

    /** The team name of a player, "" if none. */
    public String teamName(UUID id) {
        Team t = byPlayer.get(id);
        return t == null ? "" : t.name;
    }

    public int teamOnline(UUID id) {
        Team t = byPlayer.get(id);
        if (t == null) return 0;
        int n = 0;
        for (UUID m : t.members) if (Bukkit.getPlayer(m) != null) n++;
        return n;
    }

    public boolean sameTeam(UUID a, UUID b) {
        Team t = byPlayer.get(a);
        return t != null && t == byPlayer.get(b);
    }

    public int maxMembers() {
        return Math.max(2, getConfig().getInt("max-members", 10));
    }

    // ------------------------------------------------------------------ actions

    /** null if fine, otherwise why the name can't be used. */
    public String badName(String n) {
        int min = getConfig().getInt("name-min", 3), max = getConfig().getInt("name-max", 16);
        if (n == null || n.length() < min || n.length() > max) return "Team names are " + min + "-" + max + " letters long.";
        if (!n.matches("[A-Za-z0-9_]+")) return "Only letters, numbers and _ in team names.";
        if (teams.containsKey(n.toLowerCase(Locale.ROOT))) return "That team name is taken.";
        for (String bad : getConfig().getStringList("blocked-names"))
            if (n.toLowerCase(Locale.ROOT).contains(bad.toLowerCase(Locale.ROOT))) return "You can't use that team name.";
        return null;
    }

    public Team create(UUID owner, String n) {
        Team t = new Team();
        t.name = n;
        t.owner = owner;
        t.members.add(owner);
        t.pvp = getConfig().getBoolean("friendly-fire-default", false);
        teams.put(n.toLowerCase(Locale.ROOT), t);
        byPlayer.put(owner, t);
        save();
        return t;
    }

    public void add(Team t, UUID id) {
        t.members.add(id);
        byPlayer.put(id, t);
        invites.remove(id);
        save();
    }

    public void remove(Team t, UUID id) {
        t.members.remove(id);
        byPlayer.remove(id, t);
        chatOn.remove(id);
        if (t.members.isEmpty()) teams.remove(t.name.toLowerCase(Locale.ROOT));
        else if (t.owner.equals(id)) t.owner = t.members.iterator().next(); // the next oldest member takes over
        save();
    }

    public void disband(Team t) {
        for (UUID id : t.members) {
            byPlayer.remove(id, t);
            chatOn.remove(id);
        }
        teams.remove(t.name.toLowerCase(Locale.ROOT));
        for (Set<String> s : invites.values()) s.remove(t.name.toLowerCase(Locale.ROOT));
        save();
    }

    // ------------------------------------------------------------------ menu

    public Menu menu(Player p) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) {
            Menu m = new Menu(3, "&8" + Util.small("Teams"));
            m.fill();
            m.set(11, Util.item(Material.WHITE_BANNER, "&9Make a team", "&7/team create <name>", "", "&7Up to &f" + maxMembers() + " &7players,",
                    "&7a team home, team chat", "&7and no friendly fire."));
            Set<String> inv = invites.getOrDefault(p.getUniqueId(), Set.of());
            List<String> lore = new ArrayList<>();
            if (inv.isEmpty()) lore.add("&7Nobody invited you yet.");
            else for (String n : inv) if (teams.containsKey(n)) lore.add("&f" + teams.get(n).name + " &8- &e/team join " + teams.get(n).name);
            m.set(13, Util.item(Material.PAPER, "&9Invites", lore.toArray(new String[0])));
            m.set(15, Util.item(Material.GOLDEN_HELMET, "&9Top teams", "&7The biggest teams", "", "&eClick to see"), e -> top(p).open(p));
            return m;
        }
        boolean owner = t.owner.equals(p.getUniqueId());
        Menu m = new Menu(5, "&8" + Util.small("Team") + " " + t.name);
        m.fill();
        int slot = 10;
        for (UUID id : t.members) {
            if (slot == 17) slot = 19;
            if (slot >= 26) break;
            OfflinePlayer o = Bukkit.getOfflinePlayer(id);
            boolean on = o.isOnline();
            List<String> lore = new ArrayList<>();
            lore.add(id.equals(t.owner) ? "&6Owner" : "&7Member");
            lore.add(on ? "&aOnline" : "&8Offline");
            if (owner && !id.equals(p.getUniqueId())) {
                lore.add("");
                lore.add("&cClick to kick");
            }
            m.set(slot++, Util.head(o, (on ? "&a" : "&7") + name(id), lore), e -> {
                if (owner && !id.equals(p.getUniqueId())) {
                    p.closeInventory();
                    Bukkit.dispatchCommand(p, "team kick " + name(id));
                }
            });
        }
        m.set(37, Util.item(t.home != null ? Material.RED_BED : Material.GRAY_BED, "&9Team home",
                t.home != null ? "&7Click to teleport" : "&7Not set yet", owner ? "&7/team sethome to set it" : ""), e -> {
            p.closeInventory();
            Bukkit.dispatchCommand(p, "team home");
        });
        m.set(39, Util.item(chatOn.contains(p.getUniqueId()) ? Material.LIME_DYE : Material.GRAY_DYE, "&9Team chat: "
                + (chatOn.contains(p.getUniqueId()) ? "&aON" : "&7OFF"), "&7Your chat goes only to your team.", "&7Or: /tc <message>", "", "&eClick to switch"), e -> {
            toggleChat(p);
            menu(p).open(p);
        });
        m.set(41, Util.item(t.pvp ? Material.IRON_SWORD : Material.SHIELD, "&9Friendly fire: " + (t.pvp ? "&cON" : "&aOFF"),
                t.pvp ? "&7Team mates can hurt each other." : "&7Team mates can't hurt each other.", owner ? "&eClick to switch" : "&7Only the owner can change it"), e -> {
            if (!owner) return;
            t.pvp = !t.pvp;
            save();
            tell(t, "&7Friendly fire is now " + (t.pvp ? "&cON" : "&aOFF") + "&7.");
            menu(p).open(p);
        });
        m.set(43, Util.item(Material.BARRIER, owner ? "&cDisband team" : "&cLeave team", "&7Click, then confirm"), e -> confirmLeave(p, t, owner).open(p));
        m.set(4, Util.item(Material.BLUE_BANNER, "&9&l" + t.name, "&7" + t.members.size() + "/" + maxMembers() + " players, " + teamOnline(p.getUniqueId()) + " online",
                "&7Owner: &f" + name(t.owner), "", "&8/team invite <player>"));
        return m;
    }

    private Menu confirmLeave(Player p, Team t, boolean owner) {
        Menu m = new Menu(3, "&8" + (owner ? "Disband " : "Leave ") + t.name + "?");
        m.fill();
        m.set(11, Util.item(Material.LIME_STAINED_GLASS_PANE, "&aYes", owner ? "&7The team is gone for good" : "&7Leave the team"), e -> {
            p.closeInventory();
            Bukkit.dispatchCommand(p, owner ? "team disband confirm" : "team leave");
        });
        m.set(15, Util.item(Material.RED_STAINED_GLASS_PANE, "&cNo"), e -> menu(p).open(p));
        return m;
    }

    private Menu top(Player p) {
        Menu m = new Menu(4, "&8" + Util.small("Top teams"));
        m.fill();
        List<Team> list = new ArrayList<>(teams.values());
        list.sort((a, b) -> b.members.size() != a.members.size() ? b.members.size() - a.members.size() : Long.compare(a.created, b.created));
        int slot = 10;
        for (int i = 0; i < Math.min(14, list.size()); i++) {
            if (slot == 17) slot = 19;
            Team t = list.get(i);
            int online = 0;
            for (UUID id : t.members) if (Bukkit.getPlayer(id) != null) online++;
            m.set(slot++, Util.item(i == 0 ? Material.GOLD_BLOCK : i == 1 ? Material.IRON_BLOCK : i == 2 ? Material.COPPER_BLOCK : Material.BLUE_BANNER,
                    "&9#" + (i + 1) + " &f" + t.name, "&7" + t.members.size() + " players, " + online + " online", "&7Owner: &f" + name(t.owner)));
        }
        if (list.isEmpty()) m.set(13, Util.item(Material.PAPER, "&7No teams yet", "&7/team create <name>"));
        m.set(31, Util.item(Material.ARROW, "&7Back"), e -> menu(p).open(p));
        return m;
    }

    private void toggleChat(Player p) {
        if (!chatOn.remove(p.getUniqueId())) chatOn.add(p.getUniqueId());
        say(p, chatOn.contains(p.getUniqueId()) ? "&aTeam chat ON: &7your messages go to your team." : "&7Team chat OFF.");
    }

    private boolean muted(Player p) {
        org.bukkit.plugin.Plugin c = Bukkit.getPluginManager().getPlugin("DarkChat");
        if (c == null || !c.isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(c.getClass().getMethod("isMuted", UUID.class).invoke(c, p.getUniqueId()));
        } catch (Exception e) {
            return false;
        }
    }

    private void teamChat(Player p, Team t, String text) {
        if (muted(p)) {
            say(p, "&cYou are muted.");
            return;
        }
        String f = Util.color(getConfig().getString("chat-format", "&9[Team] &f{player}&8: &b{message}")
                .replace("{team}", t.name).replace("{player}", p.getName())).replace("{message}", text);
        for (UUID id : t.members) {
            Player m = Bukkit.getPlayer(id);
            if (m != null) m.sendMessage(f);
        }
        if (getConfig().getBoolean("log-team-chat", true)) getLogger().info("[" + t.name + "] " + p.getName() + ": " + text);
    }

    // ------------------------------------------------------------------ events

    private static Player attacker(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Player a = attacker(e.getDamager());
        if (a == null || a == v) return;
        Team t = teamOf(a.getUniqueId());
        if (t != null && !t.pvp && t == teamOf(v.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void chat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!chatOn.contains(p.getUniqueId())) return;
        Team t = teamOf(p.getUniqueId());
        if (t == null) {
            chatOn.remove(p.getUniqueId());
            return;
        }
        String msg = e.getMessage();
        if (msg.startsWith("!")) { // "!hello" still goes to everyone
            e.setMessage(msg.substring(1));
            return;
        }
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> teamChat(p, t, msg));
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        tp.cancel(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        if (c.equals("darkteams")) {
            if (a.length > 0 && a[0].equalsIgnoreCase("test")) test(s);
            else if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                say(s, "&aReloaded.");
            } else say(s, "&7Dark Teams " + getDescription().getVersion() + " by DarkerDawnFR. /darkteams reload|test");
            return true;
        }
        if (!(s instanceof Player p)) return say(s, "&cOnly players can be in teams.");
        Team t = teamOf(p.getUniqueId());
        if (c.equals("teamchat")) {
            if (t == null) return say(p, "&cYou're not in a team.");
            if (a.length == 0) toggleChat(p);
            else teamChat(p, t, String.join(" ", a));
            return true;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        boolean owner = t != null && t.owner.equals(p.getUniqueId());
        switch (sub) {
            case "" , "menu" -> menu(p).open(p);
            case "create" -> {
                if (t != null) return say(p, "&cYou're already in a team. Leave it first.");
                if (a.length < 2) return say(p, "&7Usage: /team create <name>");
                String bad = badName(a[1]);
                if (bad != null) return say(p, "&c" + bad);
                create(p.getUniqueId(), a[1]);
                say(p, "&aTeam &f" + a[1] + " &amade! Invite friends: &f/team invite <player>");
            }
            case "invite" -> {
                if (t == null) return say(p, "&cMake a team first: /team create <name>");
                if (!owner && !getConfig().getBoolean("members-can-invite", true)) return say(p, "&cOnly the owner can invite.");
                if (a.length < 2) return say(p, "&7Usage: /team invite <player>");
                Player o = Bukkit.getPlayerExact(a[1]);
                if (o == null) return say(p, "&cThat player isn't online.");
                if (teamOf(o.getUniqueId()) != null) return say(p, "&c" + o.getName() + " is already in a team.");
                if (t.members.size() >= maxMembers()) return say(p, "&cYour team is full (" + maxMembers() + ").");
                invites.computeIfAbsent(o.getUniqueId(), k -> new HashSet<>()).add(t.name.toLowerCase(Locale.ROOT));
                say(p, "&7Invited &f" + o.getName() + "&7.");
                say(o, "&f" + p.getName() + " &7invited you to team &9" + t.name + "&7. Join with &e/team join " + t.name);
            }
            case "join", "accept" -> {
                if (t != null) return say(p, "&cYou're already in a team.");
                if (a.length < 2) return say(p, "&7Usage: /team join <team>");
                Team j = teams.get(a[1].toLowerCase(Locale.ROOT));
                if (j == null) return say(p, "&cNo team called " + a[1] + ".");
                if (!invites.getOrDefault(p.getUniqueId(), Set.of()).contains(a[1].toLowerCase(Locale.ROOT)))
                    return say(p, "&cThat team didn't invite you.");
                if (j.members.size() >= maxMembers()) return say(p, "&cThat team is full.");
                add(j, p.getUniqueId());
                tell(j, "&f" + p.getName() + " &ajoined the team!");
            }
            case "leave" -> {
                if (t == null) return say(p, "&cYou're not in a team.");
                if (owner && t.members.size() > 1 && !(a.length > 1 && a[1].equalsIgnoreCase("confirm")))
                    return say(p, "&7You own this team. Leaving gives it to the next member. &e/team leave confirm");
                remove(t, p.getUniqueId());
                say(p, "&7You left &9" + t.name + "&7.");
                tell(t, "&f" + p.getName() + " &7left the team." + (owner && !t.members.isEmpty() ? " &f" + name(t.owner) + " &7owns it now." : ""));
            }
            case "kick" -> {
                if (!owner) return say(p, "&cOnly the team owner can kick.");
                if (a.length < 2) return say(p, "&7Usage: /team kick <player>");
                UUID k = null;
                for (UUID id : t.members) if (name(id).equalsIgnoreCase(a[1])) k = id;
                if (k == null || k.equals(p.getUniqueId())) return say(p, "&cThat player isn't in your team.");
                remove(t, k);
                tell(t, "&f" + a[1] + " &7was kicked from the team.");
                Player kp = Bukkit.getPlayer(k);
                if (kp != null) say(kp, "&cYou were kicked from &9" + t.name + "&c.");
            }
            case "disband" -> {
                if (!owner) return say(p, "&cOnly the team owner can disband it.");
                if (!(a.length > 1 && a[1].equalsIgnoreCase("confirm"))) return say(p, "&7Sure? &c/team disband confirm");
                tell(t, "&cThe team &9" + t.name + " &cwas disbanded.");
                disband(t);
            }
            case "sethome" -> {
                if (t == null) return say(p, "&cYou're not in a team.");
                if (!owner && !getConfig().getBoolean("members-can-sethome", false)) return say(p, "&cOnly the owner can set the team home.");
                t.home = p.getLocation().clone();
                save();
                tell(t, "&f" + p.getName() + " &7set the team home.");
            }
            case "home" -> {
                if (t == null) return say(p, "&cYou're not in a team.");
                if (t.home == null || t.home.getWorld() == null) return say(p, "&cYour team has no home yet.");
                if (tp.busy(p.getUniqueId())) return say(p, "&cYou're already teleporting.");
                Team ft = t;
                tp.start(p, getConfig().getInt("countdown-seconds", 5), true, () -> ft.home, prefix(), () -> say(p, "&aWelcome to the team home."));
            }
            case "delhome" -> {
                if (!owner) return say(p, "&cOnly the owner can do that.");
                t.home = null;
                save();
                say(p, "&7Team home deleted.");
            }
            case "chat" -> {
                if (t == null) return say(p, "&cYou're not in a team.");
                toggleChat(p);
            }
            case "pvp", "ff" -> {
                if (!owner) return say(p, "&cOnly the owner can change friendly fire.");
                t.pvp = !t.pvp;
                save();
                tell(t, "&7Friendly fire is now " + (t.pvp ? "&cON" : "&aOFF") + "&7.");
            }
            case "transfer" -> {
                if (!owner) return say(p, "&cOnly the owner can do that.");
                if (a.length < 2) return say(p, "&7Usage: /team transfer <player>");
                UUID n = null;
                for (UUID id : t.members) if (name(id).equalsIgnoreCase(a[1])) n = id;
                if (n == null) return say(p, "&cThat player isn't in your team.");
                t.owner = n;
                save();
                tell(t, "&f" + name(n) + " &7owns the team now.");
            }
            case "info" -> {
                Team i = a.length > 1 ? teams.get(a[1].toLowerCase(Locale.ROOT)) : t;
                if (i == null && a.length > 1) {
                    Player o = Bukkit.getPlayerExact(a[1]);
                    if (o != null) i = teamOf(o.getUniqueId());
                }
                if (i == null) return say(p, a.length > 1 ? "&cNo team found." : "&cYou're not in a team.");
                List<String> names = new ArrayList<>();
                for (UUID id : i.members) names.add((Bukkit.getPlayer(id) != null ? "&a" : "&7") + name(id));
                say(p, "&9&l" + i.name + " &7(" + i.members.size() + "/" + maxMembers() + ") owner &f" + name(i.owner));
                say(p, String.join("&8, ", names));
            }
            case "top", "list" -> top(p).open(p);
            default -> {
                say(p, "&9/team &7menu  &9create &7<name>  &9invite &7<player>  &9join &7<team>  &9leave");
                say(p, "&9home  sethome  chat &7(or /tc <msg>)  &9kick  transfer  pvp  disband  info  top");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!cmd.getName().equalsIgnoreCase("team")) return out;
        if (a.length == 1) {
            for (String x : List.of("create", "invite", "join", "leave", "kick", "disband", "home", "sethome", "delhome", "chat", "pvp", "transfer", "info", "top"))
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length == 2 && s instanceof Player p) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            Team t = teamOf(p.getUniqueId());
            if (sub.equals("invite")) for (Player o : Bukkit.getOnlinePlayers()) { if (teamOf(o.getUniqueId()) == null) out.add(o.getName()); }
            else if ((sub.equals("kick") || sub.equals("transfer")) && t != null) { for (UUID id : t.members) if (!id.equals(p.getUniqueId())) out.add(name(id)); }
            else if (sub.equals("join")) { for (String n : invites.getOrDefault(p.getUniqueId(), Set.of())) if (teams.containsKey(n)) out.add(teams.get(n).name); }
            else if (sub.equals("info")) for (Team x : teams.values()) out.add(x.name);
            out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    // ------------------------------------------------------------------ test

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID a = UUID.nameUUIDFromBytes("darkteams-a".getBytes()), b = UUID.nameUUIDFromBytes("darkteams-b".getBytes()),
                c = UUID.nameUUIDFromBytes("darkteams-c".getBytes());
        String n = "TestTeamZ";
        try {
            Team old = teams.get(n.toLowerCase(Locale.ROOT));
            if (old != null) disband(old);
            if (badName("ab") == null || badName("bad name!") == null || badName(n) != null) fails.add("name checks");
            Team t = create(a, n);
            if (badName(n.toUpperCase(Locale.ROOT)) == null) fails.add("same name twice");
            add(t, b);
            if (!sameTeam(a, b) || sameTeam(a, c)) fails.add("sameTeam");
            if (!teamName(b).equals(n) || !teamName(c).isEmpty()) fails.add("teamName");
            World w = Bukkit.getWorlds().get(0);
            t.home = new Location(w, 5.5, 80, 6.5);
            save();
            teams.clear();
            byPlayer.clear();
            load();
            Team l = teamOf(b);
            if (l == null || !l.name.equals(n) || !l.owner.equals(a) || l.members.size() != 2 || l.home == null || l.home.getBlockY() != 80 || l.pvp)
                fails.add("not saved");
            if (l != null) {
                remove(l, a); // owner leaves: b takes over
                if (!l.owner.equals(b) || teamOf(a) != null) fails.add("owner leaving");
                disband(l);
                if (teamOf(b) != null || teams.containsKey(n.toLowerCase(Locale.ROOT))) fails.add("disband");
            }
            if (maxMembers() != 10) fails.add("max members " + maxMembers());
        } catch (Throwable t) {
            fails.add(t.toString());
        } finally {
            Team left = teams.get(n.toLowerCase(Locale.ROOT));
            if (left != null) disband(left);
        }
        String r = fails.isEmpty() ? "DARKTEAMS TEST OK" : "DARKTEAMS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection all = y.getConfigurationSection("teams");
        if (all == null) return;
        for (String k : all.getKeys(false)) {
            ConfigurationSection c = all.getConfigurationSection(k);
            if (c == null) continue;
            try {
                Team t = new Team();
                t.name = c.getString("name", k);
                t.owner = UUID.fromString(c.getString("owner", ""));
                for (String m : c.getStringList("members")) t.members.add(UUID.fromString(m));
                t.members.add(t.owner);
                t.pvp = c.getBoolean("pvp", false);
                t.created = c.getLong("created", System.currentTimeMillis());
                World w = Bukkit.getWorld(c.getString("home.world", ""));
                if (w != null) t.home = new Location(w, c.getDouble("home.x"), c.getDouble("home.y"), c.getDouble("home.z"),
                        (float) c.getDouble("home.yaw"), (float) c.getDouble("home.pitch"));
                teams.put(t.name.toLowerCase(Locale.ROOT), t);
                for (UUID m : t.members) byPlayer.put(m, t);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Team t : teams.values()) {
            String k = "teams." + t.name.toLowerCase(Locale.ROOT);
            y.set(k + ".name", t.name);
            y.set(k + ".owner", t.owner.toString());
            List<String> m = new ArrayList<>();
            for (UUID id : t.members) m.add(id.toString());
            y.set(k + ".members", m);
            y.set(k + ".pvp", t.pvp);
            y.set(k + ".created", t.created);
            if (t.home != null && t.home.getWorld() != null) {
                y.set(k + ".home.world", t.home.getWorld().getName());
                y.set(k + ".home.x", t.home.getX());
                y.set(k + ".home.y", t.home.getY());
                y.set(k + ".home.z", t.home.getZ());
                y.set(k + ".home.yaw", t.home.getYaw());
                y.set(k + ".home.pitch", t.home.getPitch());
            }
        }
        try {
            y.save(file);
        } catch (IOException e) {
            getLogger().warning("Couldn't save teams.yml: " + e.getMessage());
        }
    }
}
