package dev.darkerdawnfr.darkshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Money under name tags, /rules and /stats. */
public class Extras {
    private final DarkShop plugin;

    public Extras(DarkShop plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::moneyTags, 40, 40);
    }

    // ------------------------------------------------------------------ money under name tags

    /** Everyone's balance under their name, e.g. "$1.5K" (Paper shows the short form, Spigot shows the number). */
    public void moneyTags() {
        boolean on = plugin.getConfig().getBoolean("money-tags.enabled", true);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = viewer.getScoreboard();
            Objective o = sb.getObjective("darkmoney");
            if (!on) {
                if (o != null) o.unregister();
                continue;
            }
            if (o == null) {
                o = sb.registerNewObjective("darkmoney", Criteria.DUMMY, Util.color(plugin.getConfig().getString("money-tags.label", "&a$")));
                o.setDisplaySlot(DisplaySlot.BELOW_NAME);
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                double bal = plugin.money().balance(p.getUniqueId());
                Score s = o.getScore(p.getName());
                s.setScore((int) Math.min(Integer.MAX_VALUE, Math.max(0, bal)));
                try {
                    PaperFormat.set(s, Util.color(plugin.getConfig().getString("money-tags.format", "&a{money}").replace("{money}", Util.money(bal))));
                    if (!" ".equals(o.getDisplayName())) o.setDisplayName(" "); // the formatted money already has the $
                } catch (Throwable ignored) {
                    // Spigot: the plain number shows, followed by the label ("1500 $")
                }
            }
        }
    }

    private static final class PaperFormat {
        static void set(Score s, String text) {
            s.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.fixed(
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(text)));
        }
    }

    // ------------------------------------------------------------------ /rules

    public Menu rules() {
        List<String> rules = plugin.getConfig().getStringList("rules");
        int rows = Math.min(6, Math.max(3, (rules.size() + 6) / 7 + 2));
        Menu m = new Menu(rows, "&8" + Util.small("Rules"));
        int slot = 10;
        for (int i = 0; i < rules.size(); i++) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++;
            if (slot >= rows * 9 - 9) break;
            m.set(slot++, Util.item(Material.BOOK, Math.min(64, i + 1), "&b&lRule " + (i + 1), List.of("&f" + rules.get(i))));
        }
        m.border();
        return m;
    }

    // ------------------------------------------------------------------ /stats

    private static int stat(OfflinePlayer p, Statistic s) {
        try {
            return p.getStatistic(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Object call(String plugin, String method, Object... args) {
        Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        if (p == null || !p.isEnabled()) return null;
        for (var m : p.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            try {
                return m.invoke(p, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String time(long seconds) {
        long m = seconds / 60, h = m / 60, d = h / 24;
        return d > 0 ? d + "d " + h % 24 + "h" : h > 0 ? h + "h " + m % 60 + "m" : m + "m";
    }

    /** Everything about a player: money, kills, deaths, playtime, shards, bounty... */
    public List<String[]> stats(OfflinePlayer p) {
        UUID id = p.getUniqueId();
        int kills = stat(p, Statistic.PLAYER_KILLS), deaths = stat(p, Statistic.DEATHS);
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"SUNFLOWER", "&aMoney", Util.money(plugin.money().balance(id))});
        Object rank = call("DarkShop", "baltopRank", id);
        out.add(new String[]{"GOLD_INGOT", "&6Baltop", rank instanceof Number n && n.intValue() > 0 ? "#" + n : "-"});
        out.add(new String[]{"DIAMOND_SWORD", "&cKills", String.valueOf(kills)});
        out.add(new String[]{"SKELETON_SKULL", "&7Deaths", String.valueOf(deaths)});
        out.add(new String[]{"TARGET", "&eK/D", String.format(java.util.Locale.ROOT, "%.2f", deaths == 0 ? kills : (double) kills / deaths)});
        out.add(new String[]{"ZOMBIE_HEAD", "&2Mobs killed", String.valueOf(stat(p, Statistic.MOB_KILLS))});
        out.add(new String[]{"CLOCK", "&bPlaytime", time(stat(p, Statistic.PLAY_ONE_MINUTE) / 20L)});
        out.add(new String[]{"LEATHER_BOOTS", "&fWalked", (stat(p, Statistic.WALK_ONE_CM) + stat(p, Statistic.SPRINT_ONE_CM)) / 100000 + " km"});
        out.add(new String[]{"NETHERITE_SWORD", "&cBounty", Util.money(plugin.bounty(id))});
        Object shards = call("DarkShards", "shards", id);
        if (shards != null) out.add(new String[]{"AMETHYST_SHARD", "&dShards", "✦ " + shards});
        if (plugin.playtime().enabled() && p.isOnline())
            out.add(new String[]{"EMERALD", "&aPlaytime money", Util.money(plugin.playtime().amount(stat(p, Statistic.PLAY_ONE_MINUTE) / 72000.0))
                    + " in " + plugin.playtime().left(id) + "m"});
        Object rankName = call("DarkRanks", "rankName", id);
        if (rankName != null) out.add(new String[]{"NAME_TAG", "&6Rank", String.valueOf(rankName)});
        long first = p.getFirstPlayed();
        if (first > 0) out.add(new String[]{"CAKE", "&fFirst joined", new java.text.SimpleDateFormat("dd/MM/yyyy").format(new java.util.Date(first))});
        return out;
    }

    public Menu statsMenu(OfflinePlayer p) {
        Menu m = new Menu(5, "&8" + Util.small("Stats - " + p.getName()));
        m.set(4, Util.head(p, "&f&l" + p.getName(), List.of(p.isOnline() ? "&aOnline" : "&7Offline")));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        List<String[]> st = stats(p);
        for (int i = 0; i < st.size() && i < slots.length; i++) {
            Material icon = Util.material(st.get(i)[0]);
            m.set(slots[i], Util.item(icon == null ? Material.PAPER : icon, 1, st.get(i)[1], List.of("&f" + st.get(i)[2])));
        }
        m.fill();
        return m;
    }
}
