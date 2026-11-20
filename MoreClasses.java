package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Jackpot, Fateweaver, Singularity and Spartan. */
final class MoreClasses {
    // Jackpot
    static final Map<UUID, int[]> luck = new HashMap<>();          // {mode, hitsLeft}  mode 1 = x2, 2 = x0.5, 3 = high roller
    static final Map<UUID, Long> noEffects = new HashMap<>();      // potion effects switched off
    // Fateweaver
    static final Map<UUID, UUID> links = new HashMap<>();          // both directions
    static final Map<UUID, UUID> marks = new HashMap<>();          // caster -> entity marked for the next Weave of Fate
    static final Map<UUID, Long> markEnd = new HashMap<>();
    static boolean mirroring = false;
    // Singularity
    static final Map<UUID, Boolean> slam = new HashMap<>();
    static final Map<UUID, Location> safe = new HashMap<>();
    // Spartan
    static final Map<UUID, UUID> steeds = new HashMap<>();
    static final Map<UUID, Location> lastPos = new HashMap<>();
    static final Map<UUID, Double> speed = new HashMap<>();        // blocks per second

    private static void say(Player p, String s, NamedTextColor c) { p.sendMessage(Component.text(s, c)); }

    private static NamespacedKey steedKey() { return new NamespacedKey(Aegis.I, "steed"); }

    static boolean isSteed(Entity e) { return e.getPersistentDataContainer().has(steedKey(), PersistentDataType.BYTE); }

    static double speedOf(Player p) { return speed.getOrDefault(p.getUniqueId(), 0.0); }

    // Spartan: the air-momentum passive can be switched on and off (stored on the player, survives restarts)
    private static NamespacedKey airKey() { return new NamespacedKey(Aegis.I, "air_control_off"); }

    static boolean airOff(Player p) { return p.getPersistentDataContainer().has(airKey(), PersistentDataType.BYTE); }

    static void toggleAir(Player p) {
        boolean nowOff;
        if (airOff(p)) { p.getPersistentDataContainer().remove(airKey()); nowOff = false; }
        else { p.getPersistentDataContainer().set(airKey(), PersistentDataType.BYTE, (byte) 1); nowOff = true; }
        p.sendActionBar(Component.text("Air Control: " + (nowOff ? "OFF" : "ON"), nowOff ? NamedTextColor.RED : NamedTextColor.GREEN));
        p.getWorld().playSound(p.getLocation(), "ui.button.click", 0.7f, nowOff ? 0.8f : 1.4f);
    }

    // ================================================================ ticker
    static void startTicker() {
        new BukkitRunnable() {
            int c = 0;
            @Override public void run() { c++; tickAll(c); }
        }.runTaskTimer(Aegis.I, 1, 1);
    }

