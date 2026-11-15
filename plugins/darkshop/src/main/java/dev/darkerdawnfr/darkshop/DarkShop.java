package dev.darkerdawnfr.darkshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dark Shop by DarkerDawnFR: the old DonutSMP shop.
 * /shop, /sell, /worth, /balance, /pay, /baltop, /eco and /darkshop (reload, test).
 */
public class DarkShop extends JavaPlugin implements TabCompleter {
    private Money money;
    private ShopMenus shop;
    private SellMenu sell;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        money = new Money(this);
        shop = new ShopMenus(this);
        sell = new SellMenu(this);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        for (String c : List.of("shop", "sell", "worth", "balance", "pay", "baltop", "eco", "darkshop")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        money.hook();
        // Other economies (EssentialsX...) register after us, so look again once the server is up.
        Bukkit.getScheduler().runTask(this, () -> {
            money.hook();
            getLogger().info("Dark Shop ready. Money: " + money.mode() + ". " + shop.categories().size() + " shop categories.");
        });
        Bukkit.getScheduler().runTaskTimer(this, money::save, 20 * 60, 20 * 60);
        if (!shop.missing().isEmpty())
            getLogger().info("Skipped items this Minecraft version doesn't have: " + shop.missing());
    }

    @Override
    public void onDisable() {
        if (money != null) money.unhook();
    }

    public Money money() {
        return money;
    }

    // Used by Dark AH (by reflection) when Vault is not installed.
    public double balance(UUID id) { return money.balance(id); }
    public boolean take(UUID id, double amount) { return money.take(id, amount); }
    public boolean give(UUID id, double amount) { return money.give(id, amount); }
    public String economyMode() { return money.mode(); }

