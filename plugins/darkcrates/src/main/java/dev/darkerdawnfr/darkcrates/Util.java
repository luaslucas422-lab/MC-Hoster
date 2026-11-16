package dev.darkerdawnfr.darkcrates;

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

    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("(?i)&#([0-9a-f]{6})");

    /** &a codes and &#RRGGBB colours. */
    public static String color(String s) {
        if (s == null) return "";
        java.util.regex.Matcher m = HEX.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            StringBuilder h = new StringBuilder("\u00A7x");
            for (char c : m.group(1).toCharArray()) h.append('\u00A7').append(c);
            m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(h.toString()));
        }
        m.appendTail(b);
        return ChatColor.translateAlternateColorCodes('&', b.toString());
    }

    /** "Rare Crate" in a gradient, as &#RRGGBB codes per letter. */
    public static String gradient(String text, String from, String to, boolean bold) {
        java.awt.Color a = java.awt.Color.decode(from), z = java.awt.Color.decode(to);
        StringBuilder b = new StringBuilder();
        int n = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                b.append(ch);
                continue;
            }
            double t = (double) i / n;
            int r = (int) Math.round(a.getRed() + (z.getRed() - a.getRed()) * t), g = (int) Math.round(a.getGreen() + (z.getGreen() - a.getGreen()) * t),
                    bl = (int) Math.round(a.getBlue() + (z.getBlue() - a.getBlue()) * t);
            b.append(String.format("&#%02X%02X%02X", r, g, bl)).append(bold ? "&l" : "").append(ch);
        }
        return b.toString();
    }

    /** The text without any colour codes (&a, &#RRGGBB or already coloured). */
    public static String plain(String s) {
        return ChatColor.stripColor(color(s));
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
