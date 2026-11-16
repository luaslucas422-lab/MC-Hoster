package dev.darkerdawnfr.darkah;

import dev.darkerdawnfr.darkah.AuctionHouse.Filter;
import dev.darkerdawnfr.darkah.AuctionHouse.Listing;
import dev.darkerdawnfr.darkah.AuctionHouse.Sort;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dark AH by DarkerDawnFR: a DonutSMP-style auction house.
 * /ah, /ah sell <price>, /ah <search>, /darkah (reload, test).
 */
public class DarkAH extends JavaPlugin implements Listener, TabCompleter {
    private final Eco eco = new Eco();
    private AuctionHouse ah;
    private AhMenus menus;
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ah = new AuctionHouse(this);
        menus = new AhMenus(this);
        getServer().getPluginManager().registerEvents(new Menu.Events(), this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("ah", "darkah")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        eco.hook();
        Bukkit.getScheduler().runTask(this, () -> {
            eco.hook();
            getLogger().info("Dark AH ready. " + ah.all().size() + " listings. Money: " + eco.mode());
        });
        Bukkit.getScheduler().runTaskTimer(this, ah::expire, 20 * 30, 20 * 30);
    }

    @Override
    public void onDisable() {
        if (ah != null) ah.save();
    }

    public Eco eco() { return eco; }
    public AuctionHouse ah() { return ah; }
    // Used by Dark Scoreboard.
    public int listingCount(UUID id) { return ah.of(id).size(); }
    public int listingTotal() { return ah.all().size(); }
    // Used by Dark Shop: what one item usually goes for on /ah (0 = no sales yet).
    public double auctionValue(String material) {
        Material m = Material.matchMaterial(material);
        return m == null ? 0 : ah.auctionValue(m);
    }

