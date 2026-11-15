package dev.darkerdawnfr.darkshop;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Dark Shop's balances as a Vault Economy, built at runtime (so Vault is not needed to compile or run).
 * Handles every method of net.milkbowl.vault.economy.Economy, including the old String-name and world overloads.
 */
final class VaultProvider implements InvocationHandler {
    private final Money money;

    VaultProvider(Money money) {
        this.money = money;
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        String n = m.getName();
        Object[] a = args == null ? new Object[0] : args;
        switch (n) {
            case "equals": return proxy == a[0];
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return "DarkShop economy";
            case "isEnabled": return true;
            case "getName": return "DarkShop";
            case "hasBankSupport": return false;
            case "fractionalDigits": return 2;
            case "format": return Util.money(((Number) a[0]).doubleValue());
            case "currencyNamePlural": return "Dollars";
            case "currencyNameSingular": return "Dollar";
            case "hasAccount":
            case "createPlayerAccount": return true;
            case "getBanks": return new ArrayList<String>();
            default: break;
        }
        UUID id = a.length > 0 ? player(a[0]) : null;
        double amount = a.length > 0 && a[a.length - 1] instanceof Number num ? num.doubleValue() : 0;
        switch (n) {
            case "getBalance": return id == null ? 0.0 : money.balance(id);
            case "has": return id != null && money.balance(id) + 1e-9 >= amount;
            case "withdrawPlayer": {
                if (id == null) return response(m, amount, 0, "FAILURE", "Unknown player");
                if (amount < 0) return response(m, 0, money.balance(id), "FAILURE", "Can't take a negative amount");
                boolean ok = money.take(id, amount);
                return response(m, amount, money.balance(id), ok ? "SUCCESS" : "FAILURE", ok ? null : "Not enough money");
            }
            case "depositPlayer": {
                if (id == null) return response(m, amount, 0, "FAILURE", "Unknown player");
                if (amount < 0) return response(m, 0, money.balance(id), "FAILURE", "Can't give a negative amount");
                money.give(id, amount);
                return response(m, amount, money.balance(id), "SUCCESS", null);
            }
            default:
                // banks are not supported
                if (m.getReturnType() == boolean.class) return false;
                if (m.getReturnType().getSimpleName().equals("EconomyResponse"))
                    return response(m, 0, 0, "NOT_IMPLEMENTED", "Dark Shop has no banks");
                return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static UUID player(Object o) {
        if (o instanceof OfflinePlayer p) return p.getUniqueId();
        if (o instanceof String s && !s.isEmpty()) return Bukkit.getOfflinePlayer(s).getUniqueId();
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object response(Method m, double amount, double balance, String type, String error) throws Exception {
        Class<?> resp = m.getReturnType();
        Class typeClass = Class.forName(resp.getName() + "$ResponseType", true, resp.getClassLoader());
        Object t = Enum.valueOf(typeClass, type);
        Constructor<?> c = resp.getConstructor(double.class, double.class, typeClass, String.class);
        return c.newInstance(amount, balance, t, error);
    }
}
