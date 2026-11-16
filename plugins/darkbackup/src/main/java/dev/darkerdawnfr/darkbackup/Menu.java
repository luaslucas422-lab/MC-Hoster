package dev.darkerdawnfr.darkbackup;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest menu. Items can't be taken out unless the menu is editable (/sell).
 * Note: InventoryView is never used, it changed from a class to an interface in 1.21.
 */
public class Menu implements InventoryHolder {
    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();
    public boolean editable;
    public Consumer<Player> onClose;

    public Menu(int rows, String title) {
        inv = Bukkit.createInventory(this, rows * 9, Util.color(title));
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public int size() {
        return inv.getSize();
    }

    public void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, item);
        if (action == null) actions.remove(slot);
        else actions.put(slot, action);
    }

    public void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    public void border() {
        int rows = inv.getSize() / 9;
        for (int i = 0; i < inv.getSize(); i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) inv.setItem(i, Util.glass());
        }
    }

    public void fill() {
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, Util.glass());
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    public static class Events implements Listener {
        @EventHandler(ignoreCancelled = false)
        public void click(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof Menu m)) return;
            if (m.editable) return;
            e.setCancelled(true);
            if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
            Consumer<InventoryClickEvent> a = m.actions.get(e.getRawSlot());
            if (a != null) a.accept(e);
        }

        @EventHandler
        public void drag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof Menu m && !m.editable) e.setCancelled(true);
        }

        @EventHandler
        public void close(InventoryCloseEvent e) {
            if (e.getInventory().getHolder() instanceof Menu m && m.onClose != null && e.getPlayer() instanceof Player p) {
                Consumer<Player> c = m.onClose;
                m.onClose = null;
                c.accept(p);
            }
        }
    }
}
