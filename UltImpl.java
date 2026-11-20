package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** The ultimates. Registered in init(); the framework (choosing, slots, energy) lives in Ults. */
final class UltImpl {
    private static final Random RND = new Random();

    static final Set<PotionEffectType> POSITIVE = Set.of(PotionEffectType.SPEED, PotionEffectType.HASTE, PotionEffectType.STRENGTH,
            PotionEffectType.JUMP_BOOST, PotionEffectType.REGENERATION, PotionEffectType.RESISTANCE, PotionEffectType.FIRE_RESISTANCE,
            PotionEffectType.WATER_BREATHING, PotionEffectType.INVISIBILITY, PotionEffectType.NIGHT_VISION, PotionEffectType.HEALTH_BOOST,
            PotionEffectType.ABSORPTION, PotionEffectType.SATURATION, PotionEffectType.LUCK, PotionEffectType.SLOW_FALLING,
            PotionEffectType.CONDUIT_POWER, PotionEffectType.DOLPHINS_GRACE, PotionEffectType.HERO_OF_THE_VILLAGE);

    // ================================================================ registry
    static void init() {
        Ults.register("bloodbath", "Bloodbath", "A 30 block sea of blood: Poison II + Weakness II for 30s, you steal 2 hearts and every good potion effect from players inside.", 120, 30, false, false, UltImpl::bloodbath, null);
        Ults.register("indomitable", "Indomitable", "Invisible for 10s, +50 durability on every armor piece, no knockback for 30s.", 120, 30, false, false, UltImpl::indomitable, null);
        Ults.register("tides", "Tides of the Abyss", "A massive wave pushes everyone ahead of you for 4 hearts, then pulls them back with Mining Fatigue II + Slowness II for 30s.", 120, 6, false, false, UltImpl::tides, null);
        Ults.register("hell", "A Piece of Hell", "Obsidian dome with nether terrain for 10s: everyone inside burns (unextinguishable), can't use water, you deal 1.5x damage.", 180, 10, true, false, UltImpl::hell, null);
        Ults.register("workshop", "Workshop", "Obsidian dome workshop for 10s: Resistance III, and everything you craft has 1.5x durability, 2x mining speed and 1.15x damage.", 120, 10, false, false, UltImpl::workshop, null);
        Ults.register("skyruler", "Sky Ruler", "Glide and shoot into the sky. Press again to boost in your look direction; hits give boosts (max 3). Landing deals 3 hearts.", 180, 40, false, false, UltImpl::skyRuler, UltImpl::skyBoost);
        Ults.register("guardian", "True Guardian of the Deep", "Summons a full-strength Warden that fights like a wolf for 30s or until it dies.", 180, 30, true, false, UltImpl::guardian, null);
        Ults.register("goldengod", "Golden God", "30s: apples in hand become golden apples, blocks in hand become gold blocks, Strength II and 20 hearts.", 3600, 30, false, false, UltImpl::goldenGod, null);
        Ults.register("shadows", "Shadow Manipulation", "10 clones (your armor and effects, 100 HP) rise from a 30 block shadow pool: 5 hunt, 5 run. Everyone in the pool is blinded.", 120, 30, false, false, UltImpl::shadows, null);
        Ults.register("idolgamble", "Idol Death Gamble", "Obsidian casino dome for 30s. Everyone inside spins the slots every 5s: 3 diamonds (1%) = Resistance III, Regen II, Strength IV.", 120, 30, false, false, UltImpl::idolGamble, null);
        Ults.register("avoidance", "Avoidance of Fate", "3 lives. 1st pop: severs trust of enemies around you. 2nd: scatters them. 3rd: a wave for 3 hearts. Lasts until all 3 are used.", 1800, 3600, false, false, UltImpl::avoidance, null);
        Ults.register("supremesingularity", "Supreme Singularity", "A 30 block black hole drags everything in for 5s (half a heart per tick), then explodes for 4 hearts and launches everyone 30 blocks up.", 120, 6, false, false, UltImpl::supremeSingularity, null);
        Ults.register("areswrath", "Ares' Wrath", "Three spears orbit you; left-click with a sword to hurl them (2 hearts each). Any hit: Strength II + Speed II for 15s. 2 hits on one enemy: bleed 12s. 3 hits: 3s stun.", 60, 40, false, false, UltImpl::aresWrath, null);
        Ults.register("flowtime", "The Flow of Time", "Repair 30 durability on your armor, deal 30 to players within 30 blocks, and refill golden apples, cobwebs and XP bottles to one stack.", 120, 0, false, false, UltImpl::flowTime, null);
        Ults.register("desolation", "Waves of Desolation", "Turns 30 blocks around you into a wasteland: Wither III, Poison III, bleed and Weakness II for 30s while you absorb their life and double your health.", 60, 30, false, false, UltImpl::desolation, null);
        Ults.register("tremor", "Seismic Tremor", "Launches players within 30 blocks into the air and slams them down 3x faster, ignoring Feather Falling and no-fall passives.", 120, 8, false, false, UltImpl::tremorUlt, null);
        Ults.register("worldtree", "Domain of the World Tree", "Unbreakable wooden dome with a 500 HP tree. Leave by pressing again or killing the tree. Trusted players get 20 hearts + Regen II.", 120, 120, false, false, UltImpl::worldTree, UltImpl::releaseTree);
        Ults.register("chains", "Chains of Fate", "Chains everything within 30 blocks (5s stun) and gives bleed and the enchain effect for 30s.", 120, 30, false, false, UltImpl::chains, null);
        Ults.register("defiantrage", "Defiant Rage", "Triggers by itself when you die: you revive, all armor is fully mended, Strength II + Speed III for 30s.", 1800, 0, false, true, p -> false, null);
        Ults.register("swordgod", "Sword God", "A rain of swords from the sky: 1 heart on a hit, and each blade explodes on the ground for 1 heart in its blast radius.", 60, 8, false, false, UltImpl::swordGod, null);
    }

    static double c(String id, String key, double def) { return Aegis.I.getConfig().getDouble("ults." + id + "." + key, def); }

    // ================================================================ activity + domains
    private static final Map<String, Long> activeEnd = new HashMap<>();

    static boolean isActive(Player p, String id) {
        Long e = activeEnd.get(p.getUniqueId() + id);
        return e != null && e > System.currentTimeMillis();
    }

    static void markActive(Player p, String id, double sec) { activeEnd.put(p.getUniqueId() + id, System.currentTimeMillis() + (long) (sec * 1000)); }

    /** The ultimate is over: its cooldown starts now. */
    static void finish(Player p, String id) {
        activeEnd.remove(p.getUniqueId() + id);
        PlayerData d = Aegis.I.pd(p);
        if (d.ultSlot > 0) d.endActive(d.ultSlot);
    }

    static final class Dom {
        final UUID owner;
        final String id;
        final Location c;
        final double r;
        final TempBuild build;
        BukkitRunnable task, shimmer;
        final List<Runnable> onEnd = new ArrayList<>();
        boolean ended;

        Dom(UUID owner, String id, Location c, double r, TempBuild build) {
            this.owner = owner;
            this.id = id;
            this.c = c;
            this.r = r;
            this.build = build;
        }

        boolean contains(Location l) { return l.getWorld().equals(c.getWorld()) && l.distanceSquared(c) <= r * r; }

        void end() {
            if (ended) return;
            ended = true;
            if (task != null) task.cancel();
            if (shimmer != null) shimmer.cancel();
            for (Runnable x : onEnd) x.run();
            build.restore();
            domains.remove(owner);
            Player o = Bukkit.getPlayer(owner);
            if (o != null) finish(o, id);
        }
    }

    static final Map<UUID, Dom> domains = new HashMap<>();

    static Dom startDomain(Player p, String id, double r, double seconds, Map<Long, BlockData> plan) {
        Dom old = domains.remove(p.getUniqueId());
        if (old != null) old.end();
        TempBuild tb = new TempBuild(p.getWorld());
        Dom d = new Dom(p.getUniqueId(), id, p.getLocation().clone(), r, tb);
        domains.put(p.getUniqueId(), d);
        tb.place(plan, null);
        d.shimmer = new BukkitRunnable() { @Override public void run() { UltFx.shimmer(d); } };
        d.shimmer.runTaskTimer(Aegis.I, 0, 3);
        if (seconds > 0) Bukkit.getScheduler().runTaskLater(Aegis.I, d::end, (long) (seconds * 20));
        return d;
    }

