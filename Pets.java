package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Warden;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Azaroth's pet Warden (Deep's Call). */
final class Pets {
    static final Map<UUID, UUID> pets = new HashMap<>();     // owner -> warden
    static final Map<UUID, UUID> target = new HashMap<>();   // owner -> current enemy

    static boolean isPet(Entity e) {
        return e instanceof Warden && e.getPersistentDataContainer().has(Aegis.I.petKey, PersistentDataType.STRING);
    }

    static UUID ownerOf(Entity e) {
        String s = e.getPersistentDataContainer().get(Aegis.I.petKey, PersistentDataType.STRING);
        try { return s == null ? null : UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    static boolean alive(Player p) {
        UUID w = pets.get(p.getUniqueId());
        if (w == null) return false;
        Entity e = Bukkit.getEntity(w);
        if (e != null && e.isValid() && !e.isDead()) return true;
        pets.remove(p.getUniqueId());
        return false;
    }

    static boolean summon(Player p) {
        double hpMul = Abilities.v(p, 1, "health-multiplier", 0.5);
        Location loc = p.getLocation().clone();
        loc.add(loc.getDirection().setY(0).normalize().multiply(2));
        Warden w = p.getWorld().spawn(loc, Warden.class, wd -> {
            AttributeInstance hp = wd.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) { hp.setBaseValue(hp.getBaseValue() * hpMul); wd.setHealth(hp.getValue()); }
            wd.customName(Component.text(p.getName() + "'s Warden", NamedTextColor.AQUA));
            wd.setCustomNameVisible(true);
            wd.setPersistent(false);
            wd.setRemoveWhenFarAway(false);
            wd.getPersistentDataContainer().set(Aegis.I.petKey, PersistentDataType.STRING, p.getUniqueId().toString());
        });
        pets.put(p.getUniqueId(), w.getUniqueId());
        w.clearAnger(p);
        Fx.summonPet(w.getLocation());
        p.getWorld().playSound(w.getLocation(), "entity.warden.emerge", 1f, 1f);
        return true;
    }

    /** Pet was killed: put Deep's Call on its cooldown. */
    static void onPetDeath(Warden w) {
        UUID owner = ownerOf(w);
        if (owner == null) return;
        pets.remove(owner);
        target.remove(owner);
        Player o = Bukkit.getPlayer(owner);
        if (o == null) return;
        PlayerData d = Aegis.I.pd(o);
        d.startCd(1, Abilities.v(o, 1, "cooldown-after-death", 180));
        o.sendMessage(Component.text("Your Warden has fallen. Deep's Call is on cooldown.", NamedTextColor.RED));
    }

    /** Owner died / quit / changed class: pet vanishes, no cooldown. */
    static void dismiss(Player owner) {
        UUID w = pets.remove(owner.getUniqueId());
        target.remove(owner.getUniqueId());
        if (w == null) return;
        Entity e = Bukkit.getEntity(w);
        if (e != null) e.remove();
        Aegis.I.pd(owner).cd.remove("a1");
    }

    /** Remember who the owner is fighting so the pet joins in. */
    static void noteCombat(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        Entity v = e.getEntity();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        if (d instanceof Player a && pets.containsKey(a.getUniqueId()) && v instanceof LivingEntity && !isPet(v) && !Abilities.trusts(a, v))
            target.put(a.getUniqueId(), v.getUniqueId());
        if (v instanceof Player o && pets.containsKey(o.getUniqueId()) && d instanceof LivingEntity && !isPet(d)
                && !(d instanceof Player dp && Abilities.trusts(o, dp)))
            target.put(o.getUniqueId(), d.getUniqueId());
    }

    /** Once per second: follow the owner, never anger at owner/trusted, attack the owner's enemy. */
    static void tick() {
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(pets.entrySet())) {
            Player owner = Bukkit.getPlayer(en.getKey());
            Entity ent = Bukkit.getEntity(en.getValue());
            if (owner == null || ent == null || !ent.isValid()) {
                if (ent != null && owner == null) ent.remove();
                pets.remove(en.getKey());
                continue;
            }
            Warden w = (Warden) ent;
            PlayerData d = Aegis.I.pd(owner);
            w.clearAnger(owner);
            for (UUID t : d.trusted) {
                Player tp = Bukkit.getPlayer(t);
                if (tp != null) w.clearAnger(tp);
            }

            double tpDist = Abilities.v(owner, 1, "follow-teleport-distance", 20);
            if (!w.getWorld().equals(owner.getWorld()) || w.getLocation().distance(owner.getLocation()) > tpDist) {
                w.teleport(owner.getLocation());
            } else if (w.getLocation().distance(owner.getLocation()) > 8) {
                w.getPathfinder().moveTo(owner.getLocation(), 1.2);
            }

            UUID et = target.get(owner.getUniqueId());
            Entity enemy = et == null ? null : Bukkit.getEntity(et);
            if (enemy instanceof LivingEntity le && le.isValid() && !le.isDead() && le.getWorld().equals(w.getWorld())
                    && !Abilities.trusts(owner, le) && w.getLocation().distance(le.getLocation()) < 40) {
                w.setAnger(le, 150);
            } else {
                target.remove(owner.getUniqueId());
            }
        }
    }
}