    public String msg(String key) {
        return Util.color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, key));
    }

    private static String c(String s) {
        return Util.color(s);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "shop" -> {
                if (!(s instanceof Player p)) return msg(s, "&cOnly players can open the shop.");
                shop.main().open(p);
            }
            case "sell" -> {
                if (!(s instanceof Player p)) return msg(s, "&cOnly players can sell.");
                sell.open(p);
            }
            case "worth" -> {
                if (!(s instanceof Player p)) return msg(s, "&cOnly players can use /worth.");
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) return msg(s, "&7Hold an item to see what it sells for.");
                double w = sell.worth(hand);
                if (w <= 0) return msg(s, "&c" + Util.nice(hand.getType()) + " can't be sold.");
                msg(s, "&f" + Util.nice(hand.getType()) + "&7: &a" + Util.money(w) + " &7each, &a"
                        + Util.money(w * hand.getAmount()) + " &7for " + hand.getAmount());
            }
            case "balance" -> {
                if (a.length > 0) {
                    OfflinePlayer t = find(a[0]);
                    if (t == null) return msg(s, "&cNo player called " + a[0] + ".");
                    msg(s, "&f" + t.getName() + "&7 has &a" + Util.money(money.balance(t.getUniqueId())));
                } else if (s instanceof Player p) {
                    msg(s, "&7Balance: &a" + Util.money(money.balance(p.getUniqueId())));
                } else {
                    msg(s, "&7Usage: /balance <player>");
                }
            }
            case "pay" -> {
                if (!(s instanceof Player p)) return msg(s, "&cOnly players can pay.");
                if (a.length < 2) return msg(s, "&7Usage: /pay <player> <amount>  (e.g. 1.5k)");
                OfflinePlayer t = find(a[0]);
                double amt = Util.parseMoney(a[1]);
                if (t == null) return msg(s, "&cNo player called " + a[0] + ".");
                if (t.getUniqueId().equals(p.getUniqueId())) return msg(s, "&cYou can't pay yourself.");
                if (Double.isNaN(amt) || amt < 0.01) return msg(s, "&c" + a[1] + " is not a valid amount.");
                if (!money.take(p.getUniqueId(), amt)) return msg(s, "&cYou don't have " + Util.money(amt) + ".");
                money.give(t.getUniqueId(), amt);
                msg(s, "&aYou paid &f" + t.getName() + " &a" + Util.money(amt) + ".");
                if (t.getPlayer() != null) t.getPlayer().sendMessage(c("&a" + p.getName() + " paid you &f" + Util.money(amt) + "&a."));
            }
            case "baltop" -> {
                if (!money.builtIn()) return msg(s, "&7Money is handled by " + money.mode() + ", use its baltop.");
                List<Map.Entry<String, Double>> top = money.top(10);
                msg(s, "&d&lTop balances");
                int i = 1;
                for (Map.Entry<String, Double> e : top)
                    s.sendMessage(c("&7" + i++ + ". &f" + e.getKey() + " &8- &a" + Util.money(e.getValue())));
                if (top.isEmpty()) s.sendMessage(c("&7Nobody has money yet."));
            }
            case "eco" -> {
                if (a.length < 3) return msg(s, "&7Usage: /eco <give|take|set> <player> <amount>");
                OfflinePlayer t = find(a[1]);
                double amt = Util.parseMoney(a[2]);
                if (t == null) return msg(s, "&cNo player called " + a[1] + ".");
                if (Double.isNaN(amt) || amt < 0) return msg(s, "&c" + a[2] + " is not a valid amount.");
                UUID id = t.getUniqueId();
                switch (a[0].toLowerCase(Locale.ROOT)) {
                    case "give" -> money.give(id, amt);
                    case "take" -> money.set(id, Math.max(0, money.balance(id) - amt));
                    case "set" -> money.set(id, amt);
                    default -> { return msg(s, "&7Usage: /eco <give|take|set> <player> <amount>"); }
                }
                msg(s, "&f" + t.getName() + "&7 now has &a" + Util.money(money.balance(id)));
            }
            case "darkshop" -> {
                String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
                if (sub.equals("reload")) {
                    reloadConfig();
                    shop.reload();
                    money.hook();
                    msg(s, "&aReloaded. Money: " + money.mode());
                } else if (sub.equals("test")) {
                    test(s);
                } else {
                    msg(s, "&7Dark Shop " + getDescription().getVersion() + " by DarkerDawnFR. Money: " + money.mode());
                    msg(s, "&7/darkshop reload  -  /darkshop test");
                }
            }
            default -> { return false; }
        }
        return true;
    }

    private boolean msg(CommandSender s, String text) {
        s.sendMessage(c(getConfig().getString("messages.prefix", "") + text));
        return true;
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer p = Bukkit.getOfflinePlayer(name);
        return p.hasPlayedBefore() ? p : null;
    }

    /** Checks everything without a player: used by MC Hoster's server test. */
    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        try {
            if (shop.categories().size() < 4) fails.add("only " + shop.categories().size() + " categories");
            Menu main = shop.main();
            if (main.getInventory().getItem(10) == null) fails.add("main menu empty");
            for (ShopMenus.Category c : shop.categories().values()) {
                if (c.items().isEmpty()) fails.add(c.id() + " has no items");
                shop.category(c);
                if (!c.items().isEmpty()) {
                    Menu am = shop.amount(c, c.items().get(0), 10);
                    ItemStack shown = am.getInventory().getItem(13);
                    if (shown == null || shown.getAmount() != 10) fails.add("amount menu shows wrong amount");
                }
            }
            if (sell.worth(new ItemStack(Material.DIAMOND)) <= 0) fails.add("diamond has no worth");
            if (Util.parseMoney("1.5k") != 1500 || !Util.money(2_500_000).equals("$2.5M")) fails.add("money text");
            if (money.builtIn()) {
                UUID id = UUID.nameUUIDFromBytes("darkshop-test".getBytes());
                money.set(id, 0);
                money.give(id, 1000);
                boolean took = money.take(id, 400);
                boolean tooMuch = money.take(id, 10_000);
                if (!took || tooMuch || Math.abs(money.balance(id) - 600) > 1e-6)
                    fails.add("built-in money: balance " + money.balance(id));
                money.forget(id);
            }
            Object vault = null;
            try {
                Class<?> econ = Class.forName("net.milkbowl.vault.economy.Economy");
                var rsp = Bukkit.getServicesManager().getRegistration(econ);
                vault = rsp == null ? null : rsp.getProvider();
            } catch (Throwable ignored) {
            }
            if (vault != null && money.builtIn()) {
                // our economy as other plugins see it through Vault
                OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.nameUUIDFromBytes("darkshop-vault-test".getBytes()));
                Money.call(vault, "depositPlayer", op, 250.0);
                Object r = Money.call(vault, "withdrawPlayer", op, 50.0);
                Object bal = Money.call(vault, "getBalance", op);
                Object ok = r == null ? null : Money.call(r, "transactionSuccess");
                if (!(bal instanceof Number n) || Math.abs(n.doubleValue() - 200) > 1e-6 || !Boolean.TRUE.equals(ok))
                    fails.add("vault: balance " + bal + ", withdraw ok " + ok);
                money.forget(op.getUniqueId());
            }
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        }
        String result = fails.isEmpty() ? "DARKSHOP TEST OK (money: " + money.mode() + ")" : "DARKSHOP TEST FAIL: " + fails;
        getLogger().info(result);
        if (!(s instanceof org.bukkit.command.ConsoleCommandSender)) s.sendMessage(result);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if ((name.equals("pay") || name.equals("balance")) && a.length == 1
                || name.equals("eco") && a.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (name.equals("eco") && a.length == 1) {
            out.addAll(List.of("give", "take", "set"));
        } else if (name.equals("darkshop") && a.length == 1) {
            out.addAll(List.of("reload", "test"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
