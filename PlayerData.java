package dev.aegis;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class PlayerData {
    public ClassType type;
    public int energy;
    public boolean cmdMode;
    public final Set<UUID> trusted = new HashSet<>();

    final Map<String, long[]> cd = new HashMap<>();          // key -> {endMillis, totalMillis}
    final Set<PotionEffectType> applied = new HashSet<>();   // permanent effects we applied
    ItemStack savedOffhand;
    int lastAbility;          // which ability was used last (for armor-damage / armor-repair keys)
    boolean rolling;          // slot-machine animation running
    ClassType pending;        // class being rolled (not revealed until the roll ends)                                  // Samurai dual wield

    void startKey(String key, double sec) {
        long ms = (long) (sec * 1000);
        cd.put(key, new long[]{System.currentTimeMillis() + ms, ms});
    }

    double remKey(String key) {
        long[] c = cd.get(key);
        return c == null ? 0 : Math.max(0, (c[0] - System.currentTimeMillis()) / 1000.0);
    }

    void startCd(int n, double sec) { startKey("a" + n, sec); }

    double remaining(int n) { return remKey("a" + n); }

    /** 0..1, 1 = ready. */
    double fraction(int n) {
        long[] c = cd.get("a" + n);
        if (c == null || c[1] <= 0) return 1;
        long rem = c[0] - System.currentTimeMillis();
        if (rem <= 0) return 1;
        return 1.0 - (double) rem / c[1];
    }

    public boolean trusts(UUID u) { return trusted.contains(u); }

    public boolean trusts(Player p) { return trusted.contains(p.getUniqueId()); }
}
