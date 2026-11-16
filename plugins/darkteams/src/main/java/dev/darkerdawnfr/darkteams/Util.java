package dev.darkerdawnfr.darkteams;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Small helpers shared by every menu: colours, small caps titles, money text, item building. */
public final class Util {
    private Util() {}

    private static final String NORMAL = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀѕᴛᴜᴠᴡxʏᴢ";

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    /** "Shop - End" -> "ѕʜᴏᴘ - ᴇɴᴅ", the DonutSMP title look. */
    public static String small(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            int i = NORMAL.indexOf(c);
            b.append(i >= 0 ? SMALL.charAt(i) : c);
        }
        return b.toString();
    }

    /** 1500 -> "1.5K", 2000000 -> "2M". */
    public static String money(double v) {
        String sign = v < 0 ? "-" : "";
        v = Math.abs(v);
        String[] units = {"", "K", "M", "B", "T"};
        int u = 0;
        while (v >= 1000 && u < units.length - 1) {
            v /= 1000;
            u++;
        }
        DecimalFormat f = new DecimalFormat(u == 0 ? "#,##0.##" : "0.##");
        return "$" + sign + f.format(v) + units[u];
    }

    /** "1.5k" -> 1500, "2m" -> 2000000, "100" -> 100. NaN if it is not a number. */
    public static double parseMoney(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        s = s.trim().toLowerCase(Locale.ROOT).replace("$", "").replace(",", "");
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> mult = 1e3;
            case 'm' -> mult = 1e6;
            case 'b' -> mult = 1e9;
            case 't' -> mult = 1e12;
            default -> { }
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            double v = Double.parseDouble(s) * mult;
            return Double.isFinite(v) ? Math.round(v * 100) / 100.0 : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** "GOLDEN_APPLE" -> "Golden Apple". */
    public static String nice(Material m) {
        StringBuilder b = new StringBuilder();
        for (String w : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    public static ItemStack item(Material m, String name, String... lore) {
        return item(m, 1, name, Arrays.asList(lore));
    }

    public static ItemStack item(Material m, int amount, String name, List<String> lore) {
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (name != null) meta.setDisplayName(color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(color(s));
            meta.setLore(l);
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    /** A player head with a name and lore. */
    public static ItemStack head(org.bukkit.OfflinePlayer owner, String name, List<String> lore) {
        ItemStack it = item(Material.PLAYER_HEAD, 1, name, lore);
        if (owner != null && it.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
            try {
                sm.setOwningPlayer(owner);
                it.setItemMeta(sm);
            } catch (Throwable ignored) {
            }
        }
        return it;
    }

    public static ItemStack glass() {
        return item(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    public static Material material(String name) {
        return name == null ? null : Material.matchMaterial(name.trim());
    }
}
