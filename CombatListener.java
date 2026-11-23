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
import org.bukkit.inventory.ItemStack;
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

    // ---- Boreus axe smash: how far the player has really fallen (highest point since leaving the ground)
    private static final Map<UUID, Double> peak = new HashMap<>();
    private boolean smashed = false;                 // set while onHit works out an axe smash
    private EntityDamageByEntityEvent smashEvent;    // the event that carries a smash: it is true (armor-ignoring) damage

    /** Forget the fall so far (Breezy Dash and the smash itself start a fresh fall). */
    static void resetFall(Player p) { peak.put(p.getUniqueId(), p.getLocation().getY()); p.setFallDistance(0); }

    @EventHandler(ignoreCancelled = true)
    public void onFallTrack(org.bukkit.event.player.PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getTo() == null) return;
        if (p.isOnGround() || p.isInWater() || p.isInLava() || p.isClimbing() || p.isInsideVehicle()) { peak.remove(p.getUniqueId()); return; }
        peak.merge(p.getUniqueId(), e.getTo().getY(), Math::max);
    }

    @EventHandler
    public void onQuitFall(org.bukkit.event.player.PlayerQuitEvent e) { peak.remove(e.getPlayer().getUniqueId()); }

    private static boolean isSpear(ItemStack it) { return it != null && it.getType().name().endsWith("_SPEAR"); }

    // ------------------------------------------------------------------ generic damage rules
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity v)) return;
        // Fireball explosions only hurt through Abilities.explode (armor-ignoring), never vanilla damage
        if (Abilities.exploding && (e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) { e.setCancelled(true); return; }
        // Chrono - Future Sight: dodge incoming hits
        if (v instanceof Player dp && Status.dodges.containsKey(dp.getUniqueId())
                && pl.getConfig().getStringList("classes.chrono.a2.dodge-causes").contains(e.getCause().name())) {
            int left = Status.takeDodge(dp);
            if (left >= 0) { e.setCancelled(true); Fx.dodge(dp, left); return; }
        }
        if (Status.on(Status.immortal, v)) { e.setCancelled(true); return; }
        if (v instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            ConfigurationSection n = Passives.first(p, "no-fall-damage");
            boolean spearOk = n == null || !n.getBoolean("requires-spear", false)
                    || p.getInventory().getItemInMainHand().getType().name().endsWith("_SPEAR")
                    || p.getInventory().getItemInOffHand().getType().name().endsWith("_SPEAR");
            if (n != null && n.getBoolean("no-fall-damage", false) && spearOk && !UltImpl.tremor(p)) {
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
        // Penetration (spear): ignore a % of armor per level
        if (e instanceof EntityDamageByEntityEvent ee && ee.getDamager() instanceof Player pa && e.isApplicable(DamageModifier.ARMOR)) {
            int pen = CE.level(pa.getInventory().getItemInMainHand(), "penetration");
            if (pen > 0) e.setDamage(DamageModifier.ARMOR, e.getDamage(DamageModifier.ARMOR)
                    * (1 - Math.min(1.0, pen * CustomEnchantListener.cfgD("penetration.percent-per-level", 0.10))));
        }
        boolean allTrue = pl.getConfig().getBoolean("general.all-damage-true", false);
        boolean smash = e == smashEvent;   // Boreus' axe smash: always lands in full, armor or not
        boolean trueDmg = allTrue || smash || (Abilities.inAbility && pl.getConfig().getBoolean("general.abilities-ignore-armor", true));
        if (trueDmg && e.isApplicable(DamageModifier.ARMOR)) {
            double base = e.getDamage(DamageModifier.BASE)
                    + (e.isApplicable(DamageModifier.HARD_HAT) ? e.getDamage(DamageModifier.HARD_HAT) : 0)
                    + (e.isApplicable(DamageModifier.BLOCKING) ? e.getDamage(DamageModifier.BLOCKING) : 0);
            e.setDamage(DamageModifier.ARMOR, 0);
            PotionEffect r = v.getPotionEffect(PotionEffectType.RESISTANCE);
            if (r != null && e.isApplicable(DamageModifier.RESISTANCE))
                e.setDamage(DamageModifier.RESISTANCE, -Math.min(1.0, 0.2 * (r.getAmplifier() + 1)) * base);
        }
        // true damage: protection-type enchantments are ignored too (Resistance and absorption hearts still work)
        if (trueDmg && pl.getConfig().getBoolean("general.true-damage-ignores-enchants", true) && e.isApplicable(DamageModifier.MAGIC))
            e.setDamage(DamageModifier.MAGIC, 0);
        // Mega Mace: hits never deal more than megamace.damage-cap (default 10 HP = 5 hearts)
        if (e instanceof EntityDamageByEntityEvent me && me.getDamager() instanceof Player mp && MegaMace.isMace(mp.getInventory().getItemInMainHand())) {
            double cap = pl.getConfig().getDouble("megamace.damage-cap", 10.0), f = e.getFinalDamage();
            if (f > cap) e.setDamage(DamageModifier.BASE, e.getDamage(DamageModifier.BASE) - (f - cap));
        }
        // ultimates: Agni's domain (1.5x), Workshop tools (1.15x); Dolus' tremor ignores Feather Falling
        if (e instanceof EntityDamageByEntityEvent um && um.getDamager() instanceof Player up2) {
            double umult = UltImpl.damageMult(up2, v);
            if (umult != 1.0) e.setDamage(DamageModifier.BASE, e.getDamage(DamageModifier.BASE) * umult);
        }
        // Spears: a melee hit never deals more than spear.damage-cap (default 10 HP = 5 hearts); abilities are not capped
        if (e instanceof EntityDamageByEntityEvent se && se.getDamager() instanceof Player sp && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && !Abilities.inAbility && isSpear(sp.getInventory().getItemInMainHand())) {
            double cap = pl.getConfig().getDouble("spear.damage-cap", 10.0), f = e.getFinalDamage();
            if (f > cap) e.setDamage(DamageModifier.BASE, e.getDamage(DamageModifier.BASE) - (f - cap));
        }
        if (v instanceof Player tp && e.getCause() == EntityDamageEvent.DamageCause.FALL && UltImpl.tremor(tp) && e.isApplicable(DamageModifier.MAGIC))
            e.setDamage(DamageModifier.MAGIC, 0);
        if (v instanceof Player p) {
            ConfigurationSection n = Passives.first(p, "max-damage");
            if (n != null) {
                double max = n.getDouble("max-damage", 8), f = e.getFinalDamage();
                if (f > max) e.setDamage(DamageModifier.BASE, e.getDamage(DamageModifier.BASE) - (f - max));
            }
        }
        // Immortal (armor enchant): survive a killing blow
        if (v instanceof Player ip && e.getCause() != EntityDamageEvent.DamageCause.VOID
                && ip.getHealth() - e.getFinalDamage() <= 0 && CustomEnchantListener.tryImmortal(ip)) e.setCancelled(true);
        // Ultimates that trigger on a killing blow: Fateweaver's lives, Berserker's Defiant Rage
        if (!e.isCancelled() && v instanceof Player ur && e.getCause() != EntityDamageEvent.DamageCause.VOID
                && ur.getHealth() - e.getFinalDamage() <= 0 && UltImpl.tryRevive(ur)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ melee
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        // Azaroth's pet Warden: never hurts its owner / trusted, deals reduced damage
        if (e.getDamager() instanceof org.bukkit.entity.Warden pw && Pets.isPet(pw)) {
            java.util.UUID ownerId = Pets.ownerOf(pw);
            Player op = ownerId == null ? null : org.bukkit.Bukkit.getPlayer(ownerId);
            if (e.getEntity() instanceof Player vp && (vp.getUniqueId().equals(ownerId) || (op != null && Abilities.trusts(op, vp)))) { e.setCancelled(true); return; }
            e.setDamage(e.getDamage() * pl.getConfig().getDouble("classes.azaroth.a1.damage-multiplier", 0.25));
            return;
        }
        // wild Wardens can't hurt Azaroth
        if (e.getDamager() instanceof org.bukkit.entity.Warden && e.getEntity() instanceof Player wp) {
            ConfigurationSection ig = Passives.first(wp, "ignored-by");
            if (ig != null && ig.getStringList("ignored-by").contains("WARDEN")) { e.setCancelled(true); return; }
        }
        Pets.noteCombat(e);
        Player att = e.getDamager() instanceof Player a ? a : null;
        if (att != null && (Status.on(Status.stun, att) || Status.on(Status.freeze, att))) { e.setCancelled(true); return; }
        if (!(e.getEntity() instanceof LivingEntity vic)) return;
        if (att != null && Abilities.trusts(att, vic) && pl.getConfig().getBoolean("trust.disable-pvp", false)) { e.setCancelled(true); return; }
        if (Abilities.inAbility || att == null || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;

        smashed = false;
        smashEvent = null;
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
        if (!trusted) dmg = MoreClasses.luckHit(att, dmg);   // Jackpot: Luck of the Draw / High Roller

        if (d.type != null && !trusted) {
            for (String pid : Passives.unlocked(att)) {
                ConfigurationSection n = Passives.node(d.type, pid);
                if (n == null) continue;

                // Boreus: axe acts like a mace on a 5+ block fall (not a weak-hit passive: it needs the fall)
                if (n.contains("mace-min-fall")) dmg = maceSmash(att, vic, n, dmg);

                if (!strong) continue;   // weak hits never trigger passives
                if (n.contains("backstab-chance") && NewClasses.isBackstab(att, vic, n.getDouble("backstab-cone", 120)) && Passives.roll(n.getDouble("backstab-chance", 20))) {
                    dmg *= n.getDouble("backstab-multiplier", 2.0);
                    att.getWorld().playSound(vic.getLocation(), "entity.player.attack.crit", 1f, 0.8f);
                }
                if (n.contains("tp-behind-chance") && Passives.roll(n.getDouble("tp-behind-chance", 5))) NewClasses.tpBehind(att, vic);
                // Jackpot
                if (n.contains("shuffle-chance") && vic instanceof Player sv && Passives.roll(n.getDouble("shuffle-chance", 10))) MoreClasses.shuffleOne(sv);
                if (n.contains("self-shuffle-chance") && Passives.roll(n.getDouble("self-shuffle-chance", 5))) MoreClasses.shuffleOne(att);
                if (n.contains("hit-heal-chance") && Passives.roll(n.getDouble("hit-heal-chance", 5))) NewClasses.heal(att, n.getDouble("hit-heal-hp", 4));
                if (n.contains("hit-hurt-chance") && Passives.roll(n.getDouble("hit-hurt-chance", 5))) {
                    final double selfHurt = n.getDouble("hit-hurt-hp", 2);
                    org.bukkit.Bukkit.getScheduler().runTask(pl, () -> { if (att.isOnline() && !att.isDead()) Abilities.hurtRaw(att, selfHurt); });
                }
                if (n.contains("sharp-min")) dmg = MoreClasses.rollSharp(att, n, dmg);
                // Singularity
                if (n.contains("low-hp-bonus") && vic.getHealth() < n.getDouble("hp-threshold", 12)) dmg *= n.getDouble("low-hp-bonus", 1.25);
                if (n.contains("crit-in-water") && att.isInWater() && !isCrit(att)) dmg *= n.getDouble("crit-in-water", 1.5);
                if (n.contains("burning-multiplier") && vic.getFireTicks() > 0) dmg *= n.getDouble("burning-multiplier", 1.1);
                if (n.contains("combo-start")) dmg *= combo(att, vic, n);
                if (n.contains("heal-on-hit")) {
                    double max = att.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                    att.setHealth(Math.min(max, att.getHealth() + n.getDouble("heal-on-hit", 1.0)));
                    Fx.heal(att);
                }
                if (n.contains("armor-damage-multiplier")) Gear.extraArmor(vic, n.getDouble("armor-damage-multiplier") - 1.0, dmg);
                if (n.contains("action") && "hit".equals(n.getString("when", "hit")) && Passives.roll(n.getDouble("chance", 100))) {
                    if (n.getBoolean("crit-only", false) && !isCrit(att)) continue;
                    if (n.getBoolean("axe-only", false) && !att.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) continue;
                    Passives.action(vic, n);
                }
            }
        }
        if (!trusted) dmg = CustomEnchantListener.melee(att, vic, strong, dmg);
        if (smashed) { smashEvent = e; smashed = false; }
        e.setDamage(dmg);
        shieldLogic(att, vic);
    }

    /** Axe-as-mace: density-II mace damage, capped (and on cooldown) - unless Overdrive makes it a normal mace. */
    private double maceSmash(Player att, LivingEntity vic, ConfigurationSection n, double dmg) {
        if (!att.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) return dmg;
        boolean overdrive = Status.on(Status.overdrive, att);
        PlayerData d = pl.pd(att);
        if (!overdrive && d.remKey("mace") > 0) return dmg;
        // the real fall: the server's counter, or how far below the highest point of this jump we are (wind charges etc. can reset the counter)
        double tracked = peak.getOrDefault(att.getUniqueId(), att.getLocation().getY()) - att.getLocation().getY();
        double fall = Math.max(att.getFallDistance(), tracked);
        double minFall = overdrive ? pl.getConfig().getDouble("classes.boreus.a2.overdrive-min-fall", 1.5) : n.getDouble("mace-min-fall", 5);
        if (fall < minFall) return dmg;

        // flat damage: every smash does the same amount (default 10 HP = 5 hearts), no matter the fall distance.
        // Set mace-flat-damage to 0 to go back to the old fall-scaled density-II formula.
        double flat = n.getDouble("mace-flat-damage", 10.0);
        double total;
        if (flat > 0) {
            total = flat;
        } else {
            // vanilla smash bonus (4/block for 3 blocks, 2/block for the next 5, 1/block after) + Density (0.5 per level per block)
            double bonus = Math.min(fall, 3) * 4 + Math.max(0, Math.min(fall, 8) - 3) * 2 + Math.max(0, fall - 8);
            bonus += n.getDouble("mace-density", 2) * 0.5 * fall;
            total = dmg + bonus;
            if (!overdrive) total = Math.min(n.getDouble("mace-max-damage", 8.0), total);   // 4 hearts
        }
        if (!overdrive) d.startKey("mace", n.getDouble("mace-cooldown", 10));
        smashed = true;

        Fx.smash(vic.getLocation(), overdrive);
        att.getWorld().playSound(vic.getLocation(), "item.mace.smash_ground", 1f, 1f);
        resetFall(att);
        if (overdrive) {
            // Windburst: launch the attacker back up
            Vector up = att.getVelocity();
            up.setY(pl.getConfig().getDouble("classes.boreus.a2.windburst-velocity", 1.3));
            att.setVelocity(up);
            Fx.windburst(att);
            // normal-mace area knockback
            for (Entity en : vic.getNearbyEntities(3.5, 2, 3.5)) {
                if (en.equals(att) || !(en instanceof LivingEntity) || en instanceof org.bukkit.entity.ArmorStand || Abilities.trusts(att, en)) continue;
                Vector push = en.getLocation().toVector().subtract(vic.getLocation().toVector()).setY(0);
                if (push.lengthSquared() < 1e-4) push = new Vector(1, 0, 0);
                en.setVelocity(push.normalize().multiply(0.8).setY(0.4));
            }
        }
        return total;
    }

    static boolean isCrit(Player p) {
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
            Fx.comboCrit(vic);
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
                Abilities.hurt(att, n.getDouble("thorns", 1.0), vp, false);
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
        if (Pets.isPet(e.getEntity()) && e.getTarget() instanceof Player tp) {
            java.util.UUID ownerId = Pets.ownerOf(e.getEntity());
            Player op = ownerId == null ? null : org.bukkit.Bukkit.getPlayer(ownerId);
            if (tp.getUniqueId().equals(ownerId) || (op != null && Abilities.trusts(op, tp))) { e.setCancelled(true); return; }
        }
        if (!(e.getTarget() instanceof Player p)) return;
        ConfigurationSection n = Passives.first(p, "ignored-by");
        if (n != null && n.getStringList("ignored-by").contains(e.getEntity().getType().name())) e.setCancelled(true);
    }
}