    private static void tickAll(int c) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            MegaMace.tick(p);
            UltImpl.tick(p, c);
            ClassType t = Aegis.I.pd(p).type;
            if (t == null) continue;
            switch (t) {
                case FATEWEAVER -> climb(p);
                case SINGULARITY -> { if (c % 10 == 0) rememberSafe(p); }
                case SPARTAN -> spartanTick(p, c);
                default -> { }
            }
        }
    }

    // ================================================================ shared: an item that is not used up
    /** Call while the use event is still running: gives the item back on the next tick. */
    static void keepUse(Player p, EquipmentSlot hand) {
        PlayerInventory inv = p.getInventory();
        final int slot = hand == EquipmentSlot.OFF_HAND ? 40 : inv.getHeldItemSlot();
        ItemStack now = inv.getItem(slot);
        if (now == null || now.getType().isAir()) return;
        final ItemStack orig = now.clone();
        Bukkit.getScheduler().runTask(Aegis.I, () -> {
            if (!p.isOnline()) return;
            ItemStack cur = inv.getItem(slot);
            if (cur == null || cur.getType().isAir()) inv.setItem(slot, orig);
            else if (cur.getType() == orig.getType()) { if (cur.getAmount() < orig.getAmount()) cur.setAmount(orig.getAmount()); }
            else if (orig.getAmount() == 1) inv.setItem(slot, orig);   // potion -> glass bottle and so on
        });
    }

    // ================================================================ JACKPOT
    static void shuffleOne(Player v) {
        PlayerInventory inv = v.getInventory();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int a = r.nextInt(9), b = r.nextInt(8);
        if (b >= a) b++;
        ItemStack x = inv.getItem(a), y = inv.getItem(b);
        inv.setItem(a, y);
        inv.setItem(b, x);
    }

    /** Every hit re-rolls the sword's Sharpness; this hit already uses the rolled level. */
    static double rollSharp(Player p, ConfigurationSection n, double dmg) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (!it.getType().name().endsWith("_SWORD")) return dmg;
        int lo = n.getInt("sharp-min", 1), hi = Math.max(lo, n.getInt("sharp-max", 10));
        int lvl = lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
        int cur = it.getEnchantmentLevel(Enchantment.SHARPNESS);
        double bonus = (lvl > 0 ? 0.5 * lvl + 0.5 : 0) - (cur > 0 ? 0.5 * cur + 0.5 : 0);
        ItemMeta m = it.getItemMeta();
        m.addEnchant(Enchantment.SHARPNESS, lvl, true);
        it.setItemMeta(m);
        p.sendActionBar(Component.text("Sharpness " + lvl, NamedTextColor.GOLD));
        return Math.max(0, dmg + bonus);
    }

    /** Applies Luck of the Draw / High Roller to a melee hit. */
    static double luckHit(Player p, double dmg) {
        int[] st = luck.get(p.getUniqueId());
        if (st == null) return dmg;
        double out = dmg;
        switch (st[0]) {
            case 1 -> out = dmg * 2;
            case 2 -> out = dmg * 0.5;
            case 3 -> out = ThreadLocalRandom.current().nextBoolean() ? dmg * 2 : 0;
            default -> { }
        }
        st[1]--;
        if (st[1] <= 0) {
            luck.remove(p.getUniqueId());
            Aegis.I.pd(p).endActive(st.length > 2 ? st[2] : 1);   // the lucky hits are used up: the cooldown starts now
            p.sendActionBar(Component.text("Your luck ran out.", NamedTextColor.GRAY));
        } else {
            p.sendActionBar(Component.text(st[1] + " lucky hits left", NamedTextColor.GOLD));
        }
        return out;
    }

    static boolean luckDraw(Player p) {
        int hits = (int) Abilities.v(p, 1, "hits", 5);
        double sec = Abilities.secs(p, Abilities.v(p, 1, "seconds", 10));
        UUID id = p.getUniqueId();
        switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> { luck.put(id, new int[]{1, hits, 1}); Abilities.activeHint(p, 120); say(p, "LUCKY! Your next " + hits + " hits deal DOUBLE damage.", NamedTextColor.GOLD); }
            case 1 -> { luck.put(id, new int[]{2, hits, 1}); Abilities.activeHint(p, 120); say(p, "Unlucky... your next " + hits + " hits deal HALF damage.", NamedTextColor.RED); }
            case 2 -> { doubleEffects(p, sec); Abilities.activeHint(p, sec); say(p, "LUCKY! Your potion effect levels are doubled for " + (int) sec + "s.", NamedTextColor.GOLD); }
            default -> { wipeEffects(p, sec); Abilities.activeHint(p, sec); say(p, "Unlucky... your potion effects are gone for " + (int) sec + "s.", NamedTextColor.RED); }
        }
        p.getWorld().playSound(p.getLocation(), "block.note_block.bell", 1f, 1.2f);
        return true;
    }

    static boolean highRoller(Player p) {
        int hits = (int) Abilities.v(p, 2, "hits", 5);
        luck.put(p.getUniqueId(), new int[]{3, hits, 2});
        Abilities.activeHint(p, 120);
        say(p, "High Roller! Your next " + hits + " hits: 50% double damage, 50% no damage.", NamedTextColor.GOLD);
        p.getWorld().playSound(p.getLocation(), "block.note_block.bell", 1f, 0.8f);
        return true;
    }

    private static void doubleEffects(Player p, double sec) {
        List<PotionEffect> saved = new ArrayList<>(p.getActivePotionEffects());
        if (saved.isEmpty()) return;
        final int ticks = (int) Math.round(sec * 20);
        for (PotionEffect e : saved) {
            p.removePotionEffect(e.getType());
            int amp = Math.min(254, e.getAmplifier() * 2 + 1);   // level L becomes level 2L
            int dur = e.isInfinite() ? ticks : Math.min(e.getDuration(), ticks);
            p.addPotionEffect(new PotionEffect(e.getType(), dur, amp, e.isAmbient(), e.hasParticles(), e.hasIcon()));
        }
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Aegis.I, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q == null) return;
            for (PotionEffect e : saved) {
                if (e.isInfinite()) continue;   // permanent ones come back through refresh
                int left = e.getDuration() - ticks;
                q.removePotionEffect(e.getType());
                if (left > 0) q.addPotionEffect(new PotionEffect(e.getType(), left, e.getAmplifier(), e.isAmbient(), e.hasParticles(), e.hasIcon()));
            }
            Passives.refresh(q);
        }, ticks + 1L);
    }

    private static void wipeEffects(Player p, double sec) {
        Status.set(noEffects, p, sec);
        final List<PotionEffect> saved = new ArrayList<>(p.getActivePotionEffects());
        for (PotionEffect e : saved) p.removePotionEffect(e.getType());
        final long ticks = (long) (sec * 20) + 1L;
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Aegis.I, () -> {
            noEffects.remove(id);
            Player q = Bukkit.getPlayer(id);
            if (q == null || q.isDead()) return;
            Passives.refresh(q);   // permanent effects
            for (PotionEffect e : saved) {   // everything that was taken away comes back with the time it had left
                if (e.isInfinite()) continue;
                long left = e.getDuration() - ticks;
                if (left > 0) q.addPotionEffect(new PotionEffect(e.getType(), (int) left, e.getAmplifier(), e.isAmbient(), e.hasParticles(), e.hasIcon()));
            }
        }, ticks);
    }

    // ================================================================ FATEWEAVER
    private static void climb(Player p) {
        if (!p.isSneaking()) return;
        ConfigurationSection n = Passives.first(p, "climb-speed");
        if (n == null) return;
        Location l = p.getLocation();
        Vector dir = l.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) return;
        dir.normalize();
        boolean wall = false;
        for (double dy : new double[]{0.4, 1.4})
            if (!l.clone().add(dir.clone().multiply(0.7)).add(0, dy, 0).getBlock().isPassable()) wall = true;
        if (!wall) return;
        double sp = n.getDouble("climb-speed", 0.22);
        double pitch = l.getPitch();
        double vy = pitch < -20 ? sp : (pitch > 20 ? -sp : 0.0);   // look up = climb, look down = slide down, level = cling
        p.setVelocity(new Vector(0, vy, 0));
        p.setFallDistance(0);
    }

    static LivingEntity targetAny(Player p, double range) {
        Location eye = p.getEyeLocation();
        World w = p.getWorld();
        RayTraceResult er = w.rayTraceEntities(eye, eye.getDirection(), range, 0.6, en -> en instanceof LivingEntity && !en.equals(p) && !NewClasses.isDecoy(en));
        if (er == null || !(er.getHitEntity() instanceof LivingEntity le)) return null;
        RayTraceResult br = w.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        if (br != null && br.getHitPosition().distance(eye.toVector()) < er.getHitPosition().distance(eye.toVector())) return null;
        return le;
    }

    static void unlink(UUID a) {
        UUID b = links.remove(a);
        if (b != null) links.remove(b);
    }

    /** First use marks the entity you look at; the next use links the marked entity with the one you look at. */
    static boolean weaveOfFate(Player p) {
        final UUID pid = p.getUniqueId();
        LivingEntity looked = targetAny(p, Abilities.v(p, 1, "range", 30));
        LivingEntity marked = null;
        UUID mid = marks.get(pid);
        if (mid != null) {
            Entity me = Bukkit.getEntity(mid);
            if (me instanceof LivingEntity le && le.isValid() && System.currentTimeMillis() < markEnd.getOrDefault(pid, 0L)) marked = le;
            else { marks.remove(pid); markEnd.remove(pid); }
        }
        if (marked == null) {
            if (looked == null) { Abilities.msg(p, "Look at an entity to mark it."); return false; }
            marks.put(pid, looked.getUniqueId());
            markEnd.put(pid, System.currentTimeMillis() + (long) (Abilities.v(p, 1, "mark-seconds", 30) * 1000));
            say(p, "Marked " + looked.getName() + ". Use Weave of Fate again on a second entity to link them.", NamedTextColor.LIGHT_PURPLE);
            showMark(pid, looked.getUniqueId());
            return false;   // no cooldown until the thread is tied
        }
        if (looked == null) { Abilities.msg(p, "Look at a second entity to link with " + marked.getName() + "."); return false; }
        if (looked.getUniqueId().equals(marked.getUniqueId())) { Abilities.msg(p, "Pick a different entity to link."); return false; }
        marks.remove(pid);
        markEnd.remove(pid);
        link(p, marked, looked);
        return true;
    }

    /** Purple sparkles above the marked entity until the mark is used or runs out. */
    private static void showMark(UUID pid, UUID target) {
        new BukkitRunnable() {
            @Override public void run() {
                Entity e = Bukkit.getEntity(target);
                if (!target.equals(marks.get(pid)) || e == null || !e.isValid() || System.currentTimeMillis() > markEnd.getOrDefault(pid, 0L)) { cancel(); return; }
                Location top = e.getLocation().add(0, e.getHeight() + 0.5, 0);
                Fx.dust(top, 4, .25, 0xD39BFF, 1.3f);
                Fx.part(top, Particle.WITCH, 3, .2, .2, .2, 0);
            }
        }.runTaskTimer(Aegis.I, 0, 8);
    }

    private static void link(Player p, LivingEntity a, LivingEntity b) {
        final UUID pid = p.getUniqueId(), ida = a.getUniqueId(), idb = b.getUniqueId();
        unlink(ida);
        unlink(idb);
        links.put(ida, idb);
        links.put(idb, ida);
        final long end = System.currentTimeMillis() + (long) (Abilities.secs(p, Abilities.v(p, 1, "seconds", 20)) * 1000);
        final double maxDist = Abilities.v(p, 1, "max-distance", 50);
        say(p, "A thread of fate now binds " + a.getName() + " and " + b.getName() + ".", NamedTextColor.LIGHT_PURPLE);
        new BukkitRunnable() {
            @Override public void run() {
                Entity x = Bukkit.getEntity(ida), y = Bukkit.getEntity(idb);
                boolean linked = idb.equals(links.get(ida));
                if (!linked || x == null || y == null || !x.isValid() || !y.isValid() || System.currentTimeMillis() > end
                        || !x.getWorld().equals(y.getWorld()) || x.getLocation().distance(y.getLocation()) > maxDist) {
                    if (linked) unlink(ida);
                    Player q = Bukkit.getPlayer(pid);
                    if (q != null) q.sendActionBar(Component.text("The thread of fate snaps.", NamedTextColor.LIGHT_PURPLE));
                    cancel();
                    return;
                }
                Location from = x.getLocation().add(0, x.getHeight() / 2, 0);
                Location to = y.getLocation().add(0, y.getHeight() / 2, 0);
                Vector v = to.toVector().subtract(from.toVector());
                double len = v.length();
                if (len > 0.1) {
                    v.normalize().multiply(0.5);
                    Location cur = from.clone();
                    for (double d = 0; d < len; d += 0.5) { Fx.dust(cur, 1, 0, 0xD39BFF, 0.9f); cur.add(v); }
                }
            }
        }.runTaskTimer(Aegis.I, 1, 4);
    }

    static boolean webWeave(Player p) {
        int r = (int) Abilities.v(p, 2, "radius", 5);
        Location c = p.getLocation();
        World w = c.getWorld();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z > r * r) continue;
                    Block b = w.getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z);
                    if (b.getType() == Material.COBWEB) b.setType(Material.AIR);
                }
        Fx.burst(c.clone().add(0, 1, 0), Particle.POOF, 25, .15);
        w.playSound(c, "block.cobweb.break", 1f, 0.8f);
        return true;
    }

    // ================================================================ SINGULARITY
    private static void rememberSafe(Player p) {
        if (p.isOnGround() && p.getLocation().getY() > p.getWorld().getMinHeight() + 2) safe.put(p.getUniqueId(), p.getLocation());
    }

    /** Void damage is cancelled and the player is put back on the last solid ground they stood on. */
    static void rescueFromVoid(Player p) {
        Bukkit.getScheduler().runTask(Aegis.I, () -> {
            if (!p.isOnline() || p.isDead()) return;
            Location s = safe.get(p.getUniqueId());
            if (s == null || !s.getWorld().equals(p.getWorld())) s = p.getWorld().getSpawnLocation();
            p.teleport(s);
            p.setFallDistance(0);
            p.setVelocity(new Vector(0, 0, 0));
        });
    }

    static boolean starfall(Player p) {
        final UUID id = p.getUniqueId();
        if (slam.containsKey(id)) return false;
        p.setVelocity(new Vector(0, Abilities.v(p, 1, "launch-velocity", 1.9), 0));
        slam.put(id, Boolean.TRUE);
        final double slamSpeed = Abilities.v(p, 1, "slam-speed", 2.6);
        Fx.burst(p.getLocation(), Particle.CLOUD, 30, .15);
        p.getWorld().playSound(p.getLocation(), "entity.firework_rocket.launch", 1f, 0.7f);
        new BukkitRunnable() {
            int t = 0;
            double lastY = p.getLocation().getY();
            boolean down = false;
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || t > 200) { slam.remove(id); Aegis.I.pd(p).endActive(1); cancel(); return; }
                Location l = p.getLocation();
                if (!down) {
                    if (t > 5 && l.getY() <= lastY + 0.01) down = true;   // reached the top
                    lastY = l.getY();
                    Fx.part(l, Particle.END_ROD, 3, .2, .1, .2, .02);
                }
                if (down) {
                    p.setVelocity(new Vector(0, -slamSpeed, 0));
                    p.setFallDistance(0);
                    Fx.part(l.clone().add(0, 1, 0), Particle.END_ROD, 4, .3, .5, .3, .05);
                    if (p.isOnGround() || !l.clone().subtract(0, 0.7, 0).getBlock().isPassable()) {
                        starImpact(p);
                        slam.remove(id);
                        cancel();
                        return;
                    }
                }
                t++;
            }
        }.runTaskTimer(Aegis.I, 2, 1);
        return true;
    }

    private static void starImpact(Player p) {
        Aegis.I.pd(p).endActive(1);   // Starfall is over: the cooldown starts now
        Location at = p.getLocation();
        World w = at.getWorld();
        double radius = Abilities.v(p, 1, "radius", 6), dmg = Abilities.v(p, 1, "damage", 6.0), power = Abilities.v(p, 1, "explosion-power", 4);
        boolean breakBlocks = Aegis.I.getConfig().getBoolean(Abilities.path(p, 1, "break-blocks"), false);
        for (Entity e : w.getNearbyEntities(at, radius, radius, radius)) {
            if (!Abilities.validTarget(p, e)) continue;
            LivingEntity le = (LivingEntity) e;
            Abilities.hurt(le, dmg, p);
            Vector away = le.getLocation().toVector().subtract(at.toVector());
            if (away.lengthSquared() > 1e-4) le.setVelocity(away.normalize().multiply(0.9).setY(0.5));
        }
        Abilities.exploding = true;   // vanilla explosion damage is cancelled, we dealt our own
        try { w.createExplosion(at, (float) power, false, breakBlocks, p); } finally { Abilities.exploding = false; }
        Fx.explosion(at, (float) power);
    }

    static boolean blackhole(Player p) {
        double range = Abilities.v(p, 2, "range", 25);
        Location eye = p.getEyeLocation();
        final World w = p.getWorld();
        RayTraceResult br = w.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        final Location center = br != null
                ? br.getHitPosition().toLocation(w).subtract(eye.getDirection().multiply(1.5))
                : eye.clone().add(eye.getDirection().multiply(range));
        final double radius = Abilities.v(p, 2, "radius", 14);
        final double implode = Abilities.v(p, 2, "implode-radius", 8);
        final double dmg = Abilities.v(p, 2, "damage", 4.0);
        final double pull = Abilities.v(p, 2, "pull", 0.85);
        final int life = (int) Math.round(Abilities.secs(p, Abilities.v(p, 2, "seconds", 5)) * 20);
        w.playSound(center, "entity.warden.sonic_charge", 1.5f, 0.5f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || t >= life) {
                    for (Entity e : w.getNearbyEntities(center, implode, implode, implode)) {
                        if (!Abilities.validTarget(p, e)) continue;
                        Abilities.hurt((LivingEntity) e, dmg, p);
                    }
                    Fx.explosion(center, 3f);
                    w.playSound(center, "entity.generic.explode", 1.5f, 0.5f);
                    cancel();
                    return;
                }
                for (Entity e : w.getNearbyEntities(center, radius, radius, radius)) {
                    if (e.equals(p) || e instanceof ArmorStand) continue;
                    if (e instanceof Player ep && Abilities.trusts(p, ep)) continue;
                    if (!(e instanceof LivingEntity) && !(e instanceof Item)) continue;
                    Vector to = center.toVector().subtract(e.getLocation().toVector());
                    double dist = to.length();
                    if (dist < 0.6) continue;
                    double f = Math.min(pull, 0.25 + 0.5 * (1 - Math.min(1, dist / radius)));
                    e.setVelocity(e.getVelocity().multiply(0.4).add(to.normalize().multiply(f)));
                }
                double ang = t * 0.5;
                for (int k = 0; k < 14; k++) {
                    double a = ang + k * 0.55, rr = 0.4 + k * 0.22;
                    Location pt = center.clone().add(Math.cos(a) * rr, Math.sin(k * 0.8) * 0.25, Math.sin(a) * rr);
                    Fx.dust(pt, 1, 0, k % 2 == 0 ? 0x5B1FD6 : 0x1A0638, 1.3f);
                }
                Fx.part(center, Particle.SQUID_INK, 2, .2, .2, .2, 0);
                Fx.part(center, Particle.REVERSE_PORTAL, 12, radius / 3, radius / 3, radius / 3, 0.8);
                t++;
            }
        }.runTaskTimer(Aegis.I, 0, 1);
        return true;
    }

    // ================================================================ SPARTAN
    static void breakShield(LivingEntity v, int ticks) {
        if (v instanceof Player vp) {
            vp.setCooldown(Material.SHIELD, ticks);
            if (vp.isBlocking()) vp.clearActiveItem();
        }
    }

    private static void spartanTick(Player p, int c) {
        UUID id = p.getUniqueId();
        Location now = p.getLocation();
        Location prev = lastPos.put(id, now);
        if (prev == null || !prev.getWorld().equals(now.getWorld())) { speed.put(id, 0.0); return; }
        double dx = now.getX() - prev.getX(), dy = now.getY() - prev.getY(), dz = now.getZ() - prev.getZ();
        speed.put(id, Math.sqrt(dx * dx + dy * dy + dz * dz) * 20.0);

        ConfigurationSection n = Passives.first(p, "air-boost");
        if (n == null || airOff(p)) return;
        if (p.isOnGround() || p.isGliding() || p.isInWater() || p.isInsideVehicle() || p.isFlying()) return;
        double h = Math.sqrt(dx * dx + dz * dz);
        if (h < 0.06 || h > n.getDouble("max-speed", 0.7)) return;
        double mul = n.getDouble("air-boost", 1.07);
        // keeps your air momentum (normal air drag is 0.91 per tick) so you glide further and steer more easily
        p.setVelocity(new Vector(dx * 0.91 * mul, (dy - 0.08) * 0.98, dz * 0.91 * mul));
    }

    private static void setAttr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    static boolean steed(Player p) {
        UUID id = p.getUniqueId();
        UUID old = steeds.remove(id);
        if (old != null) { Entity o = Bukkit.getEntity(old); if (o != null) o.remove(); }
        Location at = p.getLocation();
        Horse h = at.getWorld().spawn(at, Horse.class, hh -> {
            hh.setAdult();
            hh.setTamed(true);
            hh.setOwner(p);
            hh.setColor(Horse.Color.CHESTNUT);
            hh.setStyle(Horse.Style.NONE);
            hh.setPersistent(false);
            hh.getPersistentDataContainer().set(steedKey(), PersistentDataType.BYTE, (byte) 1);
            setAttr(hh, Attribute.MAX_HEALTH, Abilities.v(p, 1, "health", 30));
            setAttr(hh, Attribute.MOVEMENT_SPEED, Abilities.v(p, 1, "speed", 0.3375));
            setAttr(hh, Attribute.JUMP_STRENGTH, Abilities.v(p, 1, "jump", 1.0));
            AttributeInstance mh = hh.getAttribute(Attribute.MAX_HEALTH);
            hh.setHealth(mh == null ? 30 : mh.getValue());
            hh.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            hh.getInventory().setArmor(new ItemStack(Material.DIAMOND_HORSE_ARMOR));
        });
        steeds.put(id, h.getUniqueId());
        h.addPassenger(p);
        p.getWorld().playSound(at, "entity.horse.gallop", 1f, 1f);
        return true;
    }

    static boolean spartanCharge(Player p) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) return false;
        dir.normalize();
        final Vector fwd = dir.clone();
        p.setVelocity(dir.clone().multiply(Abilities.v(p, 2, "dash-speed", 1.9)).setY(0.2));
        final int shieldTicks = (int) Math.round(Abilities.v(p, 2, "shield-break-seconds", 20) * 20);
        final double dmg = Abilities.v(p, 2, "damage", 3.0), kb = Abilities.v(p, 2, "knockback", 1.1);
        final Set<UUID> hit = new HashSet<>();
        p.getWorld().playSound(p.getLocation(), "item.trident.riptide_1", 1f, 1.1f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || t >= 12) { cancel(); return; }
                Fx.part(p.getLocation().add(0, 1, 0), Particle.CLOUD, 4, .3, .4, .3, .02);
                for (Entity e : p.getNearbyEntities(2.2, 1.5, 2.2)) {
                    if (!Abilities.validTarget(p, e) || !hit.add(e.getUniqueId())) continue;
                    LivingEntity le = (LivingEntity) e;
                    Abilities.hurt(le, dmg, p);
                    breakShield(le, shieldTicks);
                    Vector away = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                    if (away.lengthSquared() < 1e-4) away = fwd.clone();
                    le.setVelocity(away.normalize().multiply(kb).setY(0.4));
                }
                t++;
            }
        }.runTaskTimer(Aegis.I, 1, 1);
        return true;
    }

    // ================================================================ cleanup
    static void cleanup(Player p) {
        UUID id = p.getUniqueId();
        luck.remove(id);
        noEffects.remove(id);
        unlink(id);
        marks.remove(id);
        markEnd.remove(id);
        slam.remove(id);
        safe.remove(id);
        lastPos.remove(id);
        speed.remove(id);
        UUID s = steeds.remove(id);
        if (s != null) { Entity h = Bukkit.getEntity(s); if (h != null) h.remove(); }
    }

    static void shutdown() {
        for (UUID s : new ArrayList<>(steeds.values())) { Entity h = Bukkit.getEntity(s); if (h != null) h.remove(); }
        steeds.clear();
    }
}
