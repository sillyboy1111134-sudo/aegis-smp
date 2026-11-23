package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ultimates. Every class has exactly ONE ultimate (its own); switching class switches the ultimate too.
 * An Awakening Stone (used at max energy) awakens it for good. It costs nothing: no passives are lost.
 * While you are at max energy the ultimate takes the place of one ability (see slotFor); below max energy the
 * normal ability is back. Passive ultimates (Berserker, Fateweaver) replace nothing and just trigger by themselves.
 * The ultimates themselves are in UltImpl.
 */
final class Ults {
    interface Handler { boolean cast(Player p); }

    /** first = replaces the first ability (otherwise the last one). passive = triggers by itself, replaces no ability. */
    record Def(String id, String name, String desc, double cooldown, double active, boolean first, boolean passive, Handler handler, Handler recast) { }

    static final Map<String, Def> REGISTRY = new LinkedHashMap<>();
    /** class -> id of its ultimate */
    static final Map<ClassType, String> OF = new EnumMap<>(ClassType.class);

    static void register(String id, String name, String desc, double cooldown, double active, boolean first, boolean passive, Handler handler, Handler recast) {
        REGISTRY.put(id, new Def(id, name, desc, cooldown, active, first, passive, handler, recast));
    }

    static void init() { UltImpl.init(); }

    static double cooldown(Def def) { return Aegis.I.getConfig().getDouble("ults." + def.id() + ".cooldown", def.cooldown()); }

    /** The ultimate of this class (whether or not the player has awakened it). */
    static Def of(ClassType t) { return t == null ? null : REGISTRY.get(OF.get(t)); }

    /** The player's ultimate once awakened, otherwise null. */
    static Def def(Player p) {
        PlayerData d = Aegis.I.pd(p);
        if (!d.awakened || d.type == null || !Aegis.I.getConfig().getBoolean("ults.enabled", true)) return null;
        return of(d.type);
    }

    /** The ability slot the ultimate takes over for this class (0 = none, for passive ultimates). Dolus has no second ability: the ultimate IS one. */
    static int slotFor(ClassType t, Def def) {
        if (def == null || def.passive()) return 0;
        if (t == ClassType.DOLUS) return 2;
        return def.first() ? 1 : (t.a2 != null ? 2 : 1);
    }

    /** The slot of the player's awakened ultimate whether or not it is currently usable (0 if none). */
    static int slotOf(Player p) {
        PlayerData d = Aegis.I.pd(p);
        return slotFor(d.type, def(p));
    }

    /** The ability slot the ultimate currently owns; 0 while it sleeps (not awakened, or below max energy). */
    static int slot(Player p) {
        PlayerData d = Aegis.I.pd(p);
        if (d.energy < Aegis.I.maxEnergy()) return 0;
        return slotOf(p);
    }

    /** Is the awakened ultimate usable right now (max energy)? */
    static boolean ready(Player p) { return def(p) != null && Aegis.I.pd(p).energy >= Aegis.I.maxEnergy(); }

    static void cast(Player p, int n) {
        PlayerData d = Aegis.I.pd(p);
        Def def = def(p);
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

    /** Awakening Stone: awaken the ultimate of your class. Needs max energy. Returns true if the stone should be used up. */
    static boolean awaken(Player p) {
        PlayerData d = Aegis.I.pd(p);
        ClassType t = d.type;
        if (t == null || d.rolling) { Abilities.msg(p, "You need a class first."); return false; }
        if (!Aegis.I.getConfig().getBoolean("ults.enabled", true)) { Abilities.msg(p, "Ultimates are disabled."); return false; }
        if (d.awakened) { Abilities.msg(p, "Your ultimate is already awakened."); return false; }
        int need = Aegis.I.maxEnergy();
        if (d.energy < need) { Abilities.msg(p, "You need " + need + " energy to awaken your ultimate."); return false; }
        Def def = of(t);
        if (def == null) { Abilities.msg(p, "Your class has no ultimate."); return false; }
        grant(p);
        return true;
    }

    /** Awakens without any checks (the stone, or an admin). */
    static void grant(Player p) {
        PlayerData d = Aegis.I.pd(p);
        d.awakened = true;
        d.cd.remove("a1");
        d.cd.remove("a2");
        Passives.refresh(p);
        Aegis.I.saveData();
        Def def = of(d.type);
        if (def == null) return;
        p.showTitle(Title.title(Component.text("AWAKENED", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                Component.text(def.name(), NamedTextColor.GOLD), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        p.getWorld().playSound(p.getLocation(), "block.beacon.activate", 1f, 0.7f);
        p.getWorld().playSound(p.getLocation(), "entity.evoker.prepare_summon", 1f, 0.8f);
        Fx.energy(p);
        int slot = slotFor(d.type, def);
        String where = def.passive() ? "It triggers by itself while you are at max energy."
                : "At max energy it " + (d.type == ClassType.DOLUS ? "becomes your second ability." : "takes the place of " + d.type.abilityName(slot) + ".");
        p.sendMessage(Component.text(def.name() + ": " + def.desc(), NamedTextColor.AQUA));
        p.sendMessage(Component.text(where + " Below max energy your normal abilities are back. You keep every passive, and the ultimate follows you when you change class.", NamedTextColor.YELLOW));
    }

    /** Admin: take the awakening away again. */
    static void remove(Player p) {
        PlayerData d = Aegis.I.pd(p);
        UltImpl.cleanup(p);
        d.cd.remove("a1");
        d.cd.remove("a2");
        d.cd.remove("ult");
        d.awakened = false;
        Passives.refresh(p);
        Aegis.I.saveData();
    }
}
