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
    public final Map<String, String> vows = new LinkedHashMap<>();   // binding vows: id -> parameters
    Vows.State vowState;      // cache of what the vows add up to (null = recompute)
    public String ult;        // chosen ultimate id (null = none)
    public int ultSlot;       // ability slot (1 or 2) the ultimate replaced
    public String ultSac = "";   // sacrificed passive slots, e.g. "1,3"

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

    /** Cooldown that only starts recharging once the ability has ended (activeSec seconds from now). */
    void startCdAfter(int n, double activeSec, double cdSec) {
        long now = System.currentTimeMillis(), act = (long) (activeSec * 1000), c = (long) (cdSec * 1000);
        cd.put("a" + n, new long[]{now + act + c, act + c});
        if (act > 0) cd.put("act" + n, new long[]{now + act, act});
        else cd.remove("act" + n);
    }

    boolean active(int n) {
        long[] a = cd.get("act" + n);
        return a != null && a[0] > System.currentTimeMillis();
    }

    /** The ability ended early: its cooldown starts recharging now. */
    void endActive(int n) {
        long[] a = cd.remove("act" + n);
        if (a == null) return;
        long left = a[0] - System.currentTimeMillis();
        if (left <= 0) return;
        long[] c = cd.get("a" + n);
        if (c != null) { c[0] -= left; c[1] -= left; }
    }

    double remaining(int n) { return remKey("a" + n); }

    /** 0..1, 1 = ready. */
    double fraction(int n) {
        if (active(n)) return 0;   // nothing recharges while the ability is still running
        long[] c = cd.get("a" + n);
        if (c == null || c[1] <= 0) return 1;
        long rem = c[0] - System.currentTimeMillis();
        if (rem <= 0) return 1;
        return 1.0 - (double) rem / c[1];
    }

    public boolean trusts(UUID u) { return trusted.contains(u); }

    public boolean trusts(Player p) { return trusted.contains(p.getUniqueId()); }
}
