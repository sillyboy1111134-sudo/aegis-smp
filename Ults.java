package dev.aegis;

import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Ultimates. At max energy a player may give up one ability and two passives for an ultimate (from ANY class).
 * The ultimate takes the place of the last ability (Azaroth's: the first). Below max energy the ultimate sleeps:
 * the normal ability is back, but the two passives stay lost. Choosing one is permanent (admins can remove it)
 * and excludes binding vows. The ultimates themselves are in UltImpl.
 */
final class Ults {
    interface Handler { boolean cast(Player p); }

    record Def(String id, String name, String desc, double cooldown, double active, boolean first, boolean passive, Handler handler, Handler recast) { }

    static final Map<String, Def> REGISTRY = new LinkedHashMap<>();

    static void register(String id, String name, String desc, double cooldown, double active, boolean first, boolean passive, Handler handler, Handler recast) {
        REGISTRY.put(id, new Def(id, name, desc, cooldown, active, first, passive, handler, recast));
    }

    static void init() { UltImpl.init(); }

    static double cooldown(Def def) { return Aegis.I.getConfig().getDouble("ults." + def.id() + ".cooldown", def.cooldown()); }

    /** The slot an ultimate goes into for this class: the last ability, or the first for "first" ultimates. */
    static int slotFor(ClassType t, Def def) { return def.first() ? 1 : (t.a2 != null ? 2 : 1); }

    /** The ability slot the ultimate currently replaces; 0 while the player is below max energy (the normal ability is back). */
    static int slot(Player p) {
        PlayerData d = Aegis.I.pd(p);
        if (d.ult == null || d.energy < Aegis.I.maxEnergy()) return 0;
        return d.ultSlot;
    }

    /** Passives given up for the ultimate (stored as slots, so they survive a class reroll). Stay lost even below max energy. */
    static Set<String> sacPassives(Player p) {
        Set<String> r = new HashSet<>();
        PlayerData d = Aegis.I.pd(p);
        if (d.ult == null || d.type == null) return r;
        for (String tok : d.ultSac.split(",")) {
            String id = Vows.pidOf(d.type, tok);
            if (id != null) r.add(id);
        }
        return r;
    }

    static void cast(Player p, int n) {
        PlayerData d = Aegis.I.pd(p);
        Def def = REGISTRY.get(d.ult);
        if (def == null) { Abilities.msg(p, "Your ultimate is not available."); return; }
        if (def.passive()) { Abilities.msg(p, def.name() + " triggers by itself."); return; }
        if (def.recast() != null && UltImpl.isActive(p, def.id())) { def.recast().cast(p); return; }
        if (d.active(n)) { Abilities.msg(p, def.name() + " is still active."); return; }
        if (d.remaining(n) > 0) { Abilities.msg(p, def.name() + " is on cooldown (" + String.format("%.1f", d.remaining(n)) + "s)"); return; }
        if (def.handler().cast(p)) {
            UltImpl.markActive(p, def.id(), def.active());
            d.startCdAfter(n, def.active(), cooldown(def));   // recharges once the ultimate has ended
            UltFx.cast(p, def);   // the big show
        }
    }

    static String usage() { return "/ult take <ultimate> <passive> <passive>"; }

    static Vows.Prep prepare(Player p, String id, String[] a) {
        PlayerData d = Aegis.I.pd(p);
        ClassType t = d.type;
        if (t == null || d.rolling) return Vows.Prep.err("You need a class first.");
        if (!Aegis.I.getConfig().getBoolean("ults.enabled", true)) return Vows.Prep.err("Ultimates are disabled.");
        if (d.ult != null) return Vows.Prep.err("You already chose an ultimate.");
        if (!d.vows.isEmpty()) return Vows.Prep.err("You have binding vows. Ultimates can't be combined with them.");
        int need = Aegis.I.maxEnergy();
        if (d.energy < need) return Vows.Prep.err("You need " + need + " energy to choose an ultimate.");
        Def def = REGISTRY.get(id);
        if (def == null) return Vows.Prep.err("Unknown ultimate. Use /ult list.");
        if (a.length < 2) return Vows.Prep.err("Usage: " + usage());
        String p1 = a[0].toLowerCase(), p2 = a[1].toLowerCase();
        if (p1.equals(p2)) return Vows.Prep.err("Pick two different passives.");
        for (String pid : new String[]{p1, p2})
            if (!Passives.unlockedRaw(p).contains(pid)) return Vows.Prep.err("'" + pid + "' is not one of your unlocked passives.");
        int slot = slotFor(t, def);
        return Vows.Prep.ok(slot + ";" + Vows.idx(t, p1) + "," + Vows.idx(t, p2),
                "You permanently lose " + t.abilityName(slot) + " (while you have max energy), " + Vows.passiveName(t, p1) + " and " + Vows.passiveName(t, p2)
                        + ". " + def.name() + " takes the place of " + t.abilityName(slot) + ". Below max energy your ability returns but the passives stay lost. You can no longer take binding vows.");
    }

    static void apply(Player p, String id, String param) {
        PlayerData d = Aegis.I.pd(p);
        String[] parts = param.split(";");
        d.ult = id;
        d.ultSlot = Integer.parseInt(parts[0]);
        d.ultSac = parts.length > 1 ? parts[1] : "";
        d.cd.remove("a" + d.ultSlot);
        Passives.refresh(p);
        Aegis.I.saveData();
    }

    static void remove(Player p) {
        PlayerData d = Aegis.I.pd(p);
        d.cd.remove("a" + d.ultSlot);
        d.ult = null;
        d.ultSlot = 0;
        d.ultSac = "";
        Passives.refresh(p);
        Aegis.I.saveData();
    }
}
