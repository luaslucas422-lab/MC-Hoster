package dev.darkerdawnfr.noenchantlimits;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * No Enchant Limits by DarkerDawnFR. /enchant puts any enchantment, at any level up to 255, on any item:
 * /enchant <enchantment> [level] (what you hold) or /enchant <player> <enchantment> [level].
 * If it won't do anything on that item (Sharpness on a stick), it's still added, and you get a warning.
 */
public class NoEnchantLimits extends JavaPlugin implements TabCompleter {
    public static final int MAX = 255; // the most Minecraft can save

    @Override
    public void onEnable() {
        for (String c : List.of("enchant", "noenchantlimits")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
    }

    private static String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    private boolean say(CommandSender s, String t) {
        s.sendMessage(color("&8[&5Enchant&8] " + t));
        return true;
    }

    public static Enchantment find(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(n));
        if (e != null) return e;
        // a few names people type: "prot 4", "unbreaking", "windburst", "sharp"
        String[][] alias = {{"prot", "protection"}, {"sharp", "sharpness"}, {"unb", "unbreaking"}, {"eff", "efficiency"}, {"fort", "fortune"},
                {"loot", "looting"}, {"fire", "fire_aspect"}, {"kb", "knockback"}, {"silk", "silk_touch"}, {"wind", "wind_burst"},
                {"windburst", "wind_burst"}, {"aqua", "aqua_affinity"}, {"depth", "depth_strider"}, {"feather", "feather_falling"}};
        for (String[] a : alias) if (a[0].equals(n)) return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(a[1]));
        try {
            NamespacedKey k = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            return k == null ? null : Registry.ENCHANTMENT.get(k);
        } catch (Exception ex) {
            return null;
        }
    }

    public static String nice(Enchantment e) {
        String k = e.getKey().getKey();
        StringBuilder b = new StringBuilder();
        for (String w : k.split("_")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return b.toString().trim();
    }

    /** Puts it on, whatever the item or level. Returns a warning to show, or null if it works normally. */
    public static String apply(ItemStack it, Enchantment e, int level) {
        it.addUnsafeEnchantment(e, level);
        if (!e.canEnchantItem(it) && it.getType() != Material.ENCHANTED_BOOK && it.getType() != Material.BOOK)
            return nice(e) + " doesn't do anything on " + it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + ", so it was added but won't work (Minecraft doesn't support it at all on that item).";
        if (level > e.getMaxLevel() && e.getMaxLevel() == 1)
            return nice(e) + " has no levels in Minecraft, so level " + level + " works the same as level 1.";
        return null;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("noenchantlimits.use")) return say(s, "&cOnly ops can use /enchant.");
        if (cmd.getName().equalsIgnoreCase("noenchantlimits") && a.length > 0 && a[0].equalsIgnoreCase("test")) {
            test(s);
            return true;
        }
        if (a.length == 0) return say(s, "&7Usage: &f/enchant <enchantment> [level] &7(the item you hold) or &f/enchant <player> <enchantment> [level]&7. Any level up to " + MAX + ".");
        Player target;
        int i = 0;
        Player named = Bukkit.getPlayerExact(a[0]);
        if (named != null && a.length > 1 && find(a[0]) == null) {
            target = named;
            i = 1;
        } else if (a[0].equals("@s") && s instanceof Player p && a.length > 1) {
            target = p;
            i = 1;
        } else if (s instanceof Player p) target = p;
        else return say(s, "&7From the console: /enchant <player> <enchantment> [level]");
        Enchantment e = find(a[i]);
        if (e == null) return say(s, "&cThere's no enchantment called &f" + a[i] + "&c.");
        int level = 1;
        if (a.length > i + 1) {
            try {
                level = Integer.parseInt(a[i + 1]);
            } catch (NumberFormatException ex) {
                return say(s, "&c" + a[i + 1] + " isn't a level.");
            }
        }
        if (level < 1) return say(s, "&cThe level has to be 1 or more.");
        if (level > MAX) {
            say(s, "&eMinecraft can't save levels above " + MAX + ", so it's " + MAX + ".");
            level = MAX;
        }
        ItemStack hand = target.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) return say(s, "&c" + (target == s ? "Hold" : target.getName() + " isn't holding") + " an item" + (target == s ? " first." : "."));
        String warn = apply(hand, e, level);
        target.getInventory().setItemInMainHand(hand);
        say(s, "&aAdded &f" + nice(e) + " " + level + " &ato " + (target == s ? "your" : target.getName() + "'s") + " &f"
                + hand.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "&a.");
        if (warn != null) say(s, "&e⚠ " + warn);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("noenchantlimits")) return out;
        if (a.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            for (Enchantment e : Registry.ENCHANTMENT) out.add(e.getKey().getKey());
        } else if (a.length == 2) {
            if (find(a[0]) != null) out.addAll(List.of("1", "5", "10", "64", "255"));
            else for (Enchantment e : Registry.ENCHANTMENT) out.add(e.getKey().getKey());
        } else if (a.length == 3) out.addAll(List.of("1", "5", "10", "64", "255"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        try {
            Enchantment sharp = find("sharpness"), unb = find("unb");
            if (sharp == null || unb == null || find("nonsense") != null) fails.add("names");
            ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
            if (apply(sword, sharp, 64) != null || sword.getEnchantmentLevel(sharp) != 64) fails.add("sharpness 64");
            ItemStack stick = new ItemStack(Material.STICK);
            if (apply(stick, sharp, 10) == null || stick.getEnchantmentLevel(sharp) != 10) fails.add("stick warning");
            Enchantment wind = find("windburst");
            if (wind != null) { // 1.21+
                ItemStack mace = new ItemStack(Material.valueOf("MACE"));
                if (apply(mace, wind, 64) != null || mace.getEnchantmentLevel(wind) != 64) fails.add("mace wind burst 64");
            }
            ItemStack big = new ItemStack(Material.DIAMOND_PICKAXE);
            apply(big, find("efficiency"), MAX);
            if (big.getEnchantmentLevel(find("efficiency")) != MAX) fails.add("level " + MAX);
            PluginCommand enchant = getCommand("enchant");
            if (enchant == null || Bukkit.getPluginCommand("enchant") == null || !(Bukkit.getPluginCommand("enchant").getPlugin() instanceof NoEnchantLimits))
                fails.add("/enchant isn't ours");
        } catch (Throwable t) {
            fails.add(t.toString());
        }
        String r = fails.isEmpty() ? "NOENCHANTLIMITS TEST OK" : "NOENCHANTLIMITS TEST FAIL: " + fails;
        getLogger().info(r);
        if (!(s instanceof ConsoleCommandSender)) s.sendMessage(r);
    }
}
