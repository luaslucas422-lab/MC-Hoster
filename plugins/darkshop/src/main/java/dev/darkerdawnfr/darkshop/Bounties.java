package dev.darkerdawnfr.darkshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /bounty: pay to put money on someone's head; whoever kills them gets it. Saved in bounties.yml. */
public class Bounties implements Listener {
    private final DarkShop plugin;
    private final File file;
    private final Map<UUID, Double> bounties = new HashMap<>();

    public Bounties(DarkShop plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            try {
                bounties.put(UUID.fromString(k), y.getDouble(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public double get(UUID target) {
        return bounties.getOrDefault(target, 0.0);
    }

    public enum Result { OK, TOO_LOW, NO_MONEY, SELF }

    /** Takes the money from whoever places it (null = the server, e.g. an admin or a test). */
    public Result place(UUID by, UUID target, double amount) {
        double min = plugin.getConfig().getDouble("bounty.min", 100);
        if (amount < min || !Double.isFinite(amount)) return Result.TOO_LOW;
        if (target.equals(by)) return Result.SELF;
        if (by != null && !plugin.money().take(by, amount)) return Result.NO_MONEY;
        bounties.merge(target, Math.round(amount * 100) / 100.0, Double::sum);
        save();
        return Result.OK;
    }

    public double clear(UUID target) {
        Double d = bounties.remove(target);
        save();
        return d == null ? 0 : d;
    }

    /** Pays the bounty on target to killer. Returns what was paid (0 if there was none). */
    public double claim(UUID killer, UUID target) {
        if (killer == null || killer.equals(target)) return 0;
        double b = clear(target);
        if (b > 0) plugin.money().give(killer, b);
        return b;
    }

    public List<Map.Entry<UUID, Double>> sorted() {
        List<Map.Entry<UUID, Double>> l = new ArrayList<>(bounties.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return l;
    }

    @EventHandler
    public void death(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null) return;
        double paid = claim(killer.getUniqueId(), dead.getUniqueId());
        if (paid <= 0) return;
        Bukkit.broadcastMessage(plugin.msg("bounty-claimed").replace("{killer}", killer.getName())
                .replace("{target}", dead.getName()).replace("{price}", Util.money(paid)));
        killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1f);
    }

    public Menu menu(int page) {
        List<Map.Entry<UUID, Double>> all = sorted();
        int per = 28, pages = Math.max(1, (all.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(6, "&8" + Util.small("Bounties") + " &8(" + (pg + 1) + "/" + pages + ")");
        m.border();
        int slot = 10;
        for (int i = pg * per; i < all.size() && i < (pg + 1) * per; i++) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++;
            Map.Entry<UUID, Double> e = all.get(i);
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            String name = op.getName() == null ? "?" : op.getName();
            m.set(slot++, Util.head(op, "&c" + name, List.of("&7Bounty: &a" + Util.money(e.getValue()),
                    "&7" + (op.isOnline() ? "&aOnline" : "Offline"), "", "&7Kill them to get it!", "&7Add more: &f/bounty " + name + " <amount>")));
        }
        if (all.isEmpty())
            m.set(22, Util.item(Material.PAPER, "&7No bounties", "&7Put one on someone:", "&f/bounty <player> <amount>"));
        if (pg > 0) m.set(45, Util.item(Material.ARROW, "&fPrevious page"), e -> menu(pg - 1).open((Player) e.getWhoClicked()));
        if (pg < pages - 1) m.set(53, Util.item(Material.ARROW, "&fNext page"), e -> menu(pg + 1).open((Player) e.getWhoClicked()));
        m.set(49, Util.item(Material.BOOK, "&fHow bounties work", "&f/bounty <player> <amount>",
                "&7puts your money on their head.", "&7Whoever kills them gets it.",
                "&7Smallest bounty: &a" + Util.money(plugin.getConfig().getDouble("bounty.min", 100))));
        return m;
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : bounties.entrySet()) y.set(e.getKey().toString(), e.getValue());
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save bounties: " + ex);
        }
    }
}
