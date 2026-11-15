package dev.darkerdawnfr.darkah;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Money for the auction house: any Vault economy (Dark Shop, EssentialsX, CMI...),
 * or Dark Shop directly when Vault is not installed. Everything by reflection, so neither is needed to compile.
 */
public class Eco {
    private Object vault;
    private Plugin darkShop;

    public void hook() {
        vault = null;
        darkShop = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") != null || Bukkit.getPluginManager().getPlugin("VaultUnlocked") != null) {
            try {
                Class<?> econ = Class.forName("net.milkbowl.vault.economy.Economy");
                RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(econ);
                vault = rsp == null ? null : rsp.getProvider();
            } catch (Throwable ignored) {
            }
        }
        Plugin ds = Bukkit.getPluginManager().getPlugin("DarkShop");
        if (ds != null && ds.isEnabled()) darkShop = ds;
    }

    public boolean ready() {
        return vault != null || darkShop != null;
    }

    public String mode() {
        if (vault != null) return "Vault (" + call(vault, "getName") + ")";
        if (darkShop != null) return "Dark Shop";
        return "none: install Dark Shop, or Vault and an economy plugin";
    }

    public double balance(UUID id) {
        Object r = vault != null ? call(vault, "getBalance", Bukkit.getOfflinePlayer(id))
                : darkShop != null ? call(darkShop, "balance", id) : null;
        return r instanceof Number n ? n.doubleValue() : 0;
    }

    public boolean take(UUID id, double amount) {
        if (amount < 0 || !Double.isFinite(amount)) return false;
        if (vault != null) {
            if (balance(id) + 1e-9 < amount) return false;
            Object r = call(vault, "withdrawPlayer", Bukkit.getOfflinePlayer(id), amount);
            return r != null && Boolean.TRUE.equals(call(r, "transactionSuccess"));
        }
        return darkShop != null && Boolean.TRUE.equals(call(darkShop, "take", id, amount));
    }

    public boolean give(UUID id, double amount) {
        if (amount < 0 || !Double.isFinite(amount)) return false;
        if (vault != null) {
            Object r = call(vault, "depositPlayer", Bukkit.getOfflinePlayer(id), amount);
            return r != null && Boolean.TRUE.equals(call(r, "transactionSuccess"));
        }
        return darkShop != null && Boolean.TRUE.equals(call(darkShop, "give", id, amount));
    }

    static Object call(Object target, String name, Object... args) {
        for (Class<?> type : publicTypes(target.getClass())) {
            for (Method m : type.getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                Class<?>[] types = m.getParameterTypes();
                boolean fits = true;
                for (int i = 0; i < args.length; i++) {
                    Class<?> t = types[i] == double.class ? Double.class : types[i];
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

    private static List<Class<?>> publicTypes(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        ArrayDeque<Class<?>> todo = new ArrayDeque<>();
        todo.add(c);
        while (!todo.isEmpty()) {
            Class<?> k = todo.poll();
            if (k == null || out.contains(k)) continue;
            if (Modifier.isPublic(k.getModifiers())) out.add(k);
            todo.addAll(Arrays.asList(k.getInterfaces()));
            if (k.getSuperclass() != null && k.getSuperclass() != Object.class) todo.add(k.getSuperclass());
        }
        return out;
    }
}
