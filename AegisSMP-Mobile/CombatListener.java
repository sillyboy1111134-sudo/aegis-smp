package dev.aegis;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class CombatListener implements Listener {
    private final Aegis pl = Aegis.I;
    private static final Map<UUID, Combo> combos = new HashMap<>();

    private static final class Combo { UUID target; int n; long last; }

    // ------------------------------------------------------------------ generic damage rules
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity v)) return;
        if (Status.on(Status.immortal, v)) { e.setCancelled(true); return; }
        if (v instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            ConfigurationSection n = Passives.first(p, "no-fall-damage");
            if (n != null && n.getBoolean("no-fall-damage", false)) {
                List<String> except = n.getStringList("fall-damage-on");
                Block b = p.getLocation().getBlock();
                boolean hurtByBlock = except.contains(b.getType().name()) || except.contains(b.getRelative(0, -1, 0).getType().name());
                if (!hurtByBlock) e.setCancelled(true);
            }
        }
    }

    /** Runs last: armor is ignored, resistance re-derived, Icor's cap applied. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void finalizeDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity v)) return;
        if (pl.getConfig().getBoolean("general.ignore-armor", true) && e.isApplicable(DamageModifier.ARMOR)) {
            double base = e.getDamage(DamageModifier.BASE)
                    + (e.isApplicable(DamageModifier.HARD_HAT) ? e.getDamage(DamageModifier.HARD_HAT) : 0)
                    + (e.isApplicable(DamageModifier.BLOCKING) ? e.getDamage(DamageModifier.BLOCKING) : 0);
            e.setDamage(DamageModifier.ARMOR, 0);
            PotionEffect r = v.getPotionEffect(PotionEffectType.RESISTANCE);
            if (r != null && e.isApplicable(DamageModifier.RESISTANCE))
                e.setDamage(DamageModifier.RESISTANCE, -Math.min(1.0, 0.2 * (r.getAmplifier() + 1)) * base);
        }
        if (v instanceof Player p) {
            ConfigurationSection n = Passives.first(p, "max-damage");
            if (n != null) {
                double max = n.getDouble("max-damage", 8), f = e.getFinalDamage();
                if (f > max) e.setDamage(DamageModifier.BASE, e.getDamage(DamageModifier.BASE) - (f - max));
            }
        }
    }

    // ------------------------------------------------------------------ melee
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player att = e.getDamager() instanceof Player a ? a : null;
        if (att != null && (Status.on(Status.stun, att) || Status.on(Status.freeze, att))) { e.setCancelled(true); return; }
        if (!(e.getEntity() instanceof LivingEntity vic)) return;
        if (att != null && Abilities.trusts(att, vic) && pl.getConfig().getBoolean("trust.disable-pvp", false)) { e.setCancelled(true); return; }
        if (Abilities.inAbility || att == null || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;

        PlayerData d = pl.pd(att);
        boolean strong = att.getAttackCooldown() >= pl.getConfig().getDouble("general.strong-hit-threshold", 0.9);
        boolean trusted = Abilities.trusts(att, vic);
        double dmg = e.getDamage();

        if (Status.on(Status.dual, att)) dmg *= pl.getConfig().getDouble("classes.samurai.a1.damage-multiplier", 1.25);
        if (Status.on(Status.swift, att)) {
            dmg += pl.getConfig().getDouble("classes.samurai.a2.bonus-damage", 3);
            Status.swift.remove(att.getUniqueId());
        }
        if (vic instanceof Player vp) victimSide(e, vp, att);

        if (d.type != null && !trusted) {
            for (String pid : Passives.unlocked(att)) {
                ConfigurationSection n = Passives.node(d.type, pid);
                if (n == null) continue;

                // Boreus: axe acts like a mace on a 5+ block fall (not a weak-hit passive: it needs the fall)
                if (n.contains("mace-min-fall")) dmg = maceSmash(att, vic, n, dmg);

                if (!strong) continue;   // weak hits never trigger passives
                if (n.contains("crit-in-water") && att.isInWater() && !isCrit(att)) dmg *= n.getDouble("crit-in-water", 1.5);
                if (n.contains("burning-multiplier") && vic.getFireTicks() > 0) dmg *= n.getDouble("burning-multiplier", 1.1);
                if (n.contains("combo-start")) dmg *= combo(att, vic, n);
                if (n.contains("heal-on-hit")) {
                    double max = att.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                    att.setHealth(Math.min(max, att.getHealth() + n.getDouble("heal-on-hit", 1.0)));
                }
                if (n.contains("armor-damage-multiplier")) Gear.extraArmor(vic, n.getDouble("armor-damage-multiplier") - 1.0, dmg);
                if (n.contains("action") && "hit".equals(n.getString("when", "hit")) && Passives.roll(n.getDouble("chance", 100))) {
                    if (n.getBoolean("crit-only", false) && !isCrit(att)) continue;
                    if (n.getBoolean("axe-only", false) && !att.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) continue;
                    Passives.action(vic, n);
                }
            }
        }
        e.setDamage(dmg);
        shieldLogic(att, vic);
    }

    /** Axe-as-mace: vanilla mace bonus per fallen block, capped, with its own cooldown. */
    private double maceSmash(Player att, LivingEntity vic, ConfigurationSection n, double dmg) {
        if (!att.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) return dmg;
        boolean overdrive = Status.on(Status.overdrive, att);
        PlayerData d = pl.pd(att);
        if (!overdrive && d.remKey("mace") > 0) return dmg;
        double fall = att.getFallDistance();
        if (fall < n.getDouble("mace-min-fall", 5)) return dmg;

        double bonus = Math.min(fall, 3) * 4 + Math.max(0, Math.min(fall, 8) - 3) * 2 + Math.max(0, fall - 8);
        double total = Math.min(n.getDouble("mace-max-damage", 8.0), dmg + bonus);
        if (!overdrive) d.startKey("mace", n.getDouble("mace-cooldown", 10));

        att.getWorld().spawnParticle(Particle.EXPLOSION, vic.getLocation().add(0, 1, 0), 2, .2, .2, .2, 0);
        att.getWorld().playSound(vic.getLocation(), "item.mace.smash_ground", 1f, 1f);
        att.setFallDistance(0);
        if (overdrive) {   // Windburst: launch the attacker back up
            Vector up = att.getVelocity();
            up.setY(pl.getConfig().getDouble("classes.boreus.a2.windburst-velocity", 1.3));
            att.setVelocity(up);
            att.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, att.getLocation(), 1);
        }
        return total;
    }

    private boolean isCrit(Player p) {
        return p.getFallDistance() > 0 && !p.isOnGround() && !p.isClimbing() && !p.isInWater()
                && !p.isSprinting() && !p.hasPotionEffect(PotionEffectType.BLINDNESS) && p.getVehicle() == null;
    }

    private double combo(Player att, LivingEntity vic, ConfigurationSection n) {
        long now = System.currentTimeMillis();
        long win = (long) (n.getDouble("combo-window-seconds", 3) * 1000);
        Combo c = combos.get(att.getUniqueId());
        if (c == null || !vic.getUniqueId().equals(c.target) || now - c.last > win) {
            c = new Combo();
            c.target = vic.getUniqueId();
        }
        c.n++;
        c.last = now;
        combos.put(att.getUniqueId(), c);
        int start = n.getInt("combo-start", 3), every = Math.max(1, n.getInt("combo-every", 2));
        if (c.n > start && (c.n - start - 1) % every == 0 && !isCrit(att)) {
            att.getWorld().spawnParticle(Particle.CRIT, vic.getLocation().add(0, 1, 0), 20, .3, .4, .3, .2);
            return n.getDouble("combo-multiplier", 1.5);
        }
        return 1.0;
    }

    /** Passives of the player being hit (Ymir slow, thorned shield). */
    private void victimSide(EntityDamageByEntityEvent e, Player vp, Player att) {
        if (pl.pd(vp).type == null || Abilities.trusts(vp, att)) return;
        boolean strong = att.getAttackCooldown() >= pl.getConfig().getDouble("general.strong-hit-threshold", 0.9);
        for (String pid : Passives.unlocked(vp)) {
            ConfigurationSection n = Passives.node(vp, pid);
            if (n == null) continue;
            if (strong && "damaged".equals(n.getString("when", "")) && n.contains("action") && Passives.roll(n.getDouble("chance", 100)))
                Passives.action(att, n);
            if (n.contains("thorns") && vp.isBlocking() && e.isApplicable(DamageModifier.BLOCKING) && e.getDamage(DamageModifier.BLOCKING) < 0)
                Abilities.hurt(att, n.getDouble("thorns", 1.0), vp);
        }
    }

    /** Berserker doubles shield-disable time, Ymir halves it and gains resistance while stunned. */
    private void shieldLogic(Player att, LivingEntity vic) {
        if (!(vic instanceof Player vp) || !vp.isBlocking()) return;
        double factor = 1.0;
        ConfigurationSection a = Passives.first(att, "shield-disable-multiplier");
        if (a != null) factor *= a.getDouble("shield-disable-multiplier", 2.0);
        ConfigurationSection h = Passives.first(vp, "shield-stun-multiplier");
        if (h != null) factor *= h.getDouble("shield-stun-multiplier", 0.5);
        ConfigurationSection fxn = Passives.first(vp, "shield-stun-effects");
        if (factor == 1.0 && fxn == null) return;

        final double f = factor;
        final int before = vp.getCooldown(Material.SHIELD);
        Bukkit.getScheduler().runTask(pl, () -> {
            int after = vp.getCooldown(Material.SHIELD);
            if (before != 0 || after <= 0) return;
            int nc = Math.max(1, (int) Math.round(after * f));
            vp.setCooldown(Material.SHIELD, nc);
            if (fxn != null) {
                for (String s : fxn.getStringList("shield-stun-effects")) {
                    String[] x = s.split(":");
                    PotionEffectType t = Abilities.fx(x[0]);
                    if (t != null) vp.addPotionEffect(new PotionEffect(t, nc, Math.max(0, Integer.parseInt(x[1].trim()) - 1)));
                }
            }
        });
    }

    // ------------------------------------------------------------------ misc
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p)) return;
        ConfigurationSection n = Passives.first(p, "ignored-by");
        if (n != null && n.getStringList("ignored-by").contains(e.getEntity().getType().name())) e.setCancelled(true);
    }
}
