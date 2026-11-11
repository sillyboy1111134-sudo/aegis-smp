package dev.aegis;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/** Transient states. Everything is keyed by UUID and expires by wall-clock time. */
final class Status {
    static final Map<UUID, Long> stun = new HashMap<>();
    static final Map<UUID, Long> freeze = new HashMap<>();
    static final Map<UUID, Long> enchain = new HashMap<>();
    static final Map<UUID, Long> immortal = new HashMap<>();
    static final Map<UUID, Long> dual = new HashMap<>();
    static final Map<UUID, Long> swift = new HashMap<>();
    static final Map<UUID, Long> bleed = new HashMap<>();
    static final Map<UUID, Long> overdrive = new HashMap<>();

    static boolean on(Map<UUID, Long> m, Entity e) {
        Long t = m.get(e.getUniqueId());
        if (t == null) return false;
        if (t < System.currentTimeMillis()) { m.remove(e.getUniqueId()); return false; }
        return true;
    }

    static void set(Map<UUID, Long> m, Entity e, double seconds) {
        m.put(e.getUniqueId(), System.currentTimeMillis() + (long) (seconds * 1000));
    }

    /** Stunned players can only gap / pot (see UtilityListener). Mobs just get slowed. */
    static void stun(LivingEntity e, double seconds) {
        if (e instanceof Player p) {
            set(stun, p, seconds);
            p.setVelocity(new Vector(0, 0, 0));
            p.setSprinting(false);
        } else {
            e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (seconds * 20), 6));
        }
    }

    static void bleed(LivingEntity e, double seconds) { set(bleed, e, seconds); }

    static void tickBleed() {
        double dmg = Aegis.I.getConfig().getDouble("bleed.damage-per-second", 1.0);
        for (UUID id : new ArrayList<>(bleed.keySet())) {
            Entity en = Bukkit.getEntity(id);
            if (!(en instanceof LivingEntity le) || le.isDead() || !on(bleed, le)) { bleed.remove(id); continue; }
            Abilities.hurtRaw(le, dmg);
            le.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, le.getLocation().add(0, 1, 0), 4, .25, .4, .25, .02);
        }
    }

    // ------------------------------------------------------------ max-health modifiers
    /** Add a temporary max-health modifier. Names must start with "tmp_". */
    static void healthAdd(Player p, String name, double amount, int ticks) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        removeMod(p, name);
        a.addModifier(new AttributeModifier(new NamespacedKey(Aegis.I, name), amount, AttributeModifier.Operation.ADD_NUMBER));
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Aegis.I, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q != null) removeMod(q, name);
        }, ticks);
    }

    /** Set total max health to the given heart count for a while. */
    static void healthTo(Player p, String name, double hearts, int ticks) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        healthAdd(p, name, hearts * 2.0 - a.getBaseValue(), ticks);
    }

    static void removeMod(Player p, String name) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        NamespacedKey k = new NamespacedKey(Aegis.I, name);
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) if (m.getKey().equals(k)) a.removeModifier(m);
        if (p.getHealth() > a.getValue()) p.setHealth(a.getValue());
    }

    /** Removes every temporary (tmp_) max-health modifier of this plugin. */
    static void clearMods(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        for (AttributeModifier m : new ArrayList<>(a.getModifiers()))
            if (m.getKey().getNamespace().equals("aegis") && m.getKey().getKey().startsWith("tmp_")) a.removeModifier(m);
        if (p.getHealth() > a.getValue()) p.setHealth(a.getValue());
    }

    static void clear(Player p) {
        UUID id = p.getUniqueId();
        stun.remove(id); freeze.remove(id); enchain.remove(id); immortal.remove(id);
        dual.remove(id); swift.remove(id); bleed.remove(id); overdrive.remove(id);
        clearMods(p);
    }
}
