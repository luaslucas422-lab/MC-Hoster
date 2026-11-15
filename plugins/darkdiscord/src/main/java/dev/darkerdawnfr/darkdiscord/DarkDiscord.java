package dev.darkerdawnfr.darkdiscord;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dark Discord by DarkerDawnFR.
 * /discord sends the Discord link to everyone, /dc only to you (on screen for 5 seconds).
 * In GUI mode both open a chest with a purple dye "Join The Discord!".
 * /dcedit (ops) switches Text/GUI and changes the link.
 */
public class DarkDiscord extends JavaPlugin implements Listener, TabCompleter {
    private final Set<UUID> typingLink = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastBroadcast = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("discord", "dc", "dcedit")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        getLogger().info("Dark Discord ready. Mode: " + mode() + ", link: " + link());
    }

    public String link() {
        return getConfig().getString("link", "https://discord.gg/");
    }

    public String mode() {
        return "gui".equalsIgnoreCase(getConfig().getString("mode", "text")) ? "gui" : "text";
    }

    private String prefix() {
        return Util.color(getConfig().getString("prefix", "&8[&5Discord&8] "));
    }

    // ------------------------------------------------------------------ what players see

    /** The chest: "Discord" at the top, a purple dye in the middle. */
    public Menu gui() {
        Menu m = new Menu(3, getConfig().getString("gui-title", "&5Discord"));
        ItemStack dye = Util.item(Material.PURPLE_DYE, getConfig().getString("gui-item-name", "&d&lJoin The Discord!"), "&f" + link());
        m.set(13, dye, e -> {
            Player p = (Player) e.getWhoClicked();
            p.closeInventory();
            Chat.link(p, prefix() + Util.color(getConfig().getString("click-message", "&dClick to join: &f")), link());
        });
        return m;
    }

    /** /discord in text mode: a clickable link for everyone. */
    public void broadcast(String from) {
        String text = prefix() + Util.color(getConfig().getString("broadcast-message", "&fJoin our Discord: &d"));
        for (Player p : Bukkit.getOnlinePlayers()) Chat.link(p, text, link());
        getLogger().info((from == null ? "" : from + " sent the Discord link: ") + link());
    }

    /** /dc in text mode: only for you, on screen for 5 seconds. */
    public void showOnScreen(Player p) {
        int seconds = Math.max(1, getConfig().getInt("dc-seconds", 5));
        p.sendTitle(Util.color(getConfig().getString("dc-title", "&5&lDISCORD")), Util.color("&f" + link()), 10, seconds * 20, 10);
    }

    // ------------------------------------------------------------------ /dcedit

    public Menu editor() {
        boolean gui = mode().equals("gui");
        Menu m = new Menu(3, "&8" + Util.small("Discord settings"));
        m.set(11, Util.item(Material.PAPER, (gui ? "&7" : "&a» ") + "Text mode",
                "&7/discord sends the link to everyone,", "&7/dc shows it only to you for 5 seconds.", "", gui ? "&eClick to use" : "&aIn use"),
                e -> {
                    setMode("text");
                    editor().open((Player) e.getWhoClicked());
                });
        m.set(13, Util.item(Material.NAME_TAG, "&fChange link", "&7Now: &f" + link(), "", "&eClick, then type the new link in chat"),
                e -> {
                    Player p = (Player) e.getWhoClicked();
                    p.closeInventory();
                    typingLink.add(p.getUniqueId());
                    p.sendMessage(prefix() + Util.color("&7Type your Discord invite link in chat (or &fcancel&7)."));
                });
        m.set(15, Util.item(Material.CHEST, (gui ? "&a» " : "&7") + "GUI mode",
                "&7/discord and /dc open a chest", "&7with a purple dye to click.", "", gui ? "&aIn use" : "&eClick to use"),
                e -> {
                    setMode("gui");
                    editor().open((Player) e.getWhoClicked());
                });
        m.set(22, Util.item(Material.PURPLE_DYE, "&fPreview", "&7See what players see"),
                e -> gui().open((Player) e.getWhoClicked()));
        m.fill();
        return m;
    }

    public void setMode(String mode) {
        getConfig().set("mode", mode);
        saveConfig();
    }

    /** False if it doesn't look like a link. */
    public boolean setLink(String link) {
        link = link.trim();
        if (!link.matches("(?i)https?://\\S+")) {
            if (link.matches("(?i)(discord\\.gg|discord\\.com)/\\S+")) link = "https://" + link;
            else return false;
        }
        getConfig().set("link", link);
        saveConfig();
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncPlayerChatEvent e) {
        if (!typingLink.remove(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        String text = ChatColor.stripColor(e.getMessage()).trim();
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = e.getPlayer();
            if (text.equalsIgnoreCase("cancel")) {
                p.sendMessage(prefix() + Util.color("&7Link not changed."));
            } else if (setLink(text)) {
                p.sendMessage(prefix() + Util.color("&aDiscord link is now &f" + link()));
            } else {
                p.sendMessage(prefix() + Util.color("&c" + text + " is not a link. It should look like https://discord.gg/abc123"));
            }
            if (p.isOnline()) editor().open(p);
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        typingLink.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "discord" -> {
                if (mode().equals("gui")) {
                    if (s instanceof Player p) gui().open(p);
                    else s.sendMessage(prefix() + link());
                    return true;
                }
                if (s instanceof Player p && !p.hasPermission("darkdiscord.edit")) {
                    long cd = Math.max(0, getConfig().getLong("broadcast-cooldown-seconds", 30)) * 1000;
                    long last = lastBroadcast.getOrDefault(p.getUniqueId(), 0L);
                    long left = last + cd - System.currentTimeMillis();
                    if (left > 0) {
                        // still show it to them, just don't spam everyone
                        Chat.link(p, prefix() + Util.color(getConfig().getString("broadcast-message", "&fJoin our Discord: &d")), link());
                        return true;
                    }
                    lastBroadcast.put(p.getUniqueId(), System.currentTimeMillis());
                }
                broadcast(s.getName());
            }
            case "dc" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(prefix() + link());
                    return true;
                }
                if (mode().equals("gui")) gui().open(p);
                else showOnScreen(p);
            }
            case "dcedit" -> {
                String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
                switch (sub) {
                    case "text", "gui" -> {
                        setMode(sub);
                        s.sendMessage(prefix() + Util.color("&aMode is now &f" + (sub.equals("gui") ? "GUI" : "Text")));
                    }
                    case "link" -> {
                        if (a.length < 2) s.sendMessage(prefix() + Util.color("&7Now: &f" + link() + " &7- change with /dcedit link <link>"));
                        else if (setLink(a[1])) s.sendMessage(prefix() + Util.color("&aDiscord link is now &f" + link()));
                        else s.sendMessage(prefix() + Util.color("&c" + a[1] + " is not a link, e.g. https://discord.gg/abc123"));
                    }
                    case "reload" -> {
                        reloadConfig();
                        s.sendMessage(prefix() + Util.color("&aReloaded. Mode: " + mode() + ", link: " + link()));
                    }
                    case "test" -> test(s);
                    default -> {
                        if (s instanceof Player p) editor().open(p);
                        else s.sendMessage(prefix() + "Mode: " + mode() + ", link: " + link()
                                + ". Use /dcedit text|gui|link <link>|reload");
                    }
                }
            }
            default -> { return false; }
        }
        return true;
    }

    /** Used by MC Hoster's server test. */
    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        String oldMode = mode(), oldLink = link();
        try {
            setMode("gui");
            if (!mode().equals("gui")) fails.add("could not switch to GUI");
            setMode("text");
            if (!mode().equals("text")) fails.add("could not switch to Text");
            if (setLink("not a link")) fails.add("accepted a bad link");
            if (!setLink("discord.gg/test123") || !link().equals("https://discord.gg/test123")) fails.add("link not saved: " + link());
            ItemStack dye = gui().getInventory().getItem(13);
            if (dye == null || dye.getType() != Material.PURPLE_DYE) fails.add("no purple dye in the middle");
            else {
                String n = ChatColor.stripColor(dye.getItemMeta().getDisplayName());
                List<String> lore = dye.getItemMeta().getLore();
                if (!n.equals("Join The Discord!")) fails.add("dye name is " + n);
                if (lore == null || lore.isEmpty() || !ChatColor.stripColor(lore.get(0)).equals("https://discord.gg/test123")) fails.add("dye lore " + lore);
            }
            editor();
            broadcast(null);
            Chat.link(Bukkit.getConsoleSender(), "test ", link()); // console falls back to plain text
            reloadConfig();
            if (!link().equals("https://discord.gg/test123")) fails.add("link lost after reload");
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            setMode(oldMode);
            getConfig().set("link", oldLink);
            saveConfig();
        }
        String result = fails.isEmpty() ? "DARKDISCORD TEST OK" : "DARKDISCORD TEST FAIL: " + fails;
        getLogger().info(result);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(result);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("dcedit") && a.length == 1) out.addAll(List.of("text", "gui", "link", "reload"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}
