package dev.darkerdawnfr.darkvcaddon;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dark VC Add-on by DarkerDawnFR, for Simple Voice Chat (still needed).
 * Nobody can talk or listen in voice chat until they type /voicechat agree and accept the rules in a pop-up,
 * like DonutSMP. After "Continue" they have voice chat for good (ops can take it away with /vc revoke).
 */
public class DarkVCAddon extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private YamlConfiguration data;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "agreed.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("vc");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
        if (!voiceChatInstalled()) getLogger().warning("Simple Voice Chat isn't installed: Dark VC Add-on needs it (tick Simple Voice Chat in MC Hoster).");
    }

    @Override
    public void onDisable() {
        attachments.forEach((id, a) -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.removeAttachment(a);
        });
        attachments.clear();
    }

    public static boolean voiceChatInstalled() {
        return Bukkit.getPluginManager().getPlugin("voicechat") != null;
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&dVoice Chat&8] "));
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(prefix() + Util.color(t));
        return true;
    }

    // ------------------------------------------------------------------ who may use voice chat

    public boolean agreed(UUID id) {
        return data.getBoolean(id.toString());
    }

    public void setAgreed(UUID id, boolean on) {
        data.set(id.toString(), on ? true : null);
        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save agreed.yml: " + e);
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) apply(p);
    }

    /** Simple Voice Chat's own permissions: off until you agree. */
    public void apply(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException ignored) {
            }
        }
        boolean on = agreed(p.getUniqueId()) || p.hasPermission("darkvc.bypass");
        PermissionAttachment a = p.addAttachment(this);
        for (String perm : getConfig().getStringList("voicechat-permissions")) a.setPermission(perm, on);
        attachments.put(p.getUniqueId(), a);
        p.recalculatePermissions();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        apply(p);
        if (!agreed(p.getUniqueId()) && getConfig().getBoolean("remind-on-join", true))
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && !agreed(p.getUniqueId())) say(p, getConfig().getString("reminder", "&7Want voice chat? Type &f/voicechat agree&7."));
            }, 100);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        attachments.remove(e.getPlayer().getUniqueId());
    }

    /** "/voicechat agree" and "/voice chat agree" go to us (Simple Voice Chat owns /voicechat). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void command(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage().toLowerCase(Locale.ROOT).trim().replaceAll(" +", " ");
        if (m.equals("/voicechat agree") || m.equals("/voice chat agree") || m.equals("/voice agree") || m.equals("/vc agree")) {
            e.setCancelled(true);
            popup(e.getPlayer()).open(e.getPlayer());
        }
    }

    // ------------------------------------------------------------------ the pop-up

    public Menu popup(Player p) {
        Menu m = new Menu(3, getConfig().getString("popup.title", "&8Voice Chat Rules"));
        List<String> rules = new ArrayList<>();
        rules.add("&7Before you use voice chat:");
        rules.add("");
        int i = 1;
        for (String r : getConfig().getStringList("popup.rules")) rules.add("&f" + i++ + ". &7" + r);
        rules.add("");
        rules.add("&cBreaking these can get you muted or banned.");
        m.set(13, Util.item(Material.BOOK, 1, "&d&lVoice Chat Rules", rules));
        List<String> needs = new ArrayList<>(List.of("&7To hear and talk you need:", ""));
        for (String n : getConfig().getStringList("popup.you-need")) needs.add("&8• &f" + n);
        m.set(11, Util.item(Material.NOTE_BLOCK, 1, "&fWhat you need", needs));
        if (agreed(p.getUniqueId())) {
            m.set(15, Util.item(Material.LIME_DYE, "&aYou already have voice chat", "&7Press &fV &7in game to open it."));
        } else {
            m.set(15, Util.item(Material.LIME_CONCRETE, "&a&lContinue", "&7I read the rules and agree.", "", "&eClick to get voice chat"), e -> {
                setAgreed(p.getUniqueId(), true);
                p.closeInventory();
                p.sendTitle(Util.color("&d&lVOICE CHAT"), Util.color("&7You have access now"), 10, 50, 15);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
                say(p, getConfig().getString("popup.done", "&aYou have voice chat now! Press &fV &ato open it. Rejoin if you can't hear anyone yet."));
            });
            m.set(22, Util.item(Material.RED_CONCRETE, "&cNo thanks"), e -> p.closeInventory());
        }
        if (!voiceChatInstalled()) m.set(4, Util.item(Material.BARRIER, "&cSimple Voice Chat isn't on this server yet"));
        m.fill();
        return m;
    }

    // ------------------------------------------------------------------ /vc

    @SuppressWarnings("deprecation")
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "agree";
        boolean admin = s.hasPermission("darkvc.admin");
        switch (sub) {
            case "agree", "rules" -> {
                if (s instanceof Player p) popup(p).open(p);
            }
            case "revoke", "give" -> {
                if (!admin) return true;
                if (a.length < 2) return say(s, "&7Usage: /vc " + sub + " <player>");
                UUID id = Bukkit.getOfflinePlayer(a[1]).getUniqueId();
                setAgreed(id, sub.equals("give"));
                say(s, "&7" + a[1] + (sub.equals("give") ? " has voice chat now." : " lost voice chat (they can /voicechat agree again)."));
            }
            case "reload" -> {
                if (!admin) return true;
                reloadConfig();
                for (Player p : Bukkit.getOnlinePlayers()) apply(p);
                say(s, "&aReloaded.");
            }
            case "test" -> {
                if (admin) test(s);
            }
            default -> say(s, "&f/voicechat agree &7- get voice chat" + (admin ? " &8| /vc give|revoke <player>" : ""));
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        UUID id = UUID.randomUUID();
        try {
            if (agreed(id)) fails.add("agreed by default");
            setAgreed(id, true);
            data = YamlConfiguration.loadConfiguration(dataFile);
            if (!agreed(id)) fails.add("not saved");
            setAgreed(id, false);
            if (agreed(id)) fails.add("not revoked");
            if (getConfig().getStringList("popup.rules").size() < 3) fails.add("rules");
            if (!getConfig().getStringList("voicechat-permissions").contains("voicechat.speak")) fails.add("permissions");
            if (!voiceChatInstalled()) fails.add("Simple Voice Chat isn't installed");
        } catch (Throwable t) {
            fails.add(t.toString());
        }
        String r = fails.isEmpty() ? "DARKVCADDON TEST OK" : "DARKVCADDON TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("agree");
            if (s.hasPermission("darkvc.admin")) out.addAll(List.of("give", "revoke", "reload"));
        }
        return out;
    }
}
