package dev.darkerdawnfr.darkannounce;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Dark Announce by DarkerDawnFR.
 * /announce <message>: a big announcement (title on everyone's screen, chat box, sound).
 * /donor <player> <what they bought>: a thank-you announcement with fireworks. Stores can run it from the console.
 */
public class DarkAnnounce extends JavaPlugin implements TabCompleter, org.bukkit.event.Listener {
    private int shown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("announce", "donor", "darkannounce")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
    }

    private static String fill(String s, String player, String text) {
        return Util.color(s.replace("{player}", player).replace("{message}", text).replace("{package}", text));
    }

    /** Sends an announcement made of a template section (announce / donor). Returns how many players saw it. */
    public int announce(String section, String player, String text) {
        List<String> lines = getConfig().getStringList(section + ".chat");
        String title = getConfig().getString(section + ".title", ""), sub = getConfig().getString(section + ".subtitle", "");
        Sound sound = null;
        try {
            sound = Sound.valueOf(getConfig().getString(section + ".sound", "UI_TOAST_CHALLENGE_COMPLETE").toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
        }
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (String l : lines) p.sendMessage(fill(l, player, text));
            if (!title.isEmpty() || !sub.isEmpty()) p.sendTitle(fill(title, player, text), fill(sub, player, text), 10, 80, 20);
            if (sound != null) p.playSound(p.getLocation(), sound, 0.8f, 1f);
            n++;
        }
        for (String l : lines) Bukkit.getConsoleSender().sendMessage(fill(l, player, text));
        shown++;
        return n;
    }

    public void fireworks(Player p) {
        if (!getConfig().getBoolean("donor.fireworks", true)) return;
        for (int i = 0; i < 3; i++) {
            int delay = i * 10;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline()) return;
                Firework fw = p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class);
                FireworkMeta m = fw.getFireworkMeta();
                m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.FUCHSIA, Color.AQUA, Color.YELLOW)
                        .withFade(Color.WHITE).flicker(true).trail(true).build());
                m.setPower(1);
                fw.setFireworkMeta(m);
                fw.setMetadata("darkannounce", new org.bukkit.metadata.FixedMetadataValue(this, true));
            }, delay);
        }
    }

    /** Our celebration fireworks never hurt anyone. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void noHurt(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework f && f.hasMetadata("darkannounce")) e.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "announce" -> {
                if (a.length == 0) {
                    s.sendMessage(Util.color("&7Usage: /announce <message>"));
                    return true;
                }
                announce("announce", s.getName(), String.join(" ", a));
            }
            case "donor" -> {
                if (a.length < 2) {
                    s.sendMessage(Util.color("&7Usage: /donor <player> <what they bought>  &8(e.g. /donor Geo VIP Rank)"));
                    return true;
                }
                String what = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                announce("donor", a[0], what);
                Player p = Bukkit.getPlayerExact(a[0]);
                if (p != null) fireworks(p);
            }
            case "darkannounce" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("test")) test(s);
                else if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    s.sendMessage(Util.color("&aReloaded."));
                } else s.sendMessage(Util.color("&7Dark Announce by DarkerDawnFR. /announce <message>, /donor <player> <package>"));
            }
            default -> { return false; }
        }
        return true;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        try {
            int before = shown;
            announce("announce", "Console", "Server restart in 5 minutes");
            announce("donor", "Geo", "VIP Rank");
            if (shown != before + 2) fails.add("not announced");
            if (getConfig().getStringList("donor.chat").stream().noneMatch(l -> l.contains("{player}"))) fails.add("donor template");
            if (!fill("{player} bought {package}", "Geo", "VIP").equals("Geo bought VIP")) fails.add("placeholders");
        } catch (Throwable t) {
            fails.add(t.toString());
        }
        String r = fails.isEmpty() ? "DARKANNOUNCE TEST OK" : "DARKANNOUNCE TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("donor") && a.length == 1) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }
}
