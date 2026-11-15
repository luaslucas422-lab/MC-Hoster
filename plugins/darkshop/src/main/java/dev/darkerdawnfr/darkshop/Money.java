package dev.darkerdawnfr.darkshop;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Money for the shop, /sell and Dark AH.
 *
 * If another economy (EssentialsX, CMI...) is registered with Vault, that one is used.
 * Otherwise Dark Shop keeps its own balances (balances.yml) and, when Vault is installed,
 * offers them to every other plugin through Vault. Vault is optional and only used by reflection.
 */
public class Money {
    private static final String VAULT_ECON = "net.milkbowl.vault.economy.Economy";

    private final DarkShop plugin;
    private final File file;
    private final Map<UUID, Double> balances = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private boolean dirty;
    private Object external;   // another plugin's Vault Economy
    private Object ours;       // our Vault Economy proxy, if registered

    public Money(DarkShop plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "balances.yml");
        load();
    }

    // ------------------------------------------------------------------ setup

    /** Call on enable and again once the server has started (other economies register late). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void hook() {
        Class<?> econ = vaultClass();
        if (econ == null) {
            external = null;
            return;
        }
        try {
            if (ours == null) {
                ours = Proxy.newProxyInstance(econ.getClassLoader(), new Class[]{econ}, new VaultProvider(this));
                Bukkit.getServicesManager().register((Class) econ, ours, plugin, ServicePriority.Low);
            }
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(econ);
            Object best = rsp == null ? null : rsp.getProvider();
            external = best != null && best != ours ? best : null;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not connect to Vault: " + t);
            external = null;
        }
    }

    public void unhook() {
        if (ours != null) {
            try {
                Bukkit.getServicesManager().unregister(ours);
            } catch (Throwable ignored) {
            }
            ours = null;
        }
        save();
    }

    private static Class<?> vaultClass() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null && Bukkit.getPluginManager().getPlugin("VaultUnlocked") == null)
            return null;
        try {
            return Class.forName(VAULT_ECON);
        } catch (Throwable t) {
            return null;
        }
    }

    public String mode() {
        if (external != null) return "external (" + call(external, "getName") + ")";
        return ours != null ? "built-in, shared through Vault" : "built-in";
    }

    public boolean builtIn() {
        return external == null;
    }

    // ------------------------------------------------------------------ public API (also used by Dark AH)

    public double balance(UUID id) {
        if (external != null) {
            Object r = call(external, "getBalance", Bukkit.getOfflinePlayer(id));
            return r instanceof Number n ? n.doubleValue() : 0;
        }
        Double b = balances.get(id);
        return b != null ? b : plugin.getConfig().getDouble("start-balance", 0);
    }

    /** Takes money. False (and nothing taken) if they don't have enough. */
    public boolean take(UUID id, double amount) {
        if (amount < 0 || !Double.isFinite(amount)) return false;
        if (external != null) {
            if (balance(id) + 1e-9 < amount) return false;
            return success(call(external, "withdrawPlayer", Bukkit.getOfflinePlayer(id), amount));
        }
        double b = balance(id);
        if (b + 1e-9 < amount) return false;
        set(id, b - amount);
        return true;
    }

    public boolean give(UUID id, double amount) {
        if (amount < 0 || !Double.isFinite(amount)) return false;
        if (external != null) return success(call(external, "depositPlayer", Bukkit.getOfflinePlayer(id), amount));
        set(id, balance(id) + amount);
        return true;
    }

    public void set(UUID id, double amount) {
        amount = Math.max(0, Math.round(amount * 100) / 100.0);
        if (external != null) {
            double b = balance(id);
            if (amount > b) give(id, amount - b);
            else if (amount < b) take(id, b - amount);
            return;
        }
        balances.put(id, amount);
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        if (p.getName() != null) names.put(id, p.getName());
        dirty = true;
    }

    /** Only for the built-in balances. */
    public List<Map.Entry<String, Double>> top(int n) {
        List<Map.Entry<UUID, Double>> all = new ArrayList<>(balances.entrySet());
        all.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        for (Map.Entry<UUID, Double> e : all) {
            if (out.size() >= n) break;
            String name = names.getOrDefault(e.getKey(), Bukkit.getOfflinePlayer(e.getKey()).getName());
            if (name == null) continue;
            out.add(Map.entry(name, e.getValue()));
        }
        return out;
    }

    /** Removes an account completely (used by the self test). */
    public void forget(UUID id) {
        if (balances.remove(id) != null) dirty = true;
        names.remove(id);
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                balances.put(id, y.getDouble(k + ".balance"));
                String n = y.getString(k + ".name");
                if (n != null) names.put(id, n);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        Map<UUID, Double> sorted = new LinkedHashMap<>(balances);
        for (Map.Entry<UUID, Double> e : sorted.entrySet()) {
            y.set(e.getKey() + ".balance", e.getValue());
            if (names.containsKey(e.getKey())) y.set(e.getKey() + ".name", names.get(e.getKey()));
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save balances: " + e);
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    static Object call(Object target, String name, Object... args) {
        for (Class<?> type : publicTypes(target.getClass())) {
            for (Method m : type.getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                Class<?>[] types = m.getParameterTypes();
                boolean fits = true;
                for (int i = 0; i < args.length; i++) {
                    Class<?> t = types[i].isPrimitive() ? box(types[i]) : types[i];
                    if (args[i] != null && !t.isInstance(args[i])) {
                        fits = false;
                        break;
                    }
                }
                if (!fits) continue;
                try {
                    return m.invoke(target, args);
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The class itself if public, then its public interfaces and superclasses (methods of a hidden class can't be called). */
    private static List<Class<?>> publicTypes(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        java.util.ArrayDeque<Class<?>> todo = new java.util.ArrayDeque<>();
        todo.add(c);
        while (!todo.isEmpty()) {
            Class<?> k = todo.poll();
            if (k == null || out.contains(k)) continue;
            if (java.lang.reflect.Modifier.isPublic(k.getModifiers())) out.add(k);
            todo.addAll(java.util.Arrays.asList(k.getInterfaces()));
            if (k.getSuperclass() != null && k.getSuperclass() != Object.class) todo.add(k.getSuperclass());
        }
        return out;
    }

    private static Class<?> box(Class<?> c) {
        if (c == double.class) return Double.class;
        if (c == int.class) return Integer.class;
        if (c == boolean.class) return Boolean.class;
        if (c == long.class) return Long.class;
        return c;
    }

    private static boolean success(Object response) {
        Object r = response == null ? null : call(response, "transactionSuccess");
        return Boolean.TRUE.equals(r);
    }
}
