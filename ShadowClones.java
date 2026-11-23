package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Rogue - Shadow Manipulation: the clones. Each one wears your armor, holds your weapon and shield, copies your potion
 * effects and fights like a decent PvPer instead of a mindless zombie: it sprints in, circle-strafes, times jump crits
 * to its weapon's attack cooldown, w-taps after a hit, blocks with a shield now and then, and eats golden apples
 * (backing off while it does) when it is under half health. Clones ONLY ever attack players. The vanilla zombie AI is
 * never allowed to pick a target or hit anything: every swing comes from {@link #swing}.
 */
final class ShadowClones {
    private static final Random RND = new Random();

    static final Map<UUID, Clone> clones = new HashMap<>();   // clone entity -> its state
    /** True only while the AI itself is swinging: any other melee hit by a clone (the vanilla zombie attack) is cancelled. */
    static boolean swinging = false;

    static final class Clone {
        final Zombie z;
        final UUID owner;
        final boolean hunter;
        UUID target, provoked;
        int cd = 20;                         // ticks since the last swing
        int strafeDir = RND.nextBoolean() ? 1 : -1, strafeT = 10 + RND.nextInt(15);
        int eat = 0, eatCd = 0, apples, backoff = 0, jumpCd = 0;
        ItemStack saved;                     // the weapon while a golden apple is in the hand
        boolean sprinting, bubble;

        Clone(Zombie z, UUID owner, boolean hunter, int apples) { this.z = z; this.owner = owner; this.hunter = hunter; this.apples = apples; }
    }

    static boolean isClone(Entity e) { return e != null && clones.containsKey(e.getUniqueId()); }

    static UUID ownerOf(Entity e) { Clone c = e == null ? null : clones.get(e.getUniqueId()); return c == null ? null : c.owner; }

    private static double cfg(String key, double def) { return UltImpl.c("shadows", key, def); }

    // ================================================================ summoning
    /** Spawns the clones inside the shadow pool and starts their AI. Returns after spawning; the AI task ends them. */
    static void summon(Player p, Location ctr, double r, int lifeTicks) {
        final UUID owner = p.getUniqueId();
        final int hunters = (int) cfg("hunters", 5), runners = (int) cfg("runners", 5), apples = (int) cfg("apples", 3);
        final List<Clone> list = new ArrayList<>();
        for (int i = 0; i < hunters + runners; i++) {
            Location at = UltImpl.randomSurface(ctr, r);
            Zombie z = spawn(p, at, lifeTicks);
            Clone c = new Clone(z, owner, i < hunters, apples);
            clones.put(z.getUniqueId(), c);
            list.add(c);
            Fx.burst(at.clone().add(0, 1, 0), Particle.SQUID_INK, 25, .2);
        }
        ctr.getWorld().playSound(ctr, "entity.enderman.teleport", 1.2f, 0.5f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                Player o = Bukkit.getPlayer(owner);
                if (o == null || t > lifeTicks) { for (Clone c : list) remove(c); cancel(); return; }
                for (Clone c : list) {
                    try { tick(o, c, t); }
                    catch (Exception ex) { Aegis.I.getLogger().warning("Shadow clone AI error: " + ex); }
                }
            }
        }.runTaskTimer(Aegis.I, 0, 1);
    }

    private static Zombie spawn(Player p, Location at, int life) {
        return at.getWorld().spawn(at, Zombie.class, zz -> {
            zz.setShouldBurnInDay(false);
            zz.setBaby(false);
            zz.setCanBreakDoors(false);
            zz.setCanPickupItems(false);
            zz.setPersistent(false);
            zz.setRemoveWhenFarAway(false);
            double hpv = cfg("clone-health", 100);
            AttributeInstance hp = zz.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(hpv);
            zz.setHealth(hpv);
            AttributeInstance sp = zz.getAttribute(Attribute.MOVEMENT_SPEED);
            if (sp != null) sp.setBaseValue(cfg("clone-speed", 0.26));
            AttributeInstance fr = zz.getAttribute(Attribute.FOLLOW_RANGE);
            if (fr != null) fr.setBaseValue(64);
            try {   // a hurt clone must never call in real zombies
                Attribute re = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("spawn_reinforcements"));
                AttributeInstance ri = re == null ? null : zz.getAttribute(re);
                if (ri != null) ri.setBaseValue(0);
            } catch (Throwable ignored) { }
            EntityEquipment eq = zz.getEquipment();
            PlayerInventory inv = p.getInventory();
            ItemStack head = inv.getHelmet() == null ? null : inv.getHelmet().clone();
            if (head == null) {
                head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sm = (SkullMeta) head.getItemMeta();
                sm.setOwningPlayer(p);
                head.setItemMeta(sm);
            }
            eq.setHelmet(head);
            eq.setChestplate(inv.getChestplate() == null ? null : inv.getChestplate().clone());   // exact armor, trims included
            eq.setLeggings(inv.getLeggings() == null ? null : inv.getLeggings().clone());
            eq.setBoots(inv.getBoots() == null ? null : inv.getBoots().clone());
            eq.setItemInMainHand(inv.getItemInMainHand().clone());
            eq.setItemInOffHand(inv.getItemInOffHand().clone());   // a shield here really blocks (see shieldBlocks)
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
            eq.setItemInOffHandDropChance(0f);
            zz.customName(Component.text(p.getName(), NamedTextColor.WHITE));
            zz.setCustomNameVisible(true);
            for (PotionEffect ef : p.getActivePotionEffects())   // exact potion effects
                if (ef.getType() != PotionEffectType.INVISIBILITY)
                    zz.addPotionEffect(new PotionEffect(ef.getType(), life, ef.getAmplifier(), ef.isAmbient(), ef.hasParticles(), ef.hasIcon()));
        });
    }

    // ================================================================ lifecycle
    static void remove(Clone c) {
        clones.remove(c.z.getUniqueId());
        if (c.z.isValid()) {
            Fx.burst(c.z.getLocation().add(0, 1, 0), Particle.SQUID_INK, 20, .2);
            c.z.remove();
        }
    }

    /** A clone died (killed by someone): just forget it. */
    static void died(Entity z) { clones.remove(z.getUniqueId()); }

    static void removeOf(UUID owner) {
        for (Clone c : new ArrayList<>(clones.values())) if (c.owner.equals(owner)) remove(c);
    }

    static void shutdown() {
        for (Clone c : new ArrayList<>(clones.values())) { clones.remove(c.z.getUniqueId()); c.z.remove(); }
    }

    /** Someone hit a clone: it hunts that player (runners too). */
    static void provoke(Zombie z, Entity attacker) {
        Clone c = clones.get(z.getUniqueId());
        if (c == null || !(attacker instanceof Player ap)) return;
        Player o = Bukkit.getPlayer(c.owner);
        if (ap.getUniqueId().equals(c.owner) || (o != null && Abilities.trusts(o, ap))) return;
        c.provoked = ap.getUniqueId();
    }

    /** A clone with a shield in its off hand blocks about a third of the hits that come from the front. */
    static boolean shieldBlocks(Zombie z, Entity attacker) {
        if (z.getEquipment() == null || z.getEquipment().getItemInOffHand().getType() != Material.SHIELD || attacker == null) return false;
        Vector toAtt = attacker.getLocation().toVector().subtract(z.getLocation().toVector()).setY(0);
        if (toAtt.lengthSquared() < 1e-4) return false;
        Vector facing = z.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1e-4 || facing.normalize().dot(toAtt.normalize()) < 0.3) return false;
        if (RND.nextDouble() >= cfg("block-chance", 0.35)) return false;
        z.getWorld().playSound(z.getLocation(), "item.shield.block", 1f, 1f);
        return true;
    }

    // ================================================================ targeting (players only)
    private static boolean fightable(Player owner, Player q, Zombie z, double range) {
        if (q == null || !q.isOnline() || q.isDead() || !q.getWorld().equals(z.getWorld())) return false;
        if (!Abilities.validTarget(owner, q)) return false;   // not the owner, not trusted
        GameMode gm = q.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
        return q.getLocation().distanceSquared(z.getLocation()) <= range * range;
    }

    private static Player nearestEnemy(Player owner, Zombie z, double range) {
        Player best = null;
        double bd = range * range;
        for (Player q : z.getWorld().getPlayers()) {
            if (!fightable(owner, q, z, range)) continue;
            double d = q.getLocation().distanceSquared(z.getLocation());
            if (d < bd) { bd = d; best = q; }
        }
        return best;
    }

    private static Player pickTarget(Player owner, Clone c) {
        if (c.provoked != null) {   // whoever hit it comes first
            Player pp = Bukkit.getPlayer(c.provoked);
            if (fightable(owner, pp, c.z, 80)) return pp;
            c.provoked = null;
        }
        return c.hunter ? nearestEnemy(owner, c.z, 64) : null;   // runners only fight back
    }

    // ================================================================ the fighter
    private static double maxHealth(Zombie z) {
        AttributeInstance a = z.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private static int needTicks(ItemStack it) {
        String n = it == null ? "" : it.getType().name();
        if (n.endsWith("_AXE")) return 18;
        if (n.equals("MACE")) return 16;
        if (n.equals("TRIDENT") || n.endsWith("_SPEAR")) return 13;
        return 11;   // swords and everything else: a nearly full charge
    }

    /** Damage per hit from the weapon in hand (like a player's), with Sharpness and Strength/Weakness. */
    private static double damageOf(Zombie z) {
        ItemStack it = z.getEquipment() == null ? null : z.getEquipment().getItemInMainHand();
        String n = it == null ? "" : it.getType().name();
        double d;
        if (n.endsWith("_SWORD")) d = n.startsWith("NETHERITE") ? 8 : n.startsWith("DIAMOND") ? 7 : n.startsWith("IRON") ? 6 : n.startsWith("STONE") ? 5 : 4;
        else if (n.endsWith("_AXE")) d = n.startsWith("NETHERITE") ? 10 : (n.startsWith("WOODEN") || n.startsWith("GOLDEN")) ? 7 : 9;
        else if (n.equals("MACE")) d = 6;
        else if (n.equals("TRIDENT")) d = 9;
        else d = cfg("clone-damage", 6);   // anything else (a spear, an empty hand ...)
        if (it != null) {
            int sharp = it.getEnchantmentLevel(Enchantment.SHARPNESS);
            if (sharp > 0) d += 0.5 + 0.5 * sharp;
        }
        PotionEffect st = z.getPotionEffect(PotionEffectType.STRENGTH);
        if (st != null) d += 3.0 * (st.getAmplifier() + 1);
        PotionEffect wk = z.getPotionEffect(PotionEffectType.WEAKNESS);
        if (wk != null) d = Math.max(1, d - 4.0 * (wk.getAmplifier() + 1));
        return d;
    }

    private static void tick(Player o, Clone c, int t) {
        final Zombie z = c.z;
        if (!z.isValid() || z.isDead()) return;
        c.cd++;
        if (c.jumpCd > 0) c.jumpCd--;
        if (c.eatCd > 0) c.eatCd--;
        if (c.backoff > 0) c.backoff--;
        final double speed = cfg("clone-speed", 0.26);

        Player tg = c.target == null ? null : Bukkit.getPlayer(c.target);
        boolean stale = !fightable(o, tg, z, 80);
        if (t % 10 == 0 || (stale && t % 4 == 0)) { tg = pickTarget(o, c); c.target = tg == null ? null : tg.getUniqueId(); }
        else if (stale) tg = null;

        // low on health: back off and eat a golden apple
        double max = maxHealth(z);
        if (c.eat > 0) { eatStep(z, c, tg, speed); return; }
        if (c.apples > 0 && c.eatCd == 0 && z.getHealth() < max * cfg("eat-below-fraction", 0.5)) { startEat(z, c); return; }

        if (tg == null) { idle(o, c, t); return; }

        Location zl = z.getLocation(), tl = tg.getLocation();
        Vector to = tl.toVector().subtract(zl.toVector());
        double dy = to.getY();
        to.setY(0);
        double dist = to.length();
        Vector fwd = dist < 1e-3 ? new Vector(1, 0, 0) : to.clone().normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-fwd.getX(), fwd.getZ()));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.1, dist)));
        z.setRotation(yaw, Math.max(-60f, Math.min(60f, pitch)));

        boolean los = z.hasLineOfSight(tg);
        if (dist > 7 || (!los && dist > 2.5)) {   // far (or something in the way): let the pathfinder bring us in, sprinting
            if (t % 4 == 0) z.getPathfinder().moveTo(tl, 1.2);
            c.sprinting = true;
            return;
        }
        z.getPathfinder().stopPathfinding();   // close: we steer ourselves

        // ---- movement: keep about 2.6 blocks away and circle-strafe, flipping direction now and then
        Vector side = new Vector(-fwd.getZ(), 0, fwd.getX());
        if (--c.strafeT <= 0) {
            if (RND.nextDouble() < 0.7) c.strafeDir = -c.strafeDir;
            c.strafeT = 8 + RND.nextInt(18);
        }
        double want = 2.9;   // outspace: stay at the very edge of reach (3 blocks)
        double radial = dist > want + 0.3 ? 1.0 : (dist < want - 0.5 ? -1.0 : 0.15);
        Vector move;
        if (c.backoff > 0) move = fwd.clone().multiply(-0.8).add(side.clone().multiply(c.strafeDir * 0.5));   // w-tap: step off after a hit, then sprint back in
        else move = fwd.clone().multiply(radial * 0.8).add(side.clone().multiply(c.strafeDir * 0.9));
        if (move.lengthSquared() > 1e-4) move.normalize().multiply(speed);
        c.sprinting = c.backoff == 0 && radial > 0;

        boolean grounded = z.isOnGround();
        double vy = z.getVelocity().getY();
        boolean jump = false;
        if (grounded && move.lengthSquared() > 1e-4) {   // hop over a one-block ledge
            Block ahead = zl.clone().add(move.clone().normalize().multiply(0.9)).getBlock();
            if (!ahead.isPassable() && ahead.getRelative(0, 1, 0).isPassable() && ahead.getRelative(0, 2, 0).isPassable()) jump = true;
        }

        // ---- attacking: jump when the weapon is almost recharged, hit on the way down for a critical
        int need = needTicks(z.getEquipment() == null ? null : z.getEquipment().getItemInMainHand());
        boolean inReach = dist <= 3.2 && Math.abs(dy) < 2.2 && los;
        if (inReach && grounded && c.cd >= need - 3 && c.jumpCd == 0 && RND.nextDouble() < 0.85) { jump = true; c.jumpCd = 16; }
        if (grounded && z.getNoDamageTicks() > 15 && c.jumpCd == 0 && RND.nextDouble() < 0.5) { jump = true; c.jumpCd = 10; }   // jump-reset: just got hit, jump to cut the knockback
        boolean falling = !grounded && vy < -0.03;
        boolean ready = inReach && c.cd >= need && tg.getNoDamageTicks() <= 10;
        if (ready && (falling || c.cd >= need + 6)) swing(z, c, tg, falling && !z.isInWater());

        z.setVelocity(new Vector(move.getX(), jump ? 0.42 : vy, move.getZ()));
    }

    /** One attack. Critical (falling) hits do 1.5x; a sprinting clone adds a bit of knockback. */
    private static void swing(Zombie z, Clone c, LivingEntity tg, boolean crit) {
        double dmg = damageOf(z) * (crit ? 1.5 : 1.0);
        z.swingMainHand();
        swinging = true;
        boolean stun = false;
        ItemStack hand = z.getEquipment() == null ? null : z.getEquipment().getItemInMainHand();
        if (hand != null && hand.getType().name().endsWith("_AXE") && tg instanceof Player tp && tp.isBlocking()) {   // stun slam: an axe disables the shield
            tp.setCooldown(Material.SHIELD, (int) cfg("stun-ticks", 100));
            z.getWorld().playSound(tp.getLocation(), "item.shield.break", 1f, 1f);
            stun = true;
        }
        try { tg.damage(dmg, z); } finally { swinging = false; }
        if (c.sprinting && tg.isValid()) {
            Vector kb = tg.getLocation().toVector().subtract(z.getLocation().toVector()).setY(0);
            if (kb.lengthSquared() > 1e-4) tg.setVelocity(tg.getVelocity().add(kb.normalize().multiply(0.35).setY(0.06)));
        }
        Location mid = tg.getLocation().add(0, tg.getHeight() * 0.6, 0);
        if (stun) Fx.part(mid, Particle.CRIT, 20, .4, .4, .4, 0.2);
        if (crit) { Fx.part(mid, Particle.CRIT, 12, .3, .3, .3, 0.1); z.getWorld().playSound(mid, "entity.player.attack.crit", 1f, 1f); }
        else z.getWorld().playSound(mid, "entity.player.attack.strong", 1f, 1f);
        c.cd = 0;
        c.backoff = 3;
    }

    // ================================================================ golden apples
    private static void startEat(Zombie z, Clone c) {
        EntityEquipment eq = z.getEquipment();
        if (eq == null) return;
        c.saved = eq.getItemInMainHand().clone();
        eq.setItemInMainHand(new ItemStack(Material.GOLDEN_APPLE));
        c.apples--;
        c.eat = 32;   // 1.6 seconds, like a player
        z.getPathfinder().stopPathfinding();
        c.bubble = false;
        if (cfg("web-bubble", 1) > 0) {   // "bubbling": cobwebs at the feet and head block melee while healing (spears still reach through)
            List<Block> webs = new ArrayList<>();
            for (int dy = 0; dy <= 1; dy++) {
                Block b = z.getLocation().add(0, dy, 0).getBlock();
                if (b.getType().isAir()) { b.setType(Material.COBWEB); webs.add(b); }
            }
            if (!webs.isEmpty()) {
                c.bubble = true;
                new BukkitRunnable() { @Override public void run() { for (Block b : webs) if (b.getType() == Material.COBWEB) b.setType(Material.AIR); } }
                        .runTaskLater(Aegis.I, (long) (cfg("web-seconds", 6) * 20));
            }
        }
    }

    private static void eatStep(Zombie z, Clone c, Player tg, double speed) {
        c.eat--;
        if (tg != null && !c.bubble) {   // run away from the fight while eating (unless it is bubbled in cobwebs)
            Vector away = z.getLocation().toVector().subtract(tg.getLocation().toVector()).setY(0);
            if (away.lengthSquared() > 1e-4) {
                away.normalize().multiply(speed * 0.9);
                z.setVelocity(new Vector(away.getX(), z.getVelocity().getY(), away.getZ()));
            }
        }
        if (c.eat % 4 == 0) {
            Location head = z.getEyeLocation();
            z.getWorld().playSound(head, "entity.generic.eat", 0.9f, 1f);
            z.getWorld().spawnParticle(Particle.ITEM, head, 6, .2, .1, .2, .05, new ItemStack(Material.GOLDEN_APPLE));
        }
        if (c.eat > 0) return;
        EntityEquipment eq = z.getEquipment();
        if (eq != null && c.saved != null) eq.setItemInMainHand(c.saved);
        c.saved = null;
        z.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));   // a golden apple: Regeneration II + Absorption I ...
        z.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
        z.setHealth(Math.min(maxHealth(z), z.getHealth() + cfg("apple-heal", 20)));    // ... plus a real heal (clones have a lot of health)
        z.getWorld().playSound(z.getLocation(), "entity.player.burp", 0.8f, 1f);
        Fx.part(z.getLocation().add(0, 1, 0), Particle.HEART, 5, .3, .4, .3, 0);
        c.eatCd = 60;   // a short breather before the next one
    }

    // ================================================================ no enemy to fight
    private static void idle(Player o, Clone c, int t) {
        Zombie z = c.z;
        if (t % 20 != 0) return;
        if (c.hunter) {   // nobody to hunt: stay close to the owner
            if (z.getWorld().equals(o.getWorld()) && z.getLocation().distanceSquared(o.getLocation()) > 144) z.getPathfinder().moveTo(o.getLocation(), 1.1);
            return;
        }
        Player near = nearestEnemy(o, z, 25);   // runners keep away from players and roam
        Vector dir;
        if (near != null) dir = z.getLocation().toVector().subtract(near.getLocation().toVector()).setY(0);
        else if (t % 40 == 0) { double a = RND.nextDouble() * Math.PI * 2; dir = new Vector(Math.cos(a), 0, Math.sin(a)); }
        else return;
        if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
        z.getPathfinder().moveTo(z.getLocation().add(dir.normalize().multiply(20)), 1.25);
    }
}
