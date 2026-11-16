package dev.darkerdawnfr.darkcrates;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** "Type it in chat": the next chat message of that player goes to the editor instead of chat. */
public final class Ask implements Listener {
    private final Plugin plugin;
    private final Map<UUID, Consumer<String>> waiting = new ConcurrentHashMap<>();

    public Ask(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Closes the menu, asks, and runs then(text) on the main thread. "cancel" runs nothing; after runs either way. */
    public void ask(Player p, String what, Consumer<String> then, Runnable after) {
        p.closeInventory();
        waiting.put(p.getUniqueId(), t -> {
            if (!t.trim().equalsIgnoreCase("cancel")) then.accept(t);
            else p.sendMessage(Util.color("&7Nothing changed."));
            if (after != null) after.run();
        });
        p.sendMessage(Util.color("&e✎ &7Type " + what + " in chat &8(or cancel)"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncPlayerChatEvent e) {
        Consumer<String> c = waiting.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String text = e.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getPlayer().isOnline()) c.accept(text);
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        waiting.remove(e.getPlayer().getUniqueId());
    }
}