    static Dom domainAt(Location l, String id) {
        for (Dom d : domains.values()) if (d.id.equals(id) && d.contains(l)) return d;
        return null;
    }

    // ================================================================ helpers
    static List<LivingEntity> enemies(Player p, double r) {
        List<LivingEntity> out = new ArrayList<>();
        Location pl = p.getLocation();
        for (Entity e : p.getWorld().getNearbyEntities(pl, r, r, r))
            if (Abilities.validTarget(p, e) && e.getLocation().distanceSquared(pl) <= r * r) out.add((LivingEntity) e);
        return out;
    }

    static Location randomSurface(Location ctr, double r) {
        World w = ctr.getWorld();
        double a = RND.nextDouble() * Math.PI * 2, d = Math.sqrt(RND.nextDouble()) * r;
        int x = (int) Math.floor(ctr.getX() + Math.cos(a) * d), z = (int) Math.floor(ctr.getZ() + Math.sin(a) * d);
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z + 0.5);
    }

    private static void say(Player p, String s, NamedTextColor c) { p.sendMessage(Component.text(s, c)); }

    // ================================================================ Icor: Bloodbath
    private static boolean bloodbath(Player p) {
        final String id = "bloodbath";
        final double r = c(id, "radius", 30);
        final int ticks = (int) (c(id, "seconds", 30) * 20);
        Map<PotionEffectType, Integer> gained = new HashMap<>();
        int stolen = 0;
        for (LivingEntity e : enemies(p, r)) {
            if (!(e instanceof Player vp)) continue;
            for (PotionEffect ef : vp.getActivePotionEffects())
                if (POSITIVE.contains(ef.getType())) gained.merge(ef.getType(), ef.getAmplifier(), Integer::max);
            vp.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, 1));
            vp.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 1));
            Status.healthAdd(vp, "tmp_bloodbath", -4, ticks);   // 2 hearts stolen
            stolen++;
        }
        for (Map.Entry<PotionEffectType, Integer> g : gained.entrySet())
            p.addPotionEffect(new PotionEffect(g.getKey(), 6000, g.getValue()));   // stolen effects last 5 minutes
        if (stolen > 0) {
            Status.healthAdd(p, "tmp_bloodbath_gain", 4.0 * stolen, ticks);
            NewClasses.heal(p, 4.0 * stolen);
        }
        final Location ctr = p.getLocation().clone();
        final World w = ctr.getWorld();
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= ticks) { cancel(); return; }
                for (int i = 0; i < 70; i++) {
                    double a = RND.nextDouble() * Math.PI * 2, d = Math.sqrt(RND.nextDouble()) * r;
                    Location l = ctr.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
                    l.setY(w.getHighestBlockYAt(l) + 1.1);
                    Fx.dust(l, 1, 0.1, 0x8B0000, 1.7f);
                }
                Fx.part(ctr.clone().add(0, 2, 0), Particle.CRIMSON_SPORE, 20, r / 3, 1, r / 3, 0);
                t += 4;
            }
        }.runTaskTimer(Aegis.I, 0, 4);
        w.playSound(ctr, "entity.wither.spawn", 1f, 0.5f);
        return true;
    }

    // ================================================================ Ymir: Indomitable
    private static boolean indomitable(Player p) {
        final String id = "indomitable";
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) (c(id, "invis-seconds", 10) * 20), 0, false, false, true));
        Gear.armorDelta(p, -(int) c(id, "repair", 50));
        setKb(p, true);
        final UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Aegis.I, () -> { Player q = Bukkit.getPlayer(u); if (q != null) setKb(q, false); }, (long) (c(id, "kb-seconds", 30) * 20));
        p.getWorld().playSound(p.getLocation(), "block.anvil.use", 1f, 0.6f);
        return true;
    }

    private static void setKb(Player p, boolean on) {
        AttributeInstance a = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (a == null) return;
        NamespacedKey key = new NamespacedKey(Aegis.I, "tmp_indomitable");
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) if (m.getKey().equals(key)) a.removeModifier(m);
        if (on) a.addModifier(new AttributeModifier(key, 1.0, AttributeModifier.Operation.ADD_NUMBER));
    }

    // ================================================================ Varuna: Tides of the Abyss
    private static boolean tides(Player p) {
        final String id = "tides";
        final World w = p.getWorld();
        final Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) return false;
        dir.normalize();
        final Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        final double speed = 1.5;
        final int steps = (int) (c(id, "range", 30) / speed);
        final Location front = p.getLocation().clone().add(dir.clone().multiply(2));
        final java.util.LinkedHashSet<LivingEntity> hit = new java.util.LinkedHashSet<>();
        w.playSound(p.getLocation(), "entity.dolphin.splash", 1.5f, 0.5f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline()) { cancel(); return; }
                if (t < steps) {
                    front.add(dir.clone().multiply(speed));
                    for (int i = -6; i <= 6; i++)
                        for (int h = 0; h < 4; h++) {
                            Location l = front.clone().add(side.clone().multiply(i)).add(0, h, 0);
                            Fx.part(l, Particle.SPLASH, 2, .3, .3, .3, 0);
                            if (h == 3) Fx.part(l, Particle.DRIPPING_WATER, 1, .2, .2, .2, 0);
                        }
                    for (Entity e : w.getNearbyEntities(front, 7, 3, 7)) {
                        if (!Abilities.validTarget(p, e)) continue;
                        LivingEntity le = (LivingEntity) e;
                        Vector rel = le.getLocation().toVector().subtract(front.toVector());
                        if (Math.abs(rel.dot(dir)) > 3 || Math.abs(rel.dot(side)) > 7) continue;
                        if (hit.add(le)) Abilities.hurt(le, c(id, "damage", 8.0), p);   // 4 hearts
                        le.setVelocity(dir.clone().multiply(speed).setY(0.15));
                    }
                } else if (t < steps + 14) {
                    for (LivingEntity le : hit) if (le.isValid()) Abilities.pull(le, p.getLocation(), 1.5);   // pulled back to you
                } else {
                    int fx = (int) (c(id, "debuff-seconds", 30) * 20);
                    for (LivingEntity le : hit)
                        if (le.isValid()) {
                            le.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, fx, 1));
                            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, fx, 1));
                        }
                    cancel();
                    return;
                }
                t++;
            }
        }.runTaskTimer(Aegis.I, 0, 1);
        return true;
    }

    // ================================================================ Agni: A Piece of Hell
    private static boolean hell(Player p) {
        final String id = "hell";
        final double r = c(id, "radius", 15);
        Map<Long, BlockData> plan = new LinkedHashMap<>();
        TempBuild.dome(plan, p.getLocation(), r, 2, Material.OBSIDIAN);
        netherFloor(plan, p.getLocation(), r);
        final Dom d = startDomain(p, id, r, c(id, "seconds", 10), plan);
        d.task = new BukkitRunnable() {
            @Override public void run() {
                Player o = Bukkit.getPlayer(d.owner);
                if (o == null) { cancel(); return; }
                o.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 40, 0, false, false, false));
                for (Entity e : o.getWorld().getNearbyEntities(d.c, r, r, r)) {
                    if (!(e instanceof LivingEntity le) || le.equals(o) || e instanceof org.bukkit.entity.ArmorStand || !d.contains(le.getLocation())) continue;
                    le.setFireTicks(80);   // re-lit every few ticks: water can't put it out
                    Fx.part(le.getLocation().add(0, 1, 0), Particle.FLAME, 3, .3, .5, .3, .01);
                }
            }
        };
        d.task.runTaskTimer(Aegis.I, 0, 5);
        p.getWorld().playSound(p.getLocation(), "entity.blaze.shoot", 1.5f, 0.5f);
        return true;
    }

    private static void netherFloor(Map<Long, BlockData> plan, Location c, double r) {
        World w = c.getWorld();
        int cx = c.getBlockX(), cz = c.getBlockZ(), rr = (int) r;
        for (int dx = -rr; dx <= rr; dx++)
            for (int dz = -rr; dz <= rr; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = cx + dx, z = cz + dz;
                int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (!w.getBlockAt(x, top, z).getType().isSolid()) continue;
                double roll = RND.nextDouble();
                Material m = roll < .55 ? Material.NETHERRACK : roll < .7 ? Material.MAGMA_BLOCK : roll < .85 ? Material.SOUL_SAND : Material.BLACKSTONE;
                plan.put(TempBuild.key(x, top, z), m.createBlockData());
                if (w.getBlockAt(x, top - 1, z).getType().isSolid()) plan.put(TempBuild.key(x, top - 1, z), Material.NETHERRACK.createBlockData());
                if (RND.nextDouble() < .12 && w.getBlockAt(x, top + 1, z).getType().isAir()) plan.put(TempBuild.key(x, top + 1, z), Material.FIRE.createBlockData());
            }
    }

    // ================================================================ Hephaestus: Workshop
    private static boolean workshop(Player p) {
        final String id = "workshop";
        final double r = c(id, "radius", 15);
        Location ctr = p.getLocation();
        Map<Long, BlockData> plan = new LinkedHashMap<>();
        TempBuild.dome(plan, ctr, r, 2, Material.OBSIDIAN);
        int cx = ctr.getBlockX(), cy = ctr.getBlockY(), cz = ctr.getBlockZ();
        for (int dx = -7; dx <= 7; dx++)
            for (int dz = -7; dz <= 7; dz++)
                if (dx * dx + dz * dz <= 49) plan.put(TempBuild.key(cx + dx, cy - 1, cz + dz), Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
        Material[] stations = {Material.CRAFTING_TABLE, Material.SMITHING_TABLE, Material.ANVIL, Material.GRINDSTONE, Material.BLAST_FURNACE,
                Material.LOOM, Material.CARTOGRAPHY_TABLE, Material.FLETCHING_TABLE, Material.STONECUTTER, Material.CRAFTING_TABLE};
        for (int i = 0; i < stations.length; i++) {
            double a = Math.PI * 2 * i / stations.length;
            plan.put(TempBuild.key(cx + (int) Math.round(Math.cos(a) * 5), cy, cz + (int) Math.round(Math.sin(a) * 5)), stations[i].createBlockData());
        }
        plan.put(TempBuild.key(cx + 2, cy, cz), Material.LAVA_CAULDRON.createBlockData());
        plan.put(TempBuild.key(cx - 2, cy, cz), Material.LAVA_CAULDRON.createBlockData());
        final Dom d = startDomain(p, id, r, c(id, "seconds", 10), plan);
        d.task = new BukkitRunnable() {
            @Override public void run() {
                Player o = Bukkit.getPlayer(d.owner);
                if (o == null) { cancel(); return; }
                if (d.contains(o.getLocation())) o.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 2, false, true, true));
                Fx.part(d.c.clone().add(0, 2, 0), Particle.LAVA, 4, 3, 1, 3, 0);
            }
        };
        d.task.runTaskTimer(Aegis.I, 0, 10);
        p.getWorld().playSound(ctr, "block.anvil.land", 1.2f, 0.7f);
        return true;
    }

    static NamespacedKey workshopKey() { return new NamespacedKey(Aegis.I, "workshop_item"); }

    static boolean isWorkshop(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(workshopKey(), PersistentDataType.BYTE);
    }

    static boolean workshopActive(Player p) {
        Dom d = domains.get(p.getUniqueId());
        return d != null && d.id.equals("workshop") && d.contains(p.getLocation());
    }

    /** Things crafted inside the workshop: 1.5x durability (and tagged for 2x mining speed + 1.15x damage). */
    static ItemStack enhance(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.getPersistentDataContainer().set(workshopKey(), PersistentDataType.BYTE, (byte) 1);
        int max = it.getType().getMaxDurability();
        if (m instanceof Damageable dm && max > 0) dm.setMaxDamage((int) Math.round(max * c("workshop", "durability-mult", 1.5)));
        it.setItemMeta(m);
        return it;
    }

    static double damageMult(Player att, Entity vic) {
        double m = 1.0;
        Dom d = domains.get(att.getUniqueId());
        if (d != null && d.id.equals("hell") && d.contains(vic.getLocation())) m *= c("hell", "damage-mult", 1.5);
        if (isWorkshop(att.getInventory().getItemInMainHand())) m *= c("workshop", "damage-mult", 1.15);
        return m;
    }

    /** Per-player tick (every 10 ticks): workshop tools mine 2x as fast. */
    static void tick(Player p, int c) {
        if (c % 10 != 0) return;
        ItemStack it = p.getInventory().getItemInMainHand();
        if (isWorkshop(it)) {
            String n = it.getType().name();
            if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE"))
                p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 30, 4, false, false, false));
        }
    }

    // ================================================================ Boreus: Sky Ruler
    private static final class Sky { ItemStack chest; int boosts = 1; }

    private static final Map<UUID, Sky> sky = new HashMap<>();

    private static NamespacedKey elytraKey() { return new NamespacedKey(Aegis.I, "ult_elytra"); }

    static boolean isUltElytra(ItemStack it) {
        return it != null && it.getType() == Material.ELYTRA && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(elytraKey(), PersistentDataType.BYTE);
    }

    private static ItemStack skyElytra() {
        ItemStack it = new ItemStack(Material.ELYTRA);
        ItemMeta m = it.getItemMeta();
        m.setUnbreakable(true);
        m.displayName(Component.text("Sky Ruler Wings", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        m.getPersistentDataContainer().set(elytraKey(), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private static boolean skyRuler(Player p) {
        final UUID id = p.getUniqueId();
        if (sky.containsKey(id)) return false;
        Sky s = new Sky();
        ItemStack cur = p.getInventory().getChestplate();
        s.chest = cur == null ? null : cur.clone();
        sky.put(id, s);
        p.getInventory().setChestplate(skyElytra());
        p.setVelocity(new Vector(0, c("skyruler", "launch", 2.2), 0));
        p.getWorld().playSound(p.getLocation(), "entity.firework_rocket.launch", 1.2f, 0.8f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player q = Bukkit.getPlayer(id);
                if (q == null || !sky.containsKey(id) || q.isDead()) { cancel(); return; }
                t++;
                if (t == 4) q.setGliding(true);
                Fx.part(q.getLocation(), Particle.FIREWORK, 3, .2, .2, .2, .02);
                if (t > 12 && (q.isOnGround() || t > 800)) { landSky(q); cancel(); }
            }
        }.runTaskTimer(Aegis.I, 1, 1);
        return true;
    }

    /** Pressing the ultimate key again: shoot yourself where you look, like a firework rocket. */
    private static boolean skyBoost(Player p) {
        Sky s = sky.get(p.getUniqueId());
        if (s == null) return false;
        if (s.boosts <= 0) { Abilities.msg(p, "No boosts left. Hit someone to gain one."); return false; }
        s.boosts--;
        p.setGliding(true);
        p.setVelocity(p.getLocation().getDirection().multiply(c("skyruler", "boost-speed", 1.9)));
        p.getWorld().playSound(p.getLocation(), "entity.firework_rocket.launch", 1f, 1.1f);
        p.sendActionBar(Component.text("Boosts left: " + s.boosts, NamedTextColor.AQUA));
        return true;
    }

    static void skyHit(Player p) {
        Sky s = sky.get(p.getUniqueId());
        if (s == null) return;
        int max = (int) c("skyruler", "max-boosts", 3);
        if (s.boosts < max) s.boosts++;
        p.sendActionBar(Component.text("+1 boost (" + s.boosts + ")", NamedTextColor.AQUA));
    }

    private static void landSky(Player q) {
        Sky s = sky.remove(q.getUniqueId());
        if (s == null) return;
        q.setGliding(false);
        q.getInventory().setChestplate(s.chest);
        double r = c("skyruler", "landing-radius", 4);
        for (Entity e : q.getWorld().getNearbyEntities(q.getLocation(), r, r, r))
            if (Abilities.validTarget(q, e)) Abilities.hurt((LivingEntity) e, c("skyruler", "landing-damage", 6.0), q);   // 3 hearts
        Fx.explosion(q.getLocation(), 2f);
        finish(q, "skyruler");
    }

    static void fixDrops(PlayerDeathEvent e) {
        Sky s = sky.remove(e.getEntity().getUniqueId());
        e.getDrops().removeIf(UltImpl::isUltElytra);
        if (s != null && s.chest != null) {
            if (e.getKeepInventory()) e.getEntity().getInventory().setChestplate(s.chest);
            else e.getDrops().add(s.chest);
        }
    }

    // ================================================================ Azaroth: True Guardian of the Deep
    private static boolean guardian(Player p) {
        return Pets.summonUlt(p, (int) (c("guardian", "seconds", 30) * 20));
    }

    // ================================================================ Midas: Golden God
    private static boolean goldenGod(Player p) {
        final String id = "goldengod";
        final int ticks = (int) (c(id, "seconds", 30) * 20);
        Status.healthTo(p, "tmp_goldgod", c(id, "hearts", 20), ticks);
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) p.setHealth(a.getValue());
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, 1));
        final UUID u = p.getUniqueId();
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player q = Bukkit.getPlayer(u);
                if (q == null || t >= ticks) { cancel(); return; }
                PlayerInventory inv = q.getInventory();
                ItemStack n = goldItem(inv.getItemInMainHand());
                if (n != null) inv.setItemInMainHand(n);
                n = goldItem(inv.getItemInOffHand());
                if (n != null) inv.setItemInOffHand(n);
                Fx.part(q.getLocation().add(0, 1, 0), Particle.WAX_ON, 2, .4, .8, .4, 0);
                t += 2;
            }
        }.runTaskTimer(Aegis.I, 0, 2);
        p.getWorld().playSound(p.getLocation(), "block.beacon.activate", 1f, 1.4f);
        return true;
    }

    private static ItemStack goldItem(ItemStack it) {
        if (it == null || it.getType().isAir() || it.hasItemMeta()) return null;
        Material t = it.getType();
        if (t == Material.APPLE) return new ItemStack(Material.GOLDEN_APPLE, it.getAmount());
        if (t.isBlock() && t.isItem() && t.isSolid() && !t.isInteractable() && t != Material.GOLD_BLOCK) return new ItemStack(Material.GOLD_BLOCK, it.getAmount());
        return null;
    }

    // ================================================================ Rogue: Shadow Manipulation
    static final Map<UUID, UUID> cloneOwner = new HashMap<>();    // clone -> owner
    private static final Set<UUID> runners = new HashSet<>();
    static final Map<UUID, UUID> provoked = new HashMap<>();      // clone -> the one who hit it

    static boolean isClone(Entity e) { return cloneOwner.containsKey(e.getUniqueId()); }

    static boolean isRunner(Entity e) { return runners.contains(e.getUniqueId()); }

    private static boolean shadows(Player p) {
        final String id = "shadows";
        final double r = c(id, "radius", 30);
        final int life = (int) (c(id, "seconds", 30) * 20);
        final Location ctr = p.getLocation().clone();
        final World w = ctr.getWorld();
        final UUID owner = p.getUniqueId();
        final Dom d = startDomain(p, id, r, c(id, "seconds", 30), new LinkedHashMap<>());
        d.task = new BukkitRunnable() {
            @Override public void run() {
                Player o = Bukkit.getPlayer(owner);
                if (o == null) { cancel(); return; }
                for (int i = 0; i < 50; i++) {
                    Location l = randomSurface(ctr, r);
                    Fx.dust(l.add(0, 0.1, 0), 1, 0.1, 0x101010, 1.8f);
                }
                Fx.part(ctr.clone().add(0, 1, 0), Particle.SQUID_INK, 10, r / 3, 1, r / 3, 0);
                for (Player q : w.getPlayers())
                    if (!q.equals(o) && !Abilities.trusts(o, q) && d.contains(q.getLocation()))
                        q.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, false, false));   // blind while inside the pool
            }
        };
        d.task.runTaskTimer(Aegis.I, 0, 20);
        final List<Zombie> clones = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Location at = randomSurface(ctr, r);
            Zombie z = spawnClone(p, at, life);
            clones.add(z);
            if (i >= 5) runners.add(z.getUniqueId());   // 5 hunt, 5 run
            Fx.burst(at.clone().add(0, 1, 0), Particle.SQUID_INK, 25, .2);
        }
        w.playSound(ctr, "entity.enderman.teleport", 1.2f, 0.5f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t += 10;
                Player o = Bukkit.getPlayer(owner);
                if (o == null || t > life) { for (Zombie z : clones) removeClone(z); cancel(); return; }
                for (Zombie z : clones) {
                    if (!z.isValid()) continue;
                    UUID prov = provoked.get(z.getUniqueId());
                    Entity pe = prov == null ? null : Bukkit.getEntity(prov);
                    if (pe instanceof LivingEntity ple && ple.isValid()) { z.setTarget(ple); continue; }   // whoever hits a clone gets attacked
                    if (runners.contains(z.getUniqueId())) {
                        z.setTarget(null);
                        if (t % 40 == 0) {
                            double a = RND.nextDouble() * Math.PI * 2;
                            z.getPathfinder().moveTo(z.getLocation().add(Math.cos(a) * 25, 0, Math.sin(a) * 25), 1.25);
                        }
                    } else {
                        LivingEntity cur = z.getTarget();
                        if (cur == null || !cur.isValid()) z.setTarget(nearestEnemy(o, z.getLocation(), 60));
                    }
                }
            }
        }.runTaskTimer(Aegis.I, 0, 10);
        return true;
    }

    private static Player nearestEnemy(Player owner, Location from, double range) {
        Player best = null;
        double bd = range * range;
        for (Player q : from.getWorld().getPlayers()) {
            if (!Abilities.validTarget(owner, q)) continue;
            double d = q.getLocation().distanceSquared(from);
            if (d < bd) { bd = d; best = q; }
        }
        return best;
    }

    private static Zombie spawnClone(Player p, Location at, int life) {
        Zombie z = at.getWorld().spawn(at, Zombie.class, zz -> {
            zz.setShouldBurnInDay(false);
            zz.setBaby(false);
            zz.setCanPickupItems(false);
            zz.setPersistent(false);
            zz.setRemoveWhenFarAway(false);
            AttributeInstance hp = zz.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(c("shadows", "clone-health", 100));
            zz.setHealth(c("shadows", "clone-health", 100));
            AttributeInstance dmg = zz.getAttribute(Attribute.ATTACK_DAMAGE);
            if (dmg != null) dmg.setBaseValue(c("shadows", "clone-damage", 6));
            AttributeInstance fr = zz.getAttribute(Attribute.FOLLOW_RANGE);
            if (fr != null) fr.setBaseValue(64);
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
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
            zz.customName(Component.text(p.getName(), NamedTextColor.WHITE));
            zz.setCustomNameVisible(true);
            for (PotionEffect ef : p.getActivePotionEffects())   // exact potion effects
                if (ef.getType() != PotionEffectType.INVISIBILITY)
                    zz.addPotionEffect(new PotionEffect(ef.getType(), life, ef.getAmplifier(), ef.isAmbient(), ef.hasParticles(), ef.hasIcon()));
        });
        cloneOwner.put(z.getUniqueId(), p.getUniqueId());
        return z;
    }

    private static void removeClone(Zombie z) {
        cloneOwner.remove(z.getUniqueId());
        runners.remove(z.getUniqueId());
        provoked.remove(z.getUniqueId());
        if (z.isValid()) {
            Fx.burst(z.getLocation().add(0, 1, 0), Particle.SQUID_INK, 20, .2);
            z.remove();
        }
    }

    static void cloneDied(Entity z) {
        cloneOwner.remove(z.getUniqueId());
        runners.remove(z.getUniqueId());
        provoked.remove(z.getUniqueId());
    }

    // ================================================================ Jackpot: Idol Death Gamble
    private static final String[] SYM = {"DIAMOND", "SEVEN", "CHERRY", "BELL", "BAR"};
    private static final NamedTextColor[] COL = {NamedTextColor.AQUA, NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY};

    private static boolean idolGamble(Player p) {
        final String id = "idolgamble";
        final double r = c(id, "radius", 20);
        Map<Long, BlockData> plan = new LinkedHashMap<>();
        TempBuild.dome(plan, p.getLocation(), r, 2, Material.OBSIDIAN);
        casino(plan, p.getLocation(), id);
        final Dom d = startDomain(p, id, r, c(id, "seconds", 30), plan);
        d.onEnd.add(() -> { for (Player q : d.c.getWorld().getPlayers()) spins.remove(q.getUniqueId()); });
        final int buff = (int) (c(id, "buff-seconds", 30) * 20);
        d.task = new BukkitRunnable() {
            @Override public void run() {
                if (Bukkit.getPlayer(d.owner) == null) { cancel(); return; }
                for (Player q : d.c.getWorld().getPlayers()) if (d.contains(q.getLocation())) spin(q, buff);
            }
        };
        d.task.runTaskTimer(Aegis.I, 40, 100);   // a spin for everyone inside every 5 seconds
        p.getWorld().playSound(p.getLocation(), "block.note_block.bell", 1.5f, 0.6f);
        return true;
    }

    private static final Map<UUID, Integer> spins = new HashMap<>();

    private static void spin(Player q, int buff) {
        int[] r = new int[3];
        int n = spins.merge(q.getUniqueId(), 1, Integer::sum) - 1;   // spins so far in this casino
        double chance = Math.min(c("idolgamble", "max-chance", 7), c("idolgamble", "start-chance", 1) + c("idolgamble", "chance-step", 1) * n);   // 1%, 2% ... up to 7%
        boolean jackpot = RND.nextDouble() * 100 < chance;
        if (jackpot) { r[0] = 0; r[1] = 0; r[2] = 0; }
        else do { for (int i = 0; i < 3; i++) r[i] = RND.nextInt(SYM.length); } while (r[0] == 0 && r[1] == 0 && r[2] == 0);
        Component line = Component.empty();
        for (int i = 0; i < 3; i++) {
            line = line.append(Component.text(SYM[r[i]], COL[r[i]], TextDecoration.BOLD));
            if (i < 2) line = line.append(Component.text("  |  ", NamedTextColor.DARK_GRAY));
        }
        q.sendActionBar(Component.text("Jackpot chance this spin: " + String.format("%.0f", chance) + "%", NamedTextColor.GRAY));
        q.showTitle(Title.title(Component.text("SLOTS", NamedTextColor.GOLD, TextDecoration.BOLD), line,
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(3), Duration.ofMillis(300))));
        q.playSound(q.getLocation(), jackpot ? "ui.toast.challenge_complete" : "block.note_block.chime", 1f, 1f);
        if (jackpot) {
            q.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, buff, 2));
            q.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, buff, 1));
            q.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, buff, 3));
            q.sendMessage(Component.text("JACKPOT! Three diamonds!", NamedTextColor.AQUA, TextDecoration.BOLD));
        }
    }

    /** Casino interior: the uploaded schematic if there is one (plugins/AegisSMP/<schematic>), otherwise a small placeholder. */
    private static void casino(Map<Long, BlockData> plan, Location ctr, String id) {
        File f = new File(Aegis.I.getDataFolder(), Aegis.I.getConfig().getString("ults." + id + ".schematic", "casino.schem"));
        if (f.isFile()) {
            try { Schematic.load(f).paste(plan, ctr); return; }
            catch (Exception ex) { Aegis.I.getLogger().warning("Could not load the casino schematic: " + ex.getMessage()); }
        }
        int cx = ctr.getBlockX(), cy = ctr.getBlockY(), cz = ctr.getBlockZ();
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 8; dz++) {
                plan.put(TempBuild.key(cx + dx, cy - 1, cz + dz), ((dx + dz) & 1) == 0 ? Material.RED_CONCRETE.createBlockData() : Material.BLACK_CONCRETE.createBlockData());
                if (Math.abs(dx) == 8 || Math.abs(dz) == 8) plan.put(TempBuild.key(cx + dx, cy, cz + dz), Material.GOLD_BLOCK.createBlockData());
            }
        for (int i = -6; i <= 6; i += 3) {   // slot machines along the back wall
            plan.put(TempBuild.key(cx + i, cy, cz + 6), Material.IRON_BLOCK.createBlockData());
            plan.put(TempBuild.key(cx + i, cy + 1, cz + 6), Material.REDSTONE_LAMP.createBlockData());
            plan.put(TempBuild.key(cx + i, cy + 2, cz + 6), Material.GOLD_BLOCK.createBlockData());
        }
        plan.put(TempBuild.key(cx, cy, cz), Material.EMERALD_BLOCK.createBlockData());
    }

    // ================================================================ Fateweaver: Avoidance of Fate
    private static final Map<UUID, Integer> fate = new HashMap<>();

    private static boolean avoidance(Player p) {
        fate.put(p.getUniqueId(), 3);
        say(p, "Three lives of fate are yours.", NamedTextColor.LIGHT_PURPLE);
        p.getWorld().spawnParticle(Particle.WITCH, p.getLocation().add(0, 1, 0), 60, .5, 1, .5, .1);
        return true;
    }

    /** Called when a killing blow lands: Fateweaver lives and Berserker's Defiant Rage. */
    static boolean tryRevive(Player p) {
        PlayerData d = Aegis.I.pd(p);
        if (d.ult == null || Ults.slot(p) == 0) return false;
        switch (d.ult) {
            case "defiantrage": return defiantRage(p, d);
            case "avoidance": return fatePop(p);
            default: return false;
        }
    }

    private static boolean defiantRage(Player p, PlayerData d) {
        if (d.remaining(d.ultSlot) > 0) return false;
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(a == null ? 20 : a.getValue());
        p.setNoDamageTicks(40);
        Gear.armorDelta(p, -1000);   // fully mended
        int t = (int) (c("defiantrage", "seconds", 30) * 20);
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, t, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, t, 2));
        Ults.Def def = Ults.REGISTRY.get("defiantrage");
        d.startCd(d.ultSlot, def == null ? 1800 : Ults.cooldown(def));
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 80, .5, 1, .5, .5);
        p.getWorld().playSound(p.getLocation(), "item.totem.use", 1f, 0.8f);
        p.sendMessage(Component.text("DEFIANT RAGE! You refuse to fall.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        if (def != null) UltFx.cast(p, def);
        return true;
    }

    private static boolean fatePop(Player p) {
        Integer lives = fate.get(p.getUniqueId());
        if (lives == null || lives <= 0) return false;
        int idx = 3 - lives;
        lives--;
        if (lives <= 0) { fate.remove(p.getUniqueId()); finish(p, "avoidance"); }
        else fate.put(p.getUniqueId(), lives);
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(a == null ? 20 : a.getValue());
        p.setNoDamageTicks(40);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, .5, 1, .5, .5);
        p.getWorld().playSound(p.getLocation(), "item.totem.use", 1f, 1f);
        double r = c("avoidance", "radius", 30);
        UltFx.pulse(p, "avoidance");
        switch (idx) {
            case 0 -> {   // Fate of severance
                p.sendMessage(Component.text("Fate of severance", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                for (LivingEntity e : enemies(p, r)) {
                    if (!(e instanceof Player vp)) continue;
                    Aegis.I.pd(vp).trusted.clear();
                    for (Player o : Bukkit.getOnlinePlayers()) Aegis.I.pd(o).trusted.remove(vp.getUniqueId());
                    vp.sendMessage(Component.text("Fate has severed all of your trust.", NamedTextColor.LIGHT_PURPLE));
                }
            }
            case 1 -> {   // Fate of isolation
                p.sendMessage(Component.text("Fate of isolation", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                for (LivingEntity e : enemies(p, r)) {
                    if (!(e instanceof Player vp)) continue;
                    double a2 = RND.nextDouble() * Math.PI * 2, dist = 10 + RND.nextDouble() * (r - 10);
                    Location to = randomSurface(p.getLocation().clone().add(Math.cos(a2) * dist, 0, Math.sin(a2) * dist), 1);
                    vp.teleport(to);
                }
            }
            default -> {   // Fate of death
                p.sendMessage(Component.text("Fate of death", NamedTextColor.DARK_RED, TextDecoration.BOLD));
                final double dmg = c("avoidance", "wave-damage", 6.0);   // 3 hearts
                final Set<UUID> hit = new HashSet<>();
                final Location o = p.getLocation().clone();
                final World ww = o.getWorld();
                final double maxR = r;
                new BukkitRunnable() {   // a single wave that goes out once
                    double rad = 1;
                    @Override public void run() {
                        if (rad > maxR || !p.isOnline()) { cancel(); return; }
                        Fx.ring(o.clone().add(0, 0.3, 0), rad, (int) (rad * 8) + 16, l -> { Fx.dust(l, 1, 0, 0x8E24AA, 2f); Fx.part(l, Particle.WITCH, 1, 0, 0, 0, 0); });
                        for (Entity e : ww.getNearbyEntities(o, rad + 2, 5, rad + 2)) {
                            if (!Abilities.validTarget(p, e)) continue;
                            double dx = e.getLocation().getX() - o.getX(), dz = e.getLocation().getZ() - o.getZ();
                            if (Math.abs(Math.sqrt(dx * dx + dz * dz) - rad) <= 2.0 && hit.add(e.getUniqueId())) Abilities.hurt((LivingEntity) e, dmg, p);
                        }
                        rad += 1.5;
                    }
                }.runTaskTimer(Aegis.I, 0, 1);
            }
        }
        return true;
    }

    // ================================================================ Singularity: Supreme Singularity
    private static boolean supremeSingularity(Player p) {
        final String id = "supremesingularity";
        final double r = c(id, "radius", 30);
        final int life = (int) (c(id, "seconds", 5) * 20);
        final int interval = (int) c(id, "tick-interval", 10);   // half a heart every damage tick
        final Location center = p.getLocation().clone().add(0, 4, 0);
        final World w = center.getWorld();
        w.playSound(center, "entity.warden.sonic_charge", 2f, 0.5f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline()) { cancel(); return; }
                if (t >= life) {
                    for (Entity e : w.getNearbyEntities(center, r, r, r)) {
                        if (!Abilities.validTarget(p, e) || e.getLocation().distanceSquared(center) > r * r) continue;
                        Abilities.hurt((LivingEntity) e, c(id, "damage", 8.0), p);   // 4 hearts
                    }
                    final List<LivingEntity> launch = enemies(p, r);
                    Bukkit.getScheduler().runTask(Aegis.I, () -> { for (LivingEntity le : launch) if (le.isValid()) le.setVelocity(new Vector(0, c(id, "launch", 2.3), 0)); });   // about 30 blocks up
                    Fx.explosion(center, 4f);
                    w.playSound(center, "entity.generic.explode", 2f, 0.5f);
                    cancel();
                    return;
                }
                for (Entity e : w.getNearbyEntities(center, r, r, r)) {
                    if (e.equals(p) || e instanceof org.bukkit.entity.ArmorStand) continue;
                    if (e instanceof Player ep && Abilities.trusts(p, ep)) continue;
                    if (!(e instanceof LivingEntity) && !(e instanceof org.bukkit.entity.Item)) continue;
                    Vector to = center.toVector().subtract(e.getLocation().toVector());
                    double dist = to.length();
                    if (dist < 0.8 || dist > r) continue;
                    e.setVelocity(to.normalize().multiply(Math.min(1.3, 0.3 + dist * 0.04)));
                    if (t % interval == 0 && e instanceof LivingEntity le && Abilities.validTarget(p, e)) Abilities.hurt(le, 1.0, p);   // half a heart
                }
                double ang = t * 0.5;
                for (int k = 0; k < 24; k++) {
                    double a = ang + k * 0.45, rr = 0.5 + k * 0.35;
                    Fx.dust(center.clone().add(Math.cos(a) * rr, Math.sin(k) * 0.3, Math.sin(a) * rr), 1, 0, k % 2 == 0 ? 0x5B1FD6 : 0x12002A, 1.6f);
                }
                Fx.part(center, Particle.REVERSE_PORTAL, 25, r / 4, r / 4, r / 4, 0.8);
                Fx.part(center, Particle.SQUID_INK, 3, .3, .3, .3, 0);
                t++;
            }
        }.runTaskTimer(Aegis.I, 0, 1);
        return true;
    }

    // ================================================================ Spartan: Ares' Wrath
    private static final class Ares {
        final List<ItemDisplay> orbit = new ArrayList<>();
        int inFlight = 0;
        final Map<UUID, Integer> hits = new HashMap<>();   // spears that hit each enemy
    }

    private static final Map<UUID, Ares> ares = new HashMap<>();

    private static Transformation spearLook() {
        return new Transformation(new Vector3f(), new AxisAngle4f((float) Math.toRadians(-135), 0f, 0f, 1f), new Vector3f(2.4f, 2.4f, 2.4f), new AxisAngle4f());
    }

    private static boolean aresWrath(Player p) {
        final UUID u = p.getUniqueId();
        if (ares.containsKey(u)) return false;
        final Ares a = new Ares();
        Material sm = Material.matchMaterial("NETHERITE_SPEAR");
        final Material spearMat = sm == null ? Material.TRIDENT : sm;
        for (int i = 0; i < 3; i++)
            a.orbit.add(p.getWorld().spawn(p.getLocation(), ItemDisplay.class, dd -> {
                dd.setItemStack(new ItemStack(spearMat));
                dd.setTransformation(spearLook());
                dd.setPersistent(false);
                dd.setViewRange(3f);
            }));
        ares.put(u, a);
        say(p, "Ares' Wrath: left-click with a sword to hurl a spear.", NamedTextColor.RED);
        final int maxTicks = (int) (c("areswrath", "max-seconds", 30) * 20);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player q = Bukkit.getPlayer(u);
                if (q == null || ares.get(u) != a || q.isDead() || t > maxTicks) { endAres(u); cancel(); return; }
                int n = a.orbit.size();
                for (int i = 0; i < n; i++) {   // the spears that are still with you circle around you
                    ItemDisplay d = a.orbit.get(i);
                    if (!d.isValid()) continue;
                    double ang = t * 0.12 + i * Math.PI * 2 / 3;
                    Location at = q.getLocation().add(Math.cos(ang) * 2, 1.3 + Math.sin(t * 0.1 + i) * 0.25, Math.sin(ang) * 2);
                    at.setYaw((float) Math.toDegrees(ang) + 90f);
                    d.teleport(at);
                    Fx.part(at, Particle.CRIT, 2, .1, .1, .1, 0);
                    Fx.dust(at, 2, .15, 0xC62828, 1.4f);
                }
                t++;
            }
        }.runTaskTimer(Aegis.I, 0, 1);
        return true;
    }

    static boolean aresArmed(Player p) { return ares.containsKey(p.getUniqueId()); }

    private static void endAres(UUID u) {
        Ares a = ares.remove(u);
        if (a == null) return;
        for (ItemDisplay d : a.orbit) d.remove();
        a.orbit.clear();
        Player p = Bukkit.getPlayer(u);
        if (p != null) finish(p, "areswrath");
    }

    /** Left-click with a sword: hurl one spear along your look direction. */
    static void aresFire(Player p) {
        final Ares a = ares.get(p.getUniqueId());
        if (a == null || a.orbit.isEmpty() || !p.getInventory().getItemInMainHand().getType().name().endsWith("_SWORD")) return;
        final ItemDisplay d = a.orbit.remove(a.orbit.size() - 1);
        a.inFlight++;
        final Vector dir = p.getEyeLocation().getDirection().normalize();
        final World w = p.getWorld();
        final Location pos = p.getEyeLocation().add(dir.clone().multiply(1.2));
        pos.setDirection(dir);
        d.teleport(pos);
        w.playSound(p.getLocation(), "item.trident.throw", 1.2f, 0.8f);
        final UUID u = p.getUniqueId();
        new BukkitRunnable() {
            int t = 0;
            private void done() {
                d.remove();
                a.inFlight--;
                cancel();
                if (a.orbit.isEmpty() && a.inFlight <= 0) endAres(u);
            }
            @Override public void run() {
                Player q = Bukkit.getPlayer(u);
                if (q == null || !d.isValid() || t++ > 40) { done(); return; }
                pos.add(dir.clone().multiply(1.8));
                d.teleport(pos);
                Fx.part(pos, Particle.CRIT, 4, .15, .15, .15, 0.02);
                Fx.dust(pos, 4, .2, 0xFF3030, 1.6f);
                if (!pos.getBlock().isPassable()) {
                    Fx.burst(pos, Particle.CRIT, 20, .3);
                    done();
                    return;
                }
                for (Entity e : w.getNearbyEntities(pos, 1.3, 1.3, 1.3)) {
                    if (!Abilities.validTarget(q, e)) continue;
                    aresHit(q, a, (LivingEntity) e);
                    done();
                    return;
                }
            }
        }.runTaskTimer(Aegis.I, 0, 1);
    }

    private static void aresHit(Player p, Ares a, LivingEntity le) {
        Abilities.hurt(le, c("areswrath", "damage", 4.0), p);   // 2 hearts
        int n = a.hits.merge(le.getUniqueId(), 1, Integer::sum);
        int bt = (int) (c("areswrath", "buff-seconds", 15) * 20);
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, bt, 1));   // one spear: Strength II + Speed II
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, bt, 1));
        Fx.burst(le.getLocation().add(0, 1, 0), Particle.CRIT, 40, .5);
        le.getWorld().playSound(le.getLocation(), "entity.player.attack.crit", 1.2f, 0.7f);
        if (n == 2) {
            Status.bleed(le, c("areswrath", "bleed-seconds", 12));   // two spears: same buffs and bleed
            p.sendActionBar(Component.text("Two spears: bleeding!", NamedTextColor.RED));
        } else if (n >= 3) {
            Status.stun(le, c("areswrath", "stun-seconds", 3));   // all three: stunned
            Fx.burst(le.getLocation().add(0, 1, 0), Particle.FLASH, 3, 0);
            p.sendActionBar(Component.text("All three spears: STUNNED!", NamedTextColor.DARK_RED));
        }
    }

    // ================================================================ Chrono: The Flow of Time
    private static boolean flowTime(Player p) {
        final String id = "flowtime";
        double r = c(id, "radius", 30);
        int dura = (int) c(id, "durability", 30);
        Gear.armorDelta(p, -dura);
        for (LivingEntity e : enemies(p, r)) if (e instanceof Player) Gear.armorDelta(e, dura);
        refill(p, Material.GOLDEN_APPLE, 64);
        refill(p, Material.COBWEB, 64);
        refill(p, Material.EXPERIENCE_BOTTLE, 64);
        Fx.expand(p.getLocation().add(0, 0.2, 0), 1, r, 20, 90, l -> { Fx.dust(l, 1, 0, 0xFFC107, 1.5f); Fx.part(l, Particle.END_ROD, 1, 0, 0, 0, 0); });
        p.getWorld().playSound(p.getLocation(), "block.beacon.power_select", 1.5f, 0.6f);
        return true;
    }

    private static void refill(Player p, Material m, int max) {
        PlayerInventory inv = p.getInventory();
        int have = 0;
        for (ItemStack s : inv.getContents()) if (s != null && s.getType() == m) have += s.getAmount();
        int need = max - have;
        if (need <= 0) return;
        for (ItemStack rest : inv.addItem(new ItemStack(m, need)).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    // ================================================================ Moander: Waves of Desolation
    private static boolean desolation(Player p) {
        final String id = "desolation";
        final double r = c(id, "radius", 30);
        final int ticks = (int) (c(id, "seconds", 30) * 20);
        Map<Long, BlockData> plan = new LinkedHashMap<>();
        wasteland(plan, p.getLocation(), r);
        final Dom d = startDomain(p, id, r, c(id, "seconds", 30), plan);
        for (LivingEntity le : enemies(p, r)) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, ticks, 2));
            le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, 2));
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 1));
            Status.bleed(le, c(id, "seconds", 30));
        }
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) Status.healthAdd(p, "tmp_desolation", a.getValue(), ticks);   // double health
        d.task = new BukkitRunnable() {
            @Override public void run() {
                Player o = Bukkit.getPlayer(d.owner);
                if (o == null) { cancel(); return; }
                for (LivingEntity le : enemies(o, r)) {   // absorb their life
                    Abilities.hurt(le, c(id, "drain", 1.0), o);
                    NewClasses.heal(o, c(id, "drain", 1.0));
                }
                Fx.part(d.c.clone().add(0, 1, 0), Particle.SOUL, 40, r / 3, 1, r / 3, 0.02);
            }
        };
        d.task.runTaskTimer(Aegis.I, 0, 20);
        p.getWorld().playSound(p.getLocation(), "entity.wither.spawn", 1.5f, 0.4f);
        return true;
    }

    private static void wasteland(Map<Long, BlockData> plan, Location c, double r) {
        World w = c.getWorld();
        int cx = c.getBlockX(), cz = c.getBlockZ(), rr = (int) r;
        Set<Material> ground = Set.of(Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL, Material.MYCELIUM, Material.ROOTED_DIRT,
                Material.DIRT_PATH, Material.MOSS_BLOCK, Material.COARSE_DIRT);
        Set<Material> plants = Set.of(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.VINE);
        for (int dx = -rr; dx <= rr; dx++)
            for (int dz = -rr; dz <= rr; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = cx + dx, z = cz + dz;
                int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                for (int y = top + 1; y <= top + 30; y++) {   // foliage turns to nothing
                    Material m = w.getBlockAt(x, y, z).getType();
                    if (Tag.LEAVES.isTagged(m) || Tag.FLOWERS.isTagged(m) || Tag.SAPLINGS.isTagged(m) || plants.contains(m)) plan.put(TempBuild.key(x, y, z), Material.AIR.createBlockData());
                }
                Material gm = w.getBlockAt(x, top, z).getType();
                if (!ground.contains(gm)) {
                    if (Tag.LEAVES.isTagged(gm) || plants.contains(gm)) plan.put(TempBuild.key(x, top, z), Material.AIR.createBlockData());
                    continue;
                }
                double roll = RND.nextDouble();
                Material nm = roll < .6 ? Material.COARSE_DIRT : roll < .8 ? Material.GRAVEL : Material.RED_SAND;
                plan.put(TempBuild.key(x, top, z), nm.createBlockData());
                if (RND.nextDouble() < .06) plan.put(TempBuild.key(x, top + 1, z), Material.DEAD_BUSH.createBlockData());
            }
    }

    // ================================================================ Dolus: Seismic Tremor
    private static final Map<UUID, Long> tremor = new HashMap<>();

    static boolean tremor(Player p) { return Status.on(tremor, p); }

    private static boolean tremorUlt(Player p) {
        final double r = c("tremor", "radius", 30);
        final double up = c("tremor", "launch", 2.3), down = c("tremor", "slam-speed", 2.6);
        for (LivingEntity e : enemies(p, r)) {
            final LivingEntity le = e;
            if (le instanceof Player vp) Status.set(tremor, vp, 12);   // no Feather Falling, no no-fall passives
            le.setVelocity(new Vector(0, up, 0));
            new BukkitRunnable() {
                int t = 0;
                @Override public void run() {
                    if (!le.isValid() || t > 160) { cancel(); return; }
                    t++;
                    if (t < 10) return;
                    if (le.getVelocity().getY() < 0.1 || t > 30) {   // past the top: slam down 3x faster than a normal fall
                        le.setVelocity(new Vector(0, -down, 0));
                        if (le.isOnGround()) cancel();
                    }
                }
            }.runTaskTimer(Aegis.I, 1, 1);
        }
        Fx.expand(p.getLocation().add(0, 0.2, 0), 1, r, 20, 90, l -> Fx.part(l, Particle.CAMPFIRE_COSY_SMOKE, 1, 0, 0.1, 0, 0.02));
        p.getWorld().playSound(p.getLocation(), "entity.generic.explode", 1.5f, 0.4f);
        return true;
    }

    // ================================================================ Yggdrasil: Domain of the World Tree
    private static final class Tree { double hp; final Set<Long> blocks = new HashSet<>(); BossBar bar; }

    private static final Map<UUID, Tree> trees = new HashMap<>();

    private static boolean worldTree(Player p) {
        final String id = "worldtree";
        final double r = c(id, "radius", 20);
        final double maxSecs = c(id, "max-seconds", 120);
        Location ctr = p.getLocation().clone();
        Map<Long, BlockData> plan = new LinkedHashMap<>();
        TempBuild.dome(plan, ctr, r, 2, Material.OAK_PLANKS);
        final Tree tree = new Tree();
        buildTree(plan, tree.blocks, ctr);
        tree.hp = c(id, "tree-hp", 500);
        final Dom d = startDomain(p, id, r, maxSecs, plan);
        tree.bar = Bukkit.createBossBar("World Tree", BarColor.GREEN, BarStyle.SOLID);
        trees.put(p.getUniqueId(), tree);
        final int ticks = (int) (maxSecs * 20);
        final Set<UUID> buffed = new HashSet<>();
        d.onEnd.add(() -> {
            tree.bar.removeAll();
            trees.remove(d.owner);
            for (UUID u : buffed) { Player q = Bukkit.getPlayer(u); if (q != null) Status.removeMod(q, "tmp_worldtree"); }
        });
        d.task = new BukkitRunnable() {
            @Override public void run() {
                Player o = Bukkit.getPlayer(d.owner);
                if (o == null) { cancel(); return; }
                for (Player q : d.c.getWorld().getPlayers()) {
                    if (!d.contains(q.getLocation())) { tree.bar.removePlayer(q); continue; }
                    tree.bar.addPlayer(q);
                    if (q.equals(o) || Abilities.trusts(o, q)) {   // the owner and trusted players
                        if (buffed.add(q.getUniqueId())) Status.healthTo(q, "tmp_worldtree", c(id, "hearts", 20), ticks);
                        q.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 1, false, true, true));
                    }
                }
            }
        };
        d.task.runTaskTimer(Aegis.I, 0, 20);
        p.getWorld().playSound(ctr, "block.wood.break", 2f, 0.4f);
        return true;
    }

    private static void buildTree(Map<Long, BlockData> plan, Set<Long> keys, Location c) {
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        BlockData log = Material.OAK_LOG.createBlockData();
        Leaves lb = (Leaves) Material.OAK_LEAVES.createBlockData();
        lb.setPersistent(true);
        int h = 22;
        for (int y = 0; y <= h; y++)
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++)
                    if (dx * dx + dz * dz <= 7) { long k = TempBuild.key(cx + dx, cy + y, cz + dz); plan.put(k, log); keys.add(k); }
        int top = cy + h - 2;
        for (int dx = -9; dx <= 9; dx++)
            for (int dy = -5; dy <= 6; dy++)
                for (int dz = -9; dz <= 9; dz++) {
                    if (dx * dx + dy * dy * 2 + dz * dz > 81 || (Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && dy < 3)) continue;
                    long k = TempBuild.key(cx + dx, top + dy, cz + dz);
                    if (plan.containsKey(k)) continue;
                    plan.put(k, lb);
                    keys.add(k);
                }
    }

    /** A player hits (or tries to break) a tree block: the tree takes damage. */
    static boolean treeHit(Block b, Player hitter) {
        long k = TempBuild.key(b.getX(), b.getY(), b.getZ());
        for (Map.Entry<UUID, Tree> e : trees.entrySet()) {
            Tree t = e.getValue();
            if (!t.blocks.contains(k)) continue;
            t.hp -= c("worldtree", "damage-per-hit", 5);
            t.bar.setProgress(Math.max(0, Math.min(1, t.hp / c("worldtree", "tree-hp", 500))));
            if (t.hp <= 0) { Dom d = domains.get(e.getKey()); if (d != null) d.end(); }
            return true;
        }
        return false;
    }

    private static boolean releaseTree(Player p) {
        Dom d = domains.get(p.getUniqueId());
        if (d != null) d.end();
        return true;
    }

    // ================================================================ Shackled: Chains of Fate
    private static boolean chains(Player p) {
        final String id = "chains";
        double r = c(id, "radius", 30);
        for (LivingEntity e : enemies(p, r)) {
            Status.stun(e, c(id, "stun-seconds", 5));
            Status.set(Status.enchain, e, c(id, "seconds", 30));
            Status.bleed(e, c(id, "seconds", 30));
            Location from = p.getLocation().add(0, 1, 0), to = e.getLocation().add(0, 1, 0);
            Vector v = to.toVector().subtract(from.toVector());
            double len = v.length();
            if (len > 0.5) {
                v.normalize().multiply(0.5);
                Location cur = from.clone();
                for (double dd = 0; dd < len; dd += 0.5) { Fx.dust(cur, 1, 0, 0x9E9E9E, 1.3f); cur.add(v); }
            }
        }
        p.getWorld().playSound(p.getLocation(), "block.chain.place", 2f, 0.5f);
        return true;
    }

    // ================================================================ Samurai: Sword God
    private static boolean swordGod(Player p) {
        final String id = "swordgod";
        final double r = c(id, "radius", 15);
        final int life = (int) (c(id, "seconds", 5) * 20);
        final double dmg = c(id, "damage", 2.0);   // 1 heart
        final Location ctr = p.getLocation().clone();
        final World w = ctr.getWorld();
        final Material[] mats = {Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD, Material.GOLDEN_SWORD};
        final List<ItemDisplay> disp = new ArrayList<>();
        final List<double[]> pos = new ArrayList<>();   // x, y, z, age
        w.playSound(ctr, "item.trident.thunder", 1.5f, 1.4f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t < life && t % 2 == 0)
                    for (int i = 0; i < 3; i++) {
                        Location at = randomSurface(ctr, r);
                        double y = at.getY() + 28;
                        ItemDisplay id2 = w.spawn(new Location(w, at.getX(), y, at.getZ()), ItemDisplay.class, dd -> {
                            dd.setItemStack(new ItemStack(mats[RND.nextInt(mats.length)]));
                            dd.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) Math.toRadians(-135), 0f, 0f, 1f), new Vector3f(2f, 2f, 2f), new AxisAngle4f()));
                            dd.setPersistent(false);
                            dd.setViewRange(3f);
                        });
                        disp.add(id2);
                        pos.add(new double[]{at.getX(), y, at.getZ(), 0});
                    }
                for (int i = disp.size() - 1; i >= 0; i--) {
                    ItemDisplay s = disp.get(i);
                    double[] ps = pos.get(i);
                    ps[1] -= 1.5;
                    ps[3]++;
                    Location now = new Location(w, ps[0], ps[1], ps[2]);
                    boolean boom = ps[3] > 90 || !now.clone().subtract(0, 0.8, 0).getBlock().isPassable();
                    boolean struck = false;
                    for (Entity e : w.getNearbyEntities(now, 1.3, 1.6, 1.3))
                        if (Abilities.validTarget(p, e)) { Abilities.hurt((LivingEntity) e, dmg, p); struck = true; }   // a direct hit
                    if (boom || struck) {
                        if (boom && !struck)
                            for (Entity e : w.getNearbyEntities(now, 3.5, 3.5, 3.5))
                                if (Abilities.validTarget(p, e)) Abilities.hurt((LivingEntity) e, dmg, p);   // blast radius
                        Fx.burst(now, Particle.EXPLOSION, 3, .5);
                        w.playSound(now, "entity.generic.explode", 0.6f, 1.6f);
                        s.remove();
                        disp.remove(i);
                        pos.remove(i);
                    } else {
                        s.teleport(now);
                    }
                }
                t++;
                if (t > life && disp.isEmpty()) cancel();
            }
        }.runTaskTimer(Aegis.I, 0, 1);
        return true;
    }

    // ================================================================ cleanup
    static void cleanup(Player p) {
        UUID u = p.getUniqueId();
        Dom d = domains.get(u);
        if (d != null) d.end();
        setKb(p, false);
        Sky s = sky.remove(u);
        if (s != null) {
            p.setGliding(false);
            if (isUltElytra(p.getInventory().getChestplate())) p.getInventory().setChestplate(s.chest);
        }
        fate.remove(u);
        endAres(u);
    }

    static void shutdown() {
        for (Dom d : new ArrayList<>(domains.values())) { d.ended = true; if (d.task != null) d.task.cancel(); if (d.shimmer != null) d.shimmer.cancel(); for (Runnable x : d.onEnd) x.run(); }
        domains.clear();
        for (UUID z : new ArrayList<>(cloneOwner.keySet())) { Entity e = Bukkit.getEntity(z); if (e != null) e.remove(); }
        cloneOwner.clear();
        TempBuild.shutdown();
    }
}
