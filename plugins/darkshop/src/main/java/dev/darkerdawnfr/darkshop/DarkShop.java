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
public class DarkShop extends JavaPlugin implements TabCompleter, org.bukkit.event.Listener {
    private Money money;
    private ShopMenus shop, premium;
    private SellMenu sell;
    private Bounties bounties;
    private Orders orders;
    private ShopEditor editor, premiumEditor;
    private Baltop baltop;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        money = new Money(this);
        migrate();
        shop = new ShopMenus(this);
        premium = new ShopMenus(this, "premium.categories", true);
        sell = new SellMenu(this);
        bounties = new Bounties(this);
        baltop = new Baltop(this);
        orders = new Orders(this);
        editor = new ShopEditor(this, shop);
        premiumEditor = new ShopEditor(this, premium);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(bounties, this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("shop", "sell", "worth", "balance", "pay", "baltop", "eco", "bounty", "orders", "darkshop")) {
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

    /**
     * When EssentialsX (or another plugin) also has /pay, /bal, /sell..., the one that loaded first wins.
     * This sends them to Dark Shop instead, so /pay always uses Dark Shop.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void ownCommands(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        if (!getConfig().getBoolean("own-commands", true)) return;
        String msg = e.getMessage();
        if (!msg.startsWith("/") || msg.length() < 2) return;
        String word = msg.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (word.contains(":")) return;
        PluginCommand ours = getCommand(word);
        if (ours == null) {
            for (String c : List.of("shop", "sell", "worth", "balance", "pay", "baltop", "eco", "bounty", "orders")) {
                PluginCommand pc = getCommand(c);
                if (pc != null && pc.getAliases().contains(word)) ours = pc;
            }
        }
        if (ours == null || ours.getName().equals("darkshop")) return;
        org.bukkit.command.Command current = Bukkit.getPluginCommand(word);
        if (current == ours) return; // already ours
        e.setMessage("/darkshop:" + msg.substring(1));
    }

    /** Adds things from newer versions (more /sell items, the premium shop) without touching what you changed. */
    private void migrate() {
        int v = getConfig().getInt("config-version", 1);
        if (v >= 2) return;
        var defs = getConfig().getDefaults();
        if (defs != null) {
            var worth = defs.getConfigurationSection("worth");
            if (worth != null) for (String k : worth.getKeys(false))
                if (!getConfig().isSet("worth." + k)) getConfig().set("worth." + k, worth.get(k));
            for (String k : List.of("premium", "worth-hint"))
                if (!getConfig().isSet(k) && defs.isSet(k)) getConfig().set(k, defs.get(k));
        }
        getConfig().set("config-version", 2);
        saveConfig();
    }

    public ShopMenus premium() {
        return premium;
    }

    /** What the item in your hand sells for, above the hotbar (when you switch to it). */
    @org.bukkit.event.EventHandler
    public void held(org.bukkit.event.player.PlayerItemHeldEvent e) {
        if (!getConfig().getBoolean("worth-hint", true)) return;
        Player p = e.getPlayer();
        if (!Settings.on(p.getUniqueId(), "worth")) return;
        ItemStack it = p.getInventory().getItem(e.getNewSlot());
        if (it == null || it.getType() == Material.AIR) return;
        double w = sell.worth(it);
        if (w <= 0) return;
        String text = Util.color("&7" + Util.nice(it.getType()) + " sells for &a" + Util.money(w) + (it.getAmount() > 1 ? " &8(&a" + Util.money(w * it.getAmount()) + " &8for " + it.getAmount() + ")" : ""));
        try {
            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text));
        } catch (Throwable t) {
            try {
                p.sendActionBar(text);
            } catch (Throwable ignored) {
            }
        }
    }

    public ShopMenus shop() {
        return shop;
    }

    public Money money() {
        return money;
    }

    // Used by Dark AH (by reflection) when Vault is not installed.
    public double balance(UUID id) { return money.balance(id); }
    public boolean take(UUID id, double amount) { return money.take(id, amount); }
    public boolean give(UUID id, double amount) { return money.give(id, amount); }
    public String economyMode() { return money.mode(); }
    // Used by Dark Spawners: what one item sells for in /sell.
    public double worth(String material) { return getConfig().getDouble("worth." + material, 0); }

    /** Starter gear from Dark Essentials can't be sold or put on the auction house. */
    public static boolean starter(ItemStack it) {
        if (it == null || !it.hasItemMeta() || it.getItemMeta() == null) return false;
        return it.getItemMeta().getPersistentDataContainer().has(new org.bukkit.NamespacedKey("darkessentials", "starter"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }
    // Used by Dark Scoreboard.
    public double bounty(UUID id) { return bounties.get(id); }
    public int baltopRank(UUID id) { return baltop.rank(id); }

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
                if (a.length > 0 && a[0].equalsIgnoreCase("edit")) {
                    if (!p.hasPermission("darkshop.admin")) return msg(s, "&cOnly ops can edit the shop.");
                    boolean prem = a.length > 1 && a[1].equalsIgnoreCase("premium");
                    (prem ? premiumEditor : editor).main(p).open(p);
                    return true;
                }
                if (a.length > 0 && a[0].equalsIgnoreCase("premium")) {
                    if (!p.hasPermission("darkshop.premium")) return msg(s, "&6/shop premium &7is for ranks: better deals and more things. See &f/rank shop&7.");
                    premium.main(p).open(p);
                    return true;
                }
                shop.main(p).open(p);
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
                if (!Settings.on(t.getUniqueId(), "pay")) return msg(s, "&c" + t.getName() + " has payments turned off in /settings.");
                if (t.getUniqueId().equals(p.getUniqueId())) return msg(s, "&cYou can't pay yourself.");
                if (Double.isNaN(amt) || amt < 0.01) return msg(s, "&c" + a[1] + " is not a valid amount.");
                if (!money.take(p.getUniqueId(), amt)) return msg(s, "&cYou don't have " + Util.money(amt) + ".");
                money.give(t.getUniqueId(), amt);
                msg(s, "&aYou paid &f" + t.getName() + " &a" + Util.money(amt) + ".");
                if (t.getPlayer() != null) t.getPlayer().sendMessage(c("&a" + p.getName() + " paid you &f" + Util.money(amt) + "&a."));
            }
            case "baltop" -> {
                if (s instanceof Player p) {
                    baltop.menu(p.getUniqueId(), 0).open(p);
                    return true;
                }
                List<Baltop.Row> top = baltop.rows();
                msg(s, "&d&lTop balances");
                for (int i = 0; i < Math.min(10, top.size()); i++)
                    s.sendMessage(c("&7" + (i + 1) + ". &f" + top.get(i).name() + " &8- &a" + Util.money(top.get(i).balance())));
                if (top.isEmpty()) s.sendMessage(c("&7Nobody has money yet."));
            }
            case "bounty" -> {
                if (a.length == 0 || a[0].equalsIgnoreCase("list")) {
                    if (s instanceof Player p) bounties.menu(0).open(p);
                    else for (Map.Entry<UUID, Double> e : bounties.sorted())
                        s.sendMessage(c("&f" + Bukkit.getOfflinePlayer(e.getKey()).getName() + " &8- &a" + Util.money(e.getValue())));
                    return true;
                }
                if (a[0].equalsIgnoreCase("remove")) {
                    if (!s.hasPermission("darkshop.admin")) return msg(s, "&cOnly ops can remove bounties.");
                    if (a.length < 2) return msg(s, "&7Usage: /bounty remove <player>");
                    OfflinePlayer t = find(a[1]);
                    if (t == null) return msg(s, "&cNo player called " + a[1] + ".");
                    return msg(s, "&7Removed the " + Util.money(bounties.clear(t.getUniqueId())) + " bounty on &f" + t.getName());
                }
                if (a.length < 2) return msg(s, "&7Usage: /bounty <player> <amount>  -  /bounty to see them all");
                OfflinePlayer t = find(a[0]);
                double amt = Util.parseMoney(a[1]);
                if (t == null) return msg(s, "&cNo player called " + a[0] + ".");
                if (Double.isNaN(amt)) return msg(s, "&c" + a[1] + " is not a valid amount.");
                UUID by = s instanceof Player p ? p.getUniqueId() : null;
                switch (bounties.place(by, t.getUniqueId(), amt)) {
                    case TOO_LOW -> { return msg(s, "&cThe smallest bounty is " + Util.money(getConfig().getDouble("bounty.min", 100)) + "."); }
                    case SELF -> { return msg(s, "&cYou can't put a bounty on yourself."); }
                    case NO_MONEY -> { return msg(s, "&cYou don't have " + Util.money(amt) + "."); }
                    case OK -> Bukkit.broadcastMessage(msg("bounty-placed").replace("{player}", s.getName())
                            .replace("{target}", t.getName()).replace("{price}", Util.money(amt))
                            .replace("{total}", Util.money(bounties.get(t.getUniqueId()))));
                }
            }
            case "orders" -> {
                if (!(s instanceof Player p)) return msg(s, "&cOnly players can use orders.");
                if (a.length > 0 && (a[0].equalsIgnoreCase("new") || a[0].equalsIgnoreCase("create"))) {
                    Material item;
                    int at = 1;
                    if (a.length >= 4) {
                        item = Util.material(a[1].toUpperCase(Locale.ROOT));
                        at = 2;
                    } else item = p.getInventory().getItemInMainHand().getType();
                    if (a.length < at + 2) return msg(s, "&7Hold the item and type &f/orders new <amount> <price each>&7, or &f/orders new <item> <amount> <price each>");
                    int amount;
                    try {
                        amount = Integer.parseInt(a[at].replace(",", "").replaceAll("(?i)k$", "000"));
                    } catch (NumberFormatException e) {
                        return msg(s, "&c" + a[at] + " is not an amount.");
                    }
                    double price = Util.parseMoney(a[at + 1]);
                    String[] why = new String[1];
                    Orders.Order o = orders.create(p.getUniqueId(), p.getName(), item, amount, price, why);
                    if (o == null) return msg(s, "&c" + why[0]);
                    return msg(s, "&aOrder made: &f" + amount + "x " + Util.nice(item) + " &aat &f" + Util.money(price) + " &aeach. &7"
                            + Util.money(amount * price) + " is held until it's delivered. &8(/orders > Your orders)");
                }
                orders.browse(p, 0).open(p);
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
                    premium.reload();
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
            Menu main = shop.main(null);
            if (main.getInventory().getItem(10) == null) fails.add("main menu empty");
            for (ShopMenus.Category c : shop.categories().values()) {
                if (c.items().isEmpty()) fails.add(c.id() + " has no items");
                shop.category(null, c);
                if (!c.items().isEmpty()) {
                    Menu am = shop.amount(null, c, c.items().get(0), 10);
                    ItemStack shown = am.getInventory().getItem(13);
                    if (shown == null || shown.getAmount() != 10) fails.add("amount menu shows wrong amount");
                }
            }
            if (sell.worth(new ItemStack(Material.DIAMOND)) <= 0) fails.add("diamond has no worth");
            if (premium.categories().size() < 2) fails.add("premium shop has " + premium.categories().size() + " categories");
            premium.main(null);
            int sellable = getConfig().getConfigurationSection("worth").getKeys(false).size();
            if (sellable < 150) fails.add("only " + sellable + " items can be sold");
            String cfgBackup = getConfig().saveToString();
            editor.setPrice("end", Material.DIAMOND_BLOCK, 12345);
            reloadConfig();
            shop.reload();
            if (shop.categories().get("end").items().stream().noneMatch(en -> en.material() == Material.DIAMOND_BLOCK && en.price() == 12345))
                fails.add("shop editor price didn't stick");
            editor.removeItem("end", Material.DIAMOND_BLOCK);
            if (shop.categories().get("end").items().stream().anyMatch(en -> en.material() == Material.DIAMOND_BLOCK)) fails.add("shop editor remove");
            java.nio.file.Files.writeString(new java.io.File(getDataFolder(), "config.yml").toPath(), cfgBackup);
            reloadConfig();
            shop.reload();
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
                // bounties: someone with money puts one on a target, the killer gets it
                UUID placer = UUID.nameUUIDFromBytes("darkshop-test-placer".getBytes());
                UUID target = UUID.nameUUIDFromBytes("darkshop-test-target".getBytes());
                UUID killer = UUID.nameUUIDFromBytes("darkshop-test-killer".getBytes());
                money.set(placer, 1000);
                if (bounties.place(placer, target, 50) != Bounties.Result.TOO_LOW) fails.add("bounty below the minimum");
                if (bounties.place(placer, placer, 500) != Bounties.Result.SELF) fails.add("bounty on yourself");
                if (bounties.place(placer, target, 5000) != Bounties.Result.NO_MONEY) fails.add("bounty without money");
                if (bounties.place(placer, target, 300) != Bounties.Result.OK || bounties.place(placer, target, 200) != Bounties.Result.OK)
                    fails.add("bounty not placed");
                if (bounties.get(target) != 500 || money.balance(placer) != 500) fails.add("bounty total " + bounties.get(target));
                bounties.menu(0);
                if (bounties.claim(killer, target) != 500 || money.balance(killer) != 500 || bounties.get(target) != 0)
                    fails.add("bounty not paid to the killer");
                money.forget(placer);
                money.forget(killer);
                // baltop menu
                money.set(id, 123);
                money.setName(id, "TestPlayer");
                baltop.refresh();
                if (baltop.rows().stream().noneMatch(r -> r.id().equals(id))) fails.add("baltop missing a player");
                if (baltop.menu(id, 0).getInventory().getItem(49) == null) fails.add("baltop menu");
                money.forget(id);
                baltop.refresh();
                // orders: the money is held, deliverers are paid, the buyer collects, cancelling refunds
                UUID buyer = UUID.nameUUIDFromBytes("darkshop-order-buyer".getBytes()), seller = UUID.nameUUIDFromBytes("darkshop-order-seller".getBytes());
                money.set(buyer, 10_000);
                money.set(seller, 0);
                String[] why = new String[1];
                Orders.Order o = orders.create(buyer, "Buyer", Material.DIAMOND, 100, 50, why);
                if (o == null || money.balance(buyer) != 5000) fails.add("order escrow: " + why[0] + " " + money.balance(buyer));
                if (o != null) {
                    if (orders.create(buyer, "Buyer", Material.DIAMOND, 1000, 50, why) != null) fails.add("order without money");
                    if (orders.deliver(seller, o, 30) != 30 || money.balance(seller) != 1500 || o.waiting != 30) fails.add("deliver");
                    if (orders.deliver(seller, o, 500) != 70 || o.left() != 0) fails.add("over-deliver");
                    if (orders.deliver(buyer, o, 1) != 0) fails.add("delivered to own order");
                    Orders.Order o2 = orders.create(buyer, "Buyer", Material.IRON_INGOT, 10, 100, why);
                    if (o2 == null || orders.cancel(buyer, o2) != 1000 || money.balance(buyer) != 5000) fails.add("cancel refund");
                    orders.remove(o.id);
                }
                money.forget(buyer);
                money.forget(seller);
                ItemStack st = new ItemStack(Material.STONE_SWORD);
                var sm = st.getItemMeta();
                sm.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("darkessentials", "starter"), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                st.setItemMeta(sm);
                if (!starter(st) || sell.worth(st) != 0 || starter(new ItemStack(Material.STONE_SWORD))) fails.add("starter items");
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
        if ((name.equals("pay") || name.equals("balance") || name.equals("bounty")) && a.length == 1
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
