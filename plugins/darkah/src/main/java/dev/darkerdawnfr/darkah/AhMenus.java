package dev.darkerdawnfr.darkah;

import dev.darkerdawnfr.darkah.AuctionHouse.Filter;
import dev.darkerdawnfr.darkah.AuctionHouse.Listing;
import dev.darkerdawnfr.darkah.AuctionHouse.Sort;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The DonutSMP-style /ah menus: browse (with sort, filter, search), confirm purchase, and your items. */
public class AhMenus {
    /** What each player last looked at, so sort/filter/search stay the same when they come back. */
    public static final class View {
        public int page;
        public Sort sort = Sort.RECENT;
        public Filter filter = Filter.ALL;
        public String search = "";
    }

    private final DarkAH plugin;
    private final Map<UUID, View> views = new HashMap<>();

    public AhMenus(DarkAH plugin) {
        this.plugin = plugin;
    }

    public View view(UUID who) {
        return views.computeIfAbsent(who, k -> new View());
    }

    private static final int PER_PAGE = 45;

    public Menu browse(UUID viewer) {
        View v = view(viewer);
        AuctionHouse ah = plugin.ah();
        List<Listing> list = ah.browse(v.sort, v.filter, v.search);
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        v.page = Math.max(0, Math.min(v.page, pages - 1));
        String title = "&8" + Util.small("Auction") + " &8(" + (v.page + 1) + "/" + pages + ")";
        if (!v.search.isEmpty()) title = "&8" + Util.small("Search") + ": " + v.search;
        Menu m = new Menu(6, title);
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = v.page * PER_PAGE + i;
            if (idx >= list.size()) break;
            Listing l = list.get(idx);
            boolean own = l.seller.equals(viewer);
            m.set(i, display(l, own ? "&cClick to take it back" : "&eClick to buy"), e -> {
                Player p = (Player) e.getWhoClicked();
                if (ah.get(l.id) == null) {
                    p.sendMessage(plugin.msg("gone"));
                    browse(viewer).open(p);
                } else if (own) {
                    ah.cancel(l);
                    p.sendMessage(plugin.msg("removed").replace("{item}", DarkAH.itemName(l.item)));
                    browse(viewer).open(p);
                } else {
                    confirm(l).open(p);
                }
            });
        }
        for (int s = 45; s < 54; s++) m.set(s, Util.glass());
        if (v.page > 0)
            m.set(45, Util.item(Material.ARROW, "&fPrevious page", "&7Page " + v.page), e -> {
                v.page--;
                browse(viewer).open((Player) e.getWhoClicked());
            });
        if (v.page < pages - 1)
            m.set(53, Util.item(Material.ARROW, "&fNext page", "&7Page " + (v.page + 2)), e -> {
                v.page++;
                browse(viewer).open((Player) e.getWhoClicked());
            });
        List<String> sortLore = new ArrayList<>();
        for (Sort s : Sort.values()) sortLore.add((s == v.sort ? "&a» " : "&7") + s.label);
        sortLore.add("");
        sortLore.add("&eClick to change");
        m.set(47, Util.item(Material.CAULDRON, 1, "&fSort", sortLore), e -> {
            Sort[] all = Sort.values();
            int d = e.getClick() == ClickType.RIGHT ? -1 : 1;
            v.sort = all[(v.sort.ordinal() + d + all.length) % all.length];
            v.page = 0;
            click(e.getWhoClicked());
            browse(viewer).open((Player) e.getWhoClicked());
        });
        List<String> filterLore = new ArrayList<>();
        for (Filter f : Filter.values()) filterLore.add((f == v.filter ? "&a» " : "&7") + f.label);
        filterLore.add("");
        filterLore.add("&eClick to change");
        m.set(48, Util.item(Material.HOPPER, 1, "&fFilter", filterLore), e -> {
            Filter[] all = Filter.values();
            int d = e.getClick() == ClickType.RIGHT ? -1 : 1;
            v.filter = all[(v.filter.ordinal() + d + all.length) % all.length];
            v.page = 0;
            click(e.getWhoClicked());
            browse(viewer).open((Player) e.getWhoClicked());
        });
        m.set(49, Util.item(Material.ANVIL, "&fRefresh", "&7Show the newest listings"), e -> {
            click(e.getWhoClicked());
            browse(viewer).open((Player) e.getWhoClicked());
        });
        m.set(50, Util.item(Material.OAK_SIGN, "&fSearch",
                v.search.isEmpty() ? "&7Click and type in chat" : "&7Now: &f" + v.search,
                v.search.isEmpty() ? "" : "&7Right-click to clear"), e -> {
            Player p = (Player) e.getWhoClicked();
            if (e.getClick() == ClickType.RIGHT && !v.search.isEmpty()) {
                v.search = "";
                v.page = 0;
                browse(viewer).open(p);
                return;
            }
            p.closeInventory();
            plugin.askSearch(p);
        });
        int mine = ah.of(viewer).size(), claim = ah.claims(viewer).size();
        m.set(51, Util.item(Material.CHEST, "&fYour items", "&7Selling: &f" + mine,
                claim > 0 ? "&aTo collect: &f" + claim : "&7Nothing to collect"), e -> yours(viewer).open((Player) e.getWhoClicked()));
        return m;
    }

    public Menu confirm(Listing l) {
        Menu m = new Menu(3, "&8" + Util.small("Confirm purchase"));
        m.set(13, display(l, null));
        m.set(11, Util.item(Material.LIME_STAINED_GLASS_PANE, "&a&lConfirm", "&7Buy for &a" + Util.money(l.price)), e -> {
            Player p = (Player) e.getWhoClicked();
            AuctionHouse.Result r = plugin.ah().buy(p.getUniqueId(), l);
            switch (r) {
                case OK -> {
                    p.sendMessage(plugin.msg("bought").replace("{item}", DarkAH.itemName(l.item)).replace("{price}", Util.money(l.price)));
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
                    browse(p.getUniqueId()).open(p);
                }
                case GONE -> {
                    p.sendMessage(plugin.msg("gone"));
                    browse(p.getUniqueId()).open(p);
                }
                case OWN -> p.sendMessage(plugin.msg("own"));
                case NO_ECONOMY -> p.sendMessage(plugin.msg("no-economy"));
                case NO_MONEY -> {
                    p.sendMessage(plugin.msg("not-enough").replace("{price}", Util.money(l.price)));
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                }
            }
        });
        m.set(15, Util.item(Material.RED_STAINED_GLASS_PANE, "&c&lCancel", "&7Back to the auction house"),
                e -> browse(e.getWhoClicked().getUniqueId()).open((Player) e.getWhoClicked()));
        m.fill();
        return m;
    }

    public Menu yours(UUID who) {
        AuctionHouse ah = plugin.ah();
        Menu m = new Menu(6, "&8" + Util.small("Your items"));
        int slot = 0;
        for (Listing l : ah.of(who)) {
            if (slot >= 45) break;
            m.set(slot++, display(l, "&cClick to take it back"), e -> {
                Player p = (Player) e.getWhoClicked();
                if (ah.cancel(l)) p.sendMessage(plugin.msg("removed").replace("{item}", DarkAH.itemName(l.item)));
                yours(who).open(p);
            });
        }
        List<ItemStack> claims = ah.claims(who);
        for (int i = 0; i < claims.size() && slot < 45; i++) {
            ItemStack it = claims.get(i);
            ItemStack shown = it.clone();
            addLore(shown, List.of("", "&aNot sold - click to collect"));
            m.set(slot++, shown, e -> {
                Player p = (Player) e.getWhoClicked();
                if (!ah.claims(who).remove(it)) return;
                ah.deliver(who, it);
                yours(who).open(p);
            });
        }
        for (int s = 45; s < 54; s++) m.set(s, Util.glass());
        m.set(49, Util.item(Material.ARROW, "&fBack", "&7Back to the auction house"),
                e -> browse(who).open((Player) e.getWhoClicked()));
        m.set(53, Util.item(Material.BOOK, "&fHow to sell", "&7Hold an item and type", "&f/ah sell <price>", "&7e.g. /ah sell 1.5k"));
        return m;
    }

    /** The listing's item with price, seller and time left added to its lore. */
    public ItemStack display(Listing l, String action) {
        ItemStack it = l.item.clone();
        List<String> extra = new ArrayList<>(List.of("", "&7Price: &a" + Util.money(l.price), "&7Seller: &f" + l.sellerName,
                "&7Expires in: &f" + time(l.expires - System.currentTimeMillis())));
        double worth = worth(l.item);
        if (worth > 0) extra.add(3, "&7/sell value: &e" + Util.money(worth * l.item.getAmount()));
        if (action != null) {
            extra.add("");
            extra.add(action);
        }
        addLore(it, extra);
        return it;
    }

    /** What one item sells for in Dark Shop's /sell (0 if not sellable or Dark Shop isn't installed). */
    public static double worth(ItemStack it) {
        org.bukkit.plugin.Plugin shop = org.bukkit.Bukkit.getPluginManager().getPlugin("DarkShop");
        if (shop == null || !shop.isEnabled() || it.hasItemMeta() && it.getItemMeta() != null && (it.getItemMeta().hasDisplayName() || it.getItemMeta().hasEnchants())) return 0;
        try {
            Object w = shop.getClass().getMethod("worth", String.class).invoke(shop, it.getType().name());
            return w instanceof Number n ? n.doubleValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void addLore(ItemStack it, List<String> lines) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        for (String s : lines) lore.add(Util.color(s));
        meta.setLore(lore);
        it.setItemMeta(meta);
    }

    public static String time(long ms) {
        if (ms <= 0) return "now";
        long m = ms / 60000, h = m / 60, d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (m % 60) + "m";
        return Math.max(1, m) + "m";
    }

    private static void click(org.bukkit.entity.HumanEntity p) {
        if (p instanceof Player pl) pl.playSound(pl.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1f);
    }
}
