package dev.darkerdawnfr.darkshop;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /sell: put items in the chest, close it, get paid. Items that can't be sold are given back. */
public class SellMenu {
    private final DarkShop plugin;

    public SellMenu(DarkShop plugin) {
        this.plugin = plugin;
    }

    public double worth(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || DarkShop.starter(it)) return 0;
        // Dark Fishing's fish (and anything else with its own price) sell for that price
        if (it.hasItemMeta()) {
            Double own = it.getItemMeta().getPersistentDataContainer().get(new org.bukkit.NamespacedKey("darkfishing", "value"),
                    org.bukkit.persistence.PersistentDataType.DOUBLE);
            if (own != null) return own;
        }
        // renamed or enchanted items are not sold by mistake
        if (it.hasItemMeta() && it.getItemMeta() != null
                && (it.getItemMeta().hasDisplayName() || it.getItemMeta().hasEnchants())) return 0;
        return plugin.getConfig().getDouble("worth." + it.getType().name(), 0);
    }

    /** Sell boost for this player: the best "sell-multipliers" entry they have darkshop.multiplier.<name> for (1 = none). */
    public double multiplier(Player p) {
        double best = 1;
        org.bukkit.configuration.ConfigurationSection d = plugin.getConfig().getConfigurationSection("sell-multipliers");
        if (d != null) for (String k : d.getKeys(false))
            if (p.hasPermission("darkshop.multiplier." + k)) best = Math.max(best, d.getDouble(k, 1));
        return best;
    }

    public static String x(double m) {
        return (m == Math.floor(m) ? String.valueOf((int) m) : String.valueOf(Math.round(m * 100) / 100.0)) + "x";
    }

    public Menu open(Player p) {
        double mul = multiplier(p);
        Menu m = new Menu(6, "&8" + Util.small("Sell items") + (mul > 1 ? " &a" + x(mul) : ""));
        m.editable = true;
        m.onClose = player -> sell(player, m.getInventory());
        m.open(p);
        return m;
    }

    /** Sells what's in the inventory and empties it. Returns the money paid. */
    public double sell(Player p, Inventory inv) {
        double total = 0;
        int count = 0;
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            double w = worth(it);
            if (w > 0) {
                total += w * it.getAmount();
                count += it.getAmount();
            } else {
                back.add(it);
            }
            inv.setItem(i, null);
        }
        if (!back.isEmpty()) {
            Map<Integer, ItemStack> left = new HashMap<>(p.getInventory().addItem(back.toArray(new ItemStack[0])));
            for (ItemStack it : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), it);
        }
        double mul = multiplier(p);
        total = Math.round(total * mul * 100) / 100.0;
        if (count > 0) {
            plugin.money().give(p.getUniqueId(), total);
            p.sendMessage(plugin.msg("sold").replace("{amount}", String.valueOf(count)).replace("{price}", Util.money(total))
                    + (mul > 1 ? Util.color(" &8(" + x(mul) + " " + Util.small("boost") + ")") : ""));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
        } else if (!back.isEmpty()) {
            p.sendMessage(plugin.msg("nothing-sold"));
        }
        return total;
    }
}
