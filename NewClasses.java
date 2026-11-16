package dev.aegis;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rogue (Stealth, Shadow Step, backstab / blink passives) and Yggdrasil (Vitality Boost, World Tree Root, life passives). */
final class NewClasses {
    static final Map<UUID, Long> stealth = new HashMap<>();
    static final Map<UUID, Long> vitality = new HashMap<>();
    static final Map<UUID, Long> penalty = new HashMap<>();     // after Vitality Boost: weakened golden apples
    static final List<ArmorStand> decoys = new ArrayList<>();
    static final String KILL_MOD = "kill_hearts";

    private static NamespacedKey decoyKey() { return new NamespacedKey(Aegis.I, "decoy"); }

    static boolean isDecoy(Entity e) { return e.getPersistentDataContainer().has(decoyKey(), PersistentDataType.BYTE); }

    // ================================================================ ROGUE passives
    /** True when the attacker stands behind the victim (rear cone, in degrees). */
    static boolean isBackstab(Player att, LivingEntity vic, double cone) {
        Vector face = vic.getLocation().getDirection().setY(0);
        Vector to = att.getLocation().toVector().subtract(vic.getLocation().toVector()).setY(0);
        if (face.lengthSquared() < 1e-6 || to.lengthSquared() < 1e-6) return false;
        face.normalize();
        to.normalize();
        return face.dot(to) < -Math.cos(Math.toRadians(cone / 2.0));
    }

