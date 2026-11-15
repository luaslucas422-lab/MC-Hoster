package dev.darkerdawnfr.darkshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /baltop as a menu of player heads, richest first, with your own rank at the bottom. */
public class Baltop {
    public record Row(UUID id, String name, double balance) {}

    private final DarkShop plugin;
    private List<Row> cache = List.of();
    private long cachedAt;

    public Baltop(DarkShop plugin) {
        this.plugin = plugin;
    }

    /** Everyone's balance, richest first. Recomputed at most every 30 seconds. */
    public List<Row> rows() {
        if (System.currentTimeMillis() - cachedAt < 30_000 && !cache.isEmpty()) return cache;
        List<Row> out = new ArrayList<>();
        Money money = plugin.money();
        if (money.builtIn()) {
            out.addAll(money.builtInRows());
        } else {
            // another economy (EssentialsX...): ask it about everyone who has played here
            int n = 0;
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                if (p.getName() == null || n++ > 5000) continue;
                out.add(new Row(p.getUniqueId(), p.getName(), money.balance(p.getUniqueId())));
            }
        }
        out.sort((a, b) -> Double.compare(b.balance(), a.balance()));
        cache = out;
        cachedAt = System.currentTimeMillis();
        return out;
    }

    public void refresh() {
        cachedAt = 0;
    }

    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    public Menu menu(UUID viewer, int page) {
        List<Row> all = rows();
        int per = SLOTS.length, pages = Math.max(1, (all.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(6, "&8" + Util.small("Baltop") + " &8(" + (pg + 1) + "/" + pages + ")");
        m.border();
        for (int i = 0; i < per; i++) {
            int rank = pg * per + i;
            if (rank >= all.size()) break;
            Row r = all.get(rank);
            String color = rank == 0 ? "&6" : rank == 1 ? "&f" : rank == 2 ? "&c" : "&e";
            m.set(SLOTS[i], Util.head(Bukkit.getOfflinePlayer(r.id()), color + "#" + (rank + 1) + " &f" + r.name(),
                    List.of("&7Balance: &a" + Util.money(r.balance()))));
        }
        if (all.isEmpty()) m.set(22, Util.item(Material.PAPER, "&7Nobody has money yet"));
        if (pg > 0) m.set(45, Util.item(Material.ARROW, "&fPrevious page"), e -> menu(viewer, pg - 1).open((Player) e.getWhoClicked()));
        if (pg < pages - 1) m.set(53, Util.item(Material.ARROW, "&fNext page"), e -> menu(viewer, pg + 1).open((Player) e.getWhoClicked()));
        if (viewer != null) {
            int mine = -1;
            for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(viewer)) mine = i;
            OfflinePlayer me = Bukkit.getOfflinePlayer(viewer);
            m.set(49, Util.head(me, "&dYou" + (mine >= 0 ? " &7- &e#" + (mine + 1) : ""),
                    List.of("&7Balance: &a" + Util.money(plugin.money().balance(viewer)), "", "&eClick to refresh")), e -> {
                refresh();
                menu(viewer, pg).open((Player) e.getWhoClicked());
            });
        }
        return m;
    }

    /** Rank of a player, 1 = richest, 0 if not listed. Used by Dark Scoreboard. */
    public int rank(UUID id) {
        List<Row> all = rows();
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(id)) return i + 1;
        return 0;
    }
}