    public String msg(String key) {
        return Util.color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, key));
    }

    private boolean say(CommandSender s, String text) {
        s.sendMessage(Util.color(getConfig().getString("messages.prefix", "") + text));
        return true;
    }

    public static String itemName(ItemStack it) {
        if (it.hasItemMeta() && it.getItemMeta() != null && it.getItemMeta().hasDisplayName())
            return it.getItemMeta().getDisplayName() + ChatColor.RESET;
        return (it.getAmount() > 1 ? it.getAmount() + "x " : "") + Util.nice(it.getType());
    }

    public void askSearch(Player p) {
        searching.add(p.getUniqueId());
        p.sendMessage(msg("search-ask"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncPlayerChatEvent e) {
        if (!searching.remove(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        String text = ChatColor.stripColor(e.getMessage()).trim();
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = e.getPlayer();
            if (!p.isOnline()) return;
            AhMenus.View v = menus.view(p.getUniqueId());
            v.search = text.equalsIgnoreCase("cancel") ? "" : text.length() > 32 ? text.substring(0, 32) : text;
            v.page = 0;
            menus.browse(p.getUniqueId()).open(p);
        });
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        double[] a = ah.takeAway(e.getPlayer().getUniqueId());
        if (a != null && a[0] > 0) e.getPlayer().sendMessage(msg("sold-away").replace("{count}", String.valueOf((int) a[0])).replace("{price}", Util.money(a[1])));
        int n = ah.claims(e.getPlayer().getUniqueId()).size();
        if (n > 0) e.getPlayer().sendMessage(msg("collect").replace("{count}", String.valueOf(n)));
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        searching.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("darkah")) {
            String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
            if (sub.equals("reload")) {
                reloadConfig();
                eco.hook();
                return say(s, "&aReloaded. Money: " + eco.mode());
            }
            if (sub.equals("test")) {
                test(s);
                return true;
            }
            say(s, "&7Dark AH " + getDescription().getVersion() + " by DarkerDawnFR. " + ah.all().size() + " listings. Money: " + eco.mode());
            return say(s, "&7/darkah reload  -  /darkah test");
        }
        if (!(s instanceof Player p)) return say(s, "&cOnly players can use the auction house.");
        if (a.length > 0 && a[0].equalsIgnoreCase("sell")) return sell(p, a);
        AhMenus.View v = menus.view(p.getUniqueId());
        if (a.length > 0) {
            v.search = String.join(" ", a);
            v.page = 0;
        }
        menus.browse(p.getUniqueId()).open(p);
        return true;
    }

    private boolean sell(Player p, String[] a) {
        if (a.length < 2) return say(p, "&7Hold an item and type &f/ah sell <price>&7, e.g. /ah sell 1.5k");
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) return say(p, "&cHold the item you want to sell.");
        double price = Util.parseMoney(a[1]);
        double min = getConfig().getDouble("min-price", 1), max = getConfig().getDouble("max-price", 1e12);
        if (Double.isNaN(price)) return say(p, "&c" + a[1] + " is not a price. Try 500, 1.5k or 2m.");
        if (price < min || price > max)
            return say(p, "&cThe price has to be between " + Util.money(min) + " and " + Util.money(max) + ".");
        int limit = getConfig().getInt("max-listings", 10);
        if (!p.hasPermission("darkah.unlimited") && ah.of(p.getUniqueId()).size() >= limit)
            return say(p, "&cYou can only sell " + limit + " items at once. Take one back in /ah > Your items.");
        if (hand.hasItemMeta() && hand.getItemMeta() != null && hand.getItemMeta().getPersistentDataContainer()
                .has(new org.bukkit.NamespacedKey("darkessentials", "starter"), org.bukkit.persistence.PersistentDataType.BYTE))
            return say(p, "&cStarter items can't be sold on the auction house.");
        for (String banned : getConfig().getStringList("blocked-items"))
            if (hand.getType().name().equalsIgnoreCase(banned)) return say(p, "&cThat item can't be sold here.");
        ItemStack item = hand.clone();
        p.getInventory().setItemInMainHand(null);
        Listing l = ah.list(p.getUniqueId(), p.getName(), item, price);
        say(p, "&aListed &f" + itemName(item) + " &afor &f" + Util.money(price) + "&a. It expires in "
                + AhMenus.time(l.expires - System.currentTimeMillis()) + ".");
        if (getConfig().getBoolean("broadcast-listings", false))
            Bukkit.broadcastMessage(Util.color("&d" + p.getName() + " &7is selling &f" + itemName(item) + " &7for &a" + Util.money(price)));
        return true;
    }

    /** Lists, browses, buys and expires with two made-up players. Used by MC Hoster's server test. */
    private void test(CommandSender s) {
        List<String> fails = new ArrayList<>();
        String skipped = null;
        eco.hook();
        UUID seller = UUID.nameUUIDFromBytes("darkah-test-seller".getBytes());
        UUID buyer = UUID.nameUUIDFromBytes("darkah-test-buyer".getBytes());
        try {
            if (!eco.ready()) throw new IllegalStateException("no economy (" + eco.mode() + ")");
            double sellerStart = eco.balance(seller);
            // Other economies (EssentialsX...) don't make accounts for made-up players, so money is only tested with Dark Shop.
            boolean money = eco.give(buyer, 500);
            if (!money && eco.mode().contains("DarkShop") || !money && eco.mode().equals("Dark Shop"))
                fails.add("could not give the test buyer money");
            if (!money) skipped = "money steps skipped, " + eco.mode() + " has no accounts for test players";
            double buyerStart = eco.balance(buyer);
            Listing l = ah.list(seller, "TestSeller", new ItemStack(Material.DIAMOND, 3), 100);
            if (ah.browse(Sort.RECENT, Filter.INGREDIENTS, "").stream().noneMatch(x -> x.id.equals(l.id))) fails.add("not under Ingredients");
            if (ah.browse(Sort.RECENT, Filter.BLOCKS, "").stream().anyMatch(x -> x.id.equals(l.id))) fails.add("wrongly under Blocks");
            if (ah.browse(Sort.LOWEST, Filter.ALL, "diam").stream().noneMatch(x -> x.id.equals(l.id))) fails.add("search failed");
            for (Sort so : Sort.values())
                for (Filter f : Filter.values()) {
                    AhMenus.View v = menus.view(buyer);
                    v.sort = so;
                    v.filter = f;
                    menus.browse(buyer);
                }
            menus.view(buyer).filter = Filter.ALL;
            menus.view(buyer).search = "";
            if (menus.browse(buyer).getInventory().getItem(0) == null) fails.add("browse menu is empty");
            menus.confirm(l);
            menus.yours(seller);
            if (ah.buy(seller, l) != AuctionHouse.Result.OWN) fails.add("could buy own item");
            if (money) {
                AuctionHouse.Result r = ah.buy(buyer, l);
                if (r != AuctionHouse.Result.OK) fails.add("buy: " + r);
                if (ah.get(l.id) != null) fails.add("listing still there after buying");
                if (ah.buy(buyer, l) != AuctionHouse.Result.GONE) fails.add("bought twice");
                double tax = getConfig().getDouble("tax-percent", 0);
                if (Math.abs(eco.balance(buyer) - (buyerStart - 100)) > 1e-6) fails.add("buyer balance " + eco.balance(buyer));
                if (Math.abs(eco.balance(seller) - (sellerStart + 100 * (100 - tax) / 100)) > 1e-6) fails.add("seller balance " + eco.balance(seller));
                if (ah.claims(buyer).stream().noneMatch(i -> i.getType() == Material.DIAMOND && i.getAmount() == 3))
                    fails.add("offline buyer didn't get the diamonds to collect");
                double[] away = ah.takeAway(seller);
                if (away == null || away[0] != 1 || Math.abs(away[1] - 100 * (100 - tax) / 100) > 1e-6) fails.add("offline seller's sale not remembered");
                if (ah.takeAway(seller) != null) fails.add("away sales shown twice");
            } else {
                if (ah.buy(buyer, l) != AuctionHouse.Result.NO_MONEY) fails.add("bought without money");
                ah.cancel(l);
                if (ah.claims(seller).stream().noneMatch(i -> i.getType() == Material.DIAMOND)) fails.add("taking back didn't return it");
            }
            Listing old = ah.list(seller, "TestSeller", new ItemStack(Material.OAK_LOG, 16), 5);
            old.expires = System.currentTimeMillis() - 1;
            ah.expire();
            if (ah.get(old.id) != null || ah.claims(seller).stream().noneMatch(i -> i.getType() == Material.OAK_LOG))
                fails.add("expired listing not returned");
            if (!Util.money(1500).equals("$1.5K") || Util.parseMoney("2m") != 2_000_000) fails.add("money text");
            ah.forgetSales(Material.BEDROCK);
            for (double v : new double[]{10, 1000, 30, 20, 40}) ah.recordSale(Material.BEDROCK, v);
            if (ah.auctionValue(Material.BEDROCK) != 30) fails.add("auction value " + ah.auctionValue(Material.BEDROCK));
            ah.forgetSales(Material.BEDROCK);
            if (money) {
                eco.take(buyer, eco.balance(buyer));
                eco.take(seller, eco.balance(seller) - sellerStart);
            }
        } catch (Throwable t) {
            fails.add(t.toString());
            getLogger().log(java.util.logging.Level.WARNING, "test failed", t);
        } finally {
            ah.clearClaims(seller);
            ah.clearClaims(buyer);
            for (Listing l : ah.of(seller)) ah.remove(l.id);
            ah.save();
        }
        String result = fails.isEmpty() ? "DARKAH TEST OK (money: " + eco.mode() + (skipped != null ? "; " + skipped : "") + ")" : "DARKAH TEST FAIL: " + fails;
        getLogger().info(result);
        if (!(s instanceof org.bukkit.command.ConsoleCommandSender)) s.sendMessage(result);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(cmd.getName().equalsIgnoreCase("darkah") ? List.of("reload", "test") : List.of("sell"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}