    /** Teleports the attacker right behind the victim (skipped when there is no room). */
    static void tpBehind(Player att, LivingEntity vic) {
        Bukkit.getScheduler().runTask(Aegis.I, () -> {
            if (!att.isOnline() || att.isDead() || vic.isDead() || !att.getWorld().equals(vic.getWorld())) return;
            Vector face = vic.getLocation().getDirection().setY(0);
            if (face.lengthSquared() < 1e-6) return;
            face.normalize();
            Location dest = vic.getLocation().clone().subtract(face.multiply(1.4));
            if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()) return;
            dest.setYaw(vic.getLocation().getYaw());
            dest.setPitch(att.getLocation().getPitch());
            att.teleport(dest);
        });
    }

    // ================================================================ ROGUE: Stealth
    static boolean stealth(Player p) {
        double sec = Abilities.secs(p, Abilities.v(p, 1, "seconds", 10));
        int ticks = (int) Math.round(sec * 20);
        int speed = (int) Abilities.v(p, 1, "speed-level", 3);
        Status.set(stealth, p, sec);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, Math.max(0, speed - 1), false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, ticks, 0, false, false, true));
        hideFromAll(p);
        UUID id = p.getUniqueId();
        new BukkitRunnable() {
            @Override public void run() {
                Player q = Bukkit.getPlayer(id);
                if (q == null) { cancel(); return; }
                if (!Status.on(stealth, q)) { endStealth(q); cancel(); return; }   // ran out (or ended early)
                hideFromAll(q);                                                    // also catches people who just joined
            }
        }.runTaskTimer(Aegis.I, 5, 5);
        return true;
    }

    private static void hideFromAll(Player p) {
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (v.equals(p) || Abilities.trusts(p, v)) continue;
            v.hideEntity(Aegis.I, p);
        }
    }

    static void endStealth(Player p) {
        boolean was = stealth.remove(p.getUniqueId()) != null;
        for (Player v : Bukkit.getOnlinePlayers()) if (!v.equals(p)) v.showEntity(Aegis.I, p);
        if (was) {
            p.removePotionEffect(PotionEffectType.SPEED);
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            Passives.refresh(p);   // puts the permanent speed / invisibility back
        }
    }

    // ================================================================ ROGUE: Shadow Step
    static boolean shadowStep(Player p) {
        double range = Abilities.v(p, 2, "range", 30);
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        RayTraceResult hit = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double dist = hit == null ? range : Math.max(0, hit.getHitPosition().distance(eye.toVector()) - 0.7);

        Location dest = null;
        for (double d = dist; d >= 1.0; d -= 0.5) {
            Location feet = eye.clone().add(dir.clone().multiply(d)).subtract(0, p.getEyeHeight(), 0);
            if (feet.getBlock().isPassable() && feet.clone().add(0, 1, 0).getBlock().isPassable()) { dest = feet; break; }
        }
        if (dest == null) { Abilities.msg(p, "No room to step there."); return false; }

        Location from = p.getLocation();
        dest.setYaw(from.getYaw());
        dest.setPitch(from.getPitch());
        if (!p.teleport(dest)) return false;

        Vector away = dir.clone().setY(0);
        if (away.lengthSquared() < 1e-4) away = from.getDirection().setY(0).multiply(-1);
        if (away.lengthSquared() < 1e-4) away = new Vector(0, 0, 1);
        away.normalize().multiply(-1);   // opposite of where you went
        spawnDecoy(p, from, away);
        return true;   // no particles on purpose: it is a trick
    }

    private static ItemStack cp(ItemStack s) { return s == null ? null : s.clone(); }

    private static void spawnDecoy(Player p, Location from, Vector away) {
        double speed = Abilities.v(p, 2, "decoy-speed", 0.3);
        int life = (int) Math.round(Abilities.v(p, 2, "decoy-seconds", 4) * 20);
        Location at = from.clone();
        at.setDirection(away);
        at.setPitch(0);
        PlayerInventory inv = p.getInventory();
        ItemStack head = cp(inv.getHelmet());
        if (head == null || head.getType().isAir()) {
            head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(p);
            head.setItemMeta(sm);
        }
        final ItemStack helmet = head;
        ArmorStand st = from.getWorld().spawn(at, ArmorStand.class, a -> {
            a.setArms(true);
            a.setBasePlate(false);
            a.setVisible(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setGravity(false);
            a.setPersistent(false);
            a.setCanPickupItems(false);
            a.getPersistentDataContainer().set(decoyKey(), PersistentDataType.BYTE, (byte) 1);
            EntityEquipment eq = a.getEquipment();
            eq.setHelmet(helmet);
            eq.setChestplate(cp(inv.getChestplate()));
            eq.setLeggings(cp(inv.getLeggings()));
            eq.setBoots(cp(inv.getBoots()));
            eq.setItemInMainHand(cp(inv.getItemInMainHand()));
        });
        decoys.add(st);

        final Vector run = away.clone();
        new BukkitRunnable() {
            final Location cur = at.clone();
            int t = 0;
            boolean moving = true;
            @Override public void run() {
                if (!st.isValid() || t >= life) { st.remove(); decoys.remove(st); cancel(); return; }
                if (moving) {
                    Location next = cur.clone().add(run.getX() * speed, 0, run.getZ() * speed);
                    if (!next.getBlock().isPassable()) {
                        if (next.clone().add(0, 1, 0).getBlock().isPassable() && next.clone().add(0, 2, 0).getBlock().isPassable()) next.add(0, 1, 0);
                        else moving = false;
                    } else if (!next.clone().add(0, 1, 0).getBlock().isPassable()) {
                        moving = false;
                    }
                    if (moving) {
                        int drop = 0;
                        while (drop < 3 && next.clone().add(0, -1, 0).getBlock().isPassable()) { next.add(0, -1, 0); drop++; }
                        cur.setX(next.getX());
                        cur.setY(next.getY());
                        cur.setZ(next.getZ());
                    }
                }
                if (t % 2 == 0) {
                    double sw = moving ? Math.sin(t * 0.9) * 0.9 : 0;
                    st.setRightLegPose(new EulerAngle(sw, 0, 0));
                    st.setLeftLegPose(new EulerAngle(-sw, 0, 0));
                    st.setRightArmPose(new EulerAngle(-sw, 0, 0));
                    st.setLeftArmPose(new EulerAngle(sw, 0, 0));
                }
                st.teleport(cur);
                t++;
            }
        }.runTaskTimer(Aegis.I, 1, 1);
    }

    static void popDecoy(ArmorStand st) {
        decoys.remove(st);
        st.remove();
    }

    // ================================================================ YGGDRASIL: Vitality Boost
    static boolean vitality(Player p) {
        if (Status.on(vitality, p)) return false;
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return false;
        double sec = Abilities.secs(p, Abilities.v(p, 1, "seconds", 20));
        int ticks = (int) Math.round(sec * 20);
        double mul = Abilities.v(p, 1, "health-multiplier", 2.0);
        Status.healthAdd(p, "tmp_vitality", a.getValue() * (mul - 1.0), ticks);
        p.setHealth(Math.min(a.getValue(), p.getHealth() + Abilities.v(p, 1, "instant-heal", 20)));
        Status.set(vitality, p, sec);

        UUID id = p.getUniqueId();
        double pen = Abilities.v(p, 1, "penalty-seconds", 10);
        Bukkit.getScheduler().runTaskLater(Aegis.I, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q == null || vitality.remove(id) == null) return;   // died / quit / class change in the meantime
            Status.set(penalty, q, pen);
        }, ticks + 1L);
        return true;
    }

    /** During the penalty after Vitality Boost, golden apples only give Regeneration I and less saturation. */
    static boolean gapPenalty(Player p, Material m) {
        if (m != Material.GOLDEN_APPLE && m != Material.ENCHANTED_GOLDEN_APPLE) return false;
        if (Aegis.I.pd(p).type != ClassType.YGGDRASIL || !Status.on(penalty, p)) return false;
        final float before = p.getSaturation();
        final double bonus = Abilities.v(p, 1, "penalty-saturation", 4.8);
        Bukkit.getScheduler().runTask(Aegis.I, () -> {
            if (!p.isOnline()) return;
            PotionEffect r = p.getPotionEffect(PotionEffectType.REGENERATION);
            if (r != null && !r.isInfinite() && r.getAmplifier() > 0) {
                p.removePotionEffect(PotionEffectType.REGENERATION);
                Passives.refresh(p);   // permanent Regeneration I comes back
            }
            p.setSaturation((float) Math.min(p.getFoodLevel(), before + bonus));
        });
        return true;
    }

    // ================================================================ YGGDRASIL: World Tree Root
    static boolean worldTreeRoot(Player p) {
        LivingEntity t = Abilities.target(p, Abilities.v(p, 2, "range", 30));
        if (t == null) { Abilities.msg(p, "No target in sight."); return false; }
        double sec = Abilities.secs(p, Abilities.v(p, 2, "freeze-seconds", 4));
        if (t instanceof Player) Status.set(Status.freeze, t, sec);
        else Status.stun(t, sec);
        Abilities.hurt(t, Abilities.v(p, 2, "damage", 4.0), p);
        roots(t, (int) Math.round(sec * 20));
        return true;
    }

    private static void roots(LivingEntity t, int ticks) {
        World w = t.getWorld();
        Location base = t.getLocation();
        List<BlockDisplay> list = new ArrayList<>();
        int n = 8;
        for (int i = 0; i < n; i++) {
            double ang = Math.PI * 2 * i / n;
            double r = 0.55 + (i % 2) * 0.25;
            Location l = base.clone().add(Math.cos(ang) * r, -0.1, Math.sin(ang) * r);
            final float height = 1.3f + (i % 3) * 0.4f;
            BlockDisplay bd = w.spawn(l, BlockDisplay.class, x -> {
                x.setBlock(Material.MANGROVE_ROOTS.createBlockData());
                x.setTransformation(new Transformation(new Vector3f(-0.15f, 0f, -0.15f), new AxisAngle4f(), new Vector3f(0.3f, 0.01f, 0.3f), new AxisAngle4f()));
                x.setPersistent(false);
            });
            list.add(bd);
            Bukkit.getScheduler().runTaskLater(Aegis.I, () -> {
                if (!bd.isValid()) return;
                bd.setInterpolationDelay(0);
                bd.setInterpolationDuration(6);
                bd.setTransformation(new Transformation(new Vector3f(-0.15f, 0f, -0.15f), new AxisAngle4f(), new Vector3f(0.3f, height, 0.3f), new AxisAngle4f()));
            }, 1L);
        }
        Fx.block(base.clone().add(0, 0.2, 0), Material.MANGROVE_ROOTS, 30, .6);
        w.playSound(base, "block.mangrove_roots.break", 1f, 0.8f);
        Bukkit.getScheduler().runTaskLater(Aegis.I, () -> { for (BlockDisplay b : list) b.remove(); }, Math.max(1, ticks));
    }

    // ================================================================ YGGDRASIL passives
    static double potHeal(ConfigurationSection n) {
        int lvl = Math.max(1, Math.min(10, n.getInt("instant-heal-level", 3)));
        return 4 << (lvl - 1);
    }

    /** HP a drinkable healing potion gives in vanilla. */
    static double vanillaHeal(ItemStack it) {
        if (!(it.getItemMeta() instanceof PotionMeta pm)) return 0;
        double sum = 0;
        PotionType bt = pm.getBasePotionType();
        if (bt == PotionType.HEALING) sum += 4;
        else if (bt == PotionType.STRONG_HEALING) sum += 8;
        for (PotionEffect ef : pm.getCustomEffects())
            if (ef.getType().equals(PotionEffectType.INSTANT_HEALTH)) sum += 4 << Math.min(8, ef.getAmplifier());
        return sum;
    }

    static void heal(Player p, double hp) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        double max = a == null ? 20 : a.getValue();
        p.setHealth(Math.min(max, p.getHealth() + hp));
    }

    static boolean isDay(Player p) {
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return false;
        long t = w.getTime();
        return t >= 0 && t < 12000;
    }

    /** +kill-hp max health per kill, up to kill-max-hp extra. Lost on death or when the passive is lost. */
    static void addKillHeart(Player k, ConfigurationSection n) {
        AttributeInstance a = k.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        NamespacedKey key = new NamespacedKey(Aegis.I, KILL_MOD);
        double cur = 0;
        for (AttributeModifier m : new ArrayList<>(a.getModifiers()))
            if (m.getKey().equals(key)) { cur = m.getAmount(); a.removeModifier(m); }
        double next = Math.min(n.getDouble("kill-max-hp", 4.0), cur + n.getDouble("kill-hp", 2.0));
        if (next <= 0) return;
        a.addModifier(new AttributeModifier(key, next, AttributeModifier.Operation.ADD_NUMBER));
        k.setHealth(Math.min(a.getValue(), k.getHealth() + (next - cur)));   // the new heart comes filled
    }

    static void clearKillHearts(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        NamespacedKey key = new NamespacedKey(Aegis.I, KILL_MOD);
        boolean changed = false;
        for (AttributeModifier m : new ArrayList<>(a.getModifiers()))
            if (m.getKey().equals(key)) { a.removeModifier(m); changed = true; }
        if (changed && p.getHealth() > a.getValue()) p.setHealth(a.getValue());
    }

    static void syncKillHearts(Player p) {
        if (!Passives.has(p, "kills")) clearKillHearts(p);
    }

    // ================================================================ cleanup
    static void cleanup(Player p) {
        endStealth(p);
        vitality.remove(p.getUniqueId());
        penalty.remove(p.getUniqueId());
        MoreClasses.cleanup(p);
    }

    static void shutdown() {
        for (ArmorStand a : new ArrayList<>(decoys)) a.remove();
        decoys.clear();
        MoreClasses.shutdown();
    }
}
