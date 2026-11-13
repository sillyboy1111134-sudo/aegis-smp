package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;

@SuppressWarnings("deprecation")
final class Abilities {
    static boolean inAbility = false;

    record Duel(UUID caster, UUID other, Location center, double r) { }

    static final class Charge { BossBar bar; long start; BukkitTask task; }

    static final Map<UUID, Duel> duels = new HashMap<>();
    static final Map<UUID, Charge> charges = new HashMap<>();
    static final Set<Long> shell = new HashSet<>();           // protected magma-dome blocks
    static final List<Runnable> cleanups = new ArrayList<>(); // run on disable

    static Aegis pl() { return Aegis.I; }
    static FileConfiguration cfg() { return Aegis.I.getConfig(); }

    // ================================================================ helpers
    static String path(Player p, int n, String key) {
        ClassType t = pl().pd(p).type;
        return "classes." + (t == null ? "none" : t.id) + ".a" + n + "." + key;
    }
    static double v(Player p, int n, String key, double def) { return cfg().getDouble(path(p, n, key), def); }
    static String s(Player p, int n, String key, String def) { return cfg().getString(path(p, n, key), def); }
    static List<String> list(Player p, int n, String key) { return cfg().getStringList(path(p, n, key)); }

    /** Seconds -> ticks, with +X% per energy. */
    static int ticks(Player p, double sec) {
        double b = 1 + cfg().getDouble("general.duration-bonus-per-energy", 0.02) * pl().pd(p).energy;
        return Math.max(1, (int) Math.round(sec * 20 * b));
    }
    static double secs(Player p, double sec) { return ticks(p, sec) / 20.0; }

    static void msg(Player p, String s) { p.sendMessage(Component.text(s, NamedTextColor.RED)); }
    static void snd(Entity e, String key) { e.getWorld().playSound(e.getLocation(), key, 1f, 1f); }

    static boolean trusts(Player a, Entity b) {
        if (!(b instanceof Player bp)) return false;
        if (!pl().pd(a).trusts(bp)) return false;
        return !cfg().getBoolean("trust.mutual-required", false) || pl().pd(bp).trusts(a);
    }

    static boolean validTarget(Player p, Entity en) {
        return en instanceof LivingEntity && !en.equals(p) && !(en instanceof ArmorStand) && !trusts(p, en);
    }

    static PotionEffectType fx(String s) {
        return s == null ? null : PotionEffectType.getByKey(NamespacedKey.minecraft(s.toLowerCase(Locale.ROOT)));
    }

    /** list entries: "effect:LEVEL:SECONDS" */
    static void give(Player src, LivingEntity to, List<String> list, boolean scale) {
        for (String s : list) {
            String[] a = s.split(":");
            PotionEffectType t = fx(a[0]);
            if (t == null) continue;
            int amp = a.length > 1 ? Integer.parseInt(a[1].trim()) - 1 : 0;
            double sec = a.length > 2 ? Double.parseDouble(a[2].trim()) : 30;
            to.addPotionEffect(new PotionEffect(t, scale ? ticks(src, sec) : (int) (sec * 20), Math.max(0, amp)));
        }
    }

    static void hurt(LivingEntity v, double dmg, Player src) { hurt(v, dmg, src, true); }

    /** ability = true: also applies the used ability's "armor-damage" key (durability removed from the target). */
    static void hurt(LivingEntity v, double dmg, Player src, boolean ability) {
        inAbility = true;
        try { v.damage(dmg, src); } finally { inAbility = false; }
        if (!ability) return;
        PlayerData d = pl().pd(src);
        if (d.type != null && d.lastAbility > 0) {
            int ad = (int) v(src, d.lastAbility, "armor-damage", 0);
            if (ad > 0) Gear.armorDelta(v, ad);
        }
    }

    static void hurtRaw(LivingEntity v, double dmg) {
        inAbility = true;
        try { v.damage(dmg); } finally { inAbility = false; }
    }

    static LivingEntity target(Player p, double range) {
        Location eye = p.getEyeLocation();
        World w = p.getWorld();
        RayTraceResult er = w.rayTraceEntities(eye, eye.getDirection(), range, 0.6, en -> validTarget(p, en));
        if (er == null || !(er.getHitEntity() instanceof LivingEntity le)) return null;
        RayTraceResult br = w.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        if (br != null && br.getHitPosition().distance(eye.toVector()) < er.getHitPosition().distance(eye.toVector())) return null;
        return le;
    }

    static void pull(Entity e, Location to, double strength) {
        Vector d = to.toVector().subtract(e.getLocation().toVector());
        double len = d.length();
        if (len < 0.5) return;
        Vector vel = d.normalize().multiply(Math.min(strength, 0.4 + len * 0.12));
        vel.setY(Math.max(vel.getY(), 0.25));
        e.setVelocity(vel);
    }

    static void line(Location a, Location b, Particle pt) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len <= 0) return;
        d.normalize();
        for (double i = 0; i < len; i += 0.5) a.getWorld().spawnParticle(pt, a.clone().add(d.clone().multiply(i)), 1, 0, 0, 0, 0);
    }

    /** Travelling block/item projectile used by several abilities. */
    static void fire(Player p, Material mat, boolean asItem, double range, double speed, double hitR, Consumer<LivingEntity> onHit) {
        fire(p, mat, asItem, range, speed, hitR, onHit, null);
    }

    static void fire(Player p, Material mat, boolean asItem, double range, double speed, double hitR, Consumer<LivingEntity> onHit, Consumer<Location> onBlock) {
        Location start = p.getEyeLocation();
        Vector dir = start.getDirection().normalize();
        World w = start.getWorld();
        Location s0 = start.clone().add(dir.clone().multiply(1.0));
        Display d;
        if (asItem) {
            d = w.spawn(s0, ItemDisplay.class, x -> x.setItemStack(new ItemStack(mat)));
        } else {
            d = w.spawn(s0, BlockDisplay.class, x -> {
                x.setBlock(mat.createBlockData());
                x.setTransformation(new Transformation(new Vector3f(-0.4f, -0.4f, -0.4f), new AxisAngle4f(), new Vector3f(0.8f, 0.8f, 0.8f), new AxisAngle4f()));
            });
        }
        d.setTeleportDuration(1);
        new BukkitRunnable() {
            double travelled = 0;
            @Override public void run() {
                if (!d.isValid()) { cancel(); return; }
                Location cur = d.getLocation();
                Location next = cur.clone().add(dir.clone().multiply(speed));
                RayTraceResult br = w.rayTraceBlocks(cur, dir, speed, FluidCollisionMode.NEVER, true);
                LivingEntity found = null;
                for (Entity e : w.getNearbyEntities(next, hitR, hitR, hitR)) {
                    if (validTarget(p, e)) { found = (LivingEntity) e; break; }
                }
                if (found != null) {
                    w.spawnParticle(Particle.CRIT, next, 20, .3, .3, .3, .1);
                    onHit.accept(found);
                    d.remove(); cancel(); return;
                }
                if (br != null) {
                    Location hitLoc = br.getHitPosition().toLocation(w);
                    w.spawnParticle(Particle.POOF, next, 10, .2, .2, .2, .05);
                    d.remove(); cancel();
                    if (onBlock != null) onBlock.accept(hitLoc);
                    return;
                }
                d.teleport(next);
                Fx.trail(next, mat);
                travelled += speed;
                if (travelled >= range) { d.remove(); cancel(); }
            }
        }.runTaskTimer(pl(), 1, 1);
    }

    // ================================================================ entry point
    static void use(Player p, int n) {
        PlayerData d = pl().pd(p);
        ClassType t = d.type;
        if (t == null || d.rolling || (n == 2 && t.a2 == null) || p.isDead()) return;
        if (Status.on(Status.stun, p) || Status.on(Status.freeze, p)) { msg(p, "You are stunned!"); return; }

        if (t == ClassType.AZAROTH && n == 1 && Pets.alive(p)) { msg(p, "Your Warden is still alive."); return; }
        boolean toggle = (t == ClassType.YMIR && n == 1 && duels.containsKey(p.getUniqueId()))
                || (t == ClassType.AGNI && n == 2 && charges.containsKey(p.getUniqueId()));
        if (!toggle && d.remaining(n) > 0) {
            msg(p, t.abilityName(n) + " is on cooldown (" + String.format("%.1f", d.remaining(n)) + "s)");
            return;
        }
        d.lastAbility = n;
        boolean ok = switch (t) {
            case SHACKLED -> n == 1 ? enchain(p) : chainShot(p);
            case MOANDER -> n == 1 ? drain(p) : witherAway(p);
            case CHRONO -> n == 1 ? timestop(p) : futureSight(p);
            case ICOR -> n == 1 ? transfusion(p) : lifesteal(p);
            case BERSERKER -> n == 1 ? gambit(p) : rapidStrikes(p);
            case DOLUS -> seismic(p);
            case SAMURAI -> n == 1 ? dualWield(p) : swiftSlash(p);
            case YMIR -> n == 1 ? forcedDuel(p) : impervious(p);
            case VARUNA -> n == 1 ? tidalPull(p) : suffocation(p);
            case AGNI -> n == 1 ? magmaBath(p) : fireball(p);
            case HEPHAESTUS -> n == 1 ? shatter(p) : piercing(p);
            case BOREUS -> n == 1 ? breezyDash(p) : overdrive(p);
            case AZAROTH -> n == 1 ? deepsCall(p) : Domain.start(p);
            case MIDAS -> n == 1 ? goldenTouch(p) : bounty(p);
        };
        if (ok) {
            d.startCd(n, v(p, n, "cooldown", 30));
            int rep = (int) v(p, n, "armor-repair", 0);          // durability healed on every armor piece you wear
            if (rep > 0) Gear.armorDelta(p, -rep);
            Fx.cast(p, t, n);
        }
    }

    // ================================================================ SHACKLED
    static boolean enchain(Player p) {
        LivingEntity t = target(p, v(p, 1, "range", 30));
        if (t == null) { msg(p, "No target in sight."); return false; }
        Status.set(Status.enchain, t, secs(p, v(p, 1, "seconds", 10)));
        Fx.onTarget(t, "chain");
        snd(t, "block.chain.place");
        return true;
    }

    static boolean chainShot(Player p) {
        double range = v(p, 2, "range", 30);
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.5, en -> validTarget(p, en));
        if (r == null) { msg(p, "The chain missed."); return true; }
        Location hl = r.getHitPosition().toLocation(p.getWorld());
        Fx.beam(eye.clone().subtract(0, 0.3, 0), hl, Particle.CRIT, 0x9E9E9E);
        Fx.part(hl, Particle.ENCHANTED_HIT, 40, .4, .4, .4, .3);
        if (r.getHitEntity() instanceof LivingEntity t) {
            hurt(t, v(p, 2, "damage", 4), p);
            pull(t, p.getLocation(), v(p, 2, "pull-strength", 3.0));
            Status.bleed(t, v(p, 2, "bleed-seconds", 5));
        } else {
            pull(p, hl, v(p, 2, "self-pull-strength", 3.5));
        }
        snd(p, "block.chain.place");
        return true;
    }

    // ================================================================ MOANDER
    static boolean drain(Player p) {
        give(p, p, list(p, 1, "effects"), true);
        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 40, 1, 1, 1, .05);
        snd(p, "entity.wither.ambient");
        return true;
    }

    static boolean witherAway(Player p) {
        LivingEntity t = target(p, v(p, 2, "range", 30));
        if (t == null) { msg(p, "No target in sight."); return false; }
        int ticks = ticks(p, v(p, 2, "seconds", 20));
        if (t instanceof Player tp) Status.healthAdd(tp, "tmp_moander_loss", -v(p, 2, "steal-hp", 4), ticks);
        Status.healthTo(p, "tmp_moander_gain", v(p, 2, "my-hearts", 12), ticks);
        give(p, t, list(p, 2, "effects"), true);
        Fx.beam(p.getEyeLocation().subtract(0, 0.3, 0), t.getEyeLocation(), Particle.SOUL_FIRE_FLAME, 0x101010);
        Fx.onTarget(t, "wither");
        return true;
    }

    // ================================================================ CHRONO
    static boolean timestop(Player p) {
        double r = v(p, 1, "radius", 10);
        int dur = ticks(p, v(p, 1, "seconds", 2.5));
        Map<Entity, Location> locked = new HashMap<>();
        Map<Entity, Vector> vel = new HashMap<>();
        Map<Entity, Boolean> grav = new HashMap<>();
        Map<LivingEntity, Boolean> ai = new HashMap<>();
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (e.equals(p) || e instanceof ArmorStand || trusts(p, e)) continue;
            if (e instanceof Player q) {
                Status.set(Status.freeze, q, dur / 20.0);
            } else if (e instanceof LivingEntity le) {
                ai.put(le, le.hasAI());
                le.setAI(false);
            } else {
                vel.put(e, e.getVelocity());
                grav.put(e, e.hasGravity());
                e.setGravity(false);
                locked.put(e, e.getLocation());
            }
        }
        Fx.timeStop(p, r, dur);
        snd(p, "block.beacon.deactivate");
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                for (Map.Entry<Entity, Location> en : locked.entrySet()) {
                    Entity e = en.getKey();
                    if (e.isValid()) { e.setVelocity(new Vector()); e.teleport(en.getValue()); }
                }
                if (t >= dur) {
                    ai.forEach((le, was) -> { if (le.isValid()) le.setAI(was); });
                    for (Entity e : locked.keySet()) {
                        if (!e.isValid()) continue;
                        e.setGravity(grav.getOrDefault(e, true));
                        e.setVelocity(vel.getOrDefault(e, new Vector()));
                    }
                    cancel();
                }
            }
        }.runTaskTimer(pl(), 1, 1);
        return true;
    }

    static boolean futureSight(Player p) {
        int dodges = Math.max(1, (int) v(p, 2, "dodges", 5));
        double sec = v(p, 2, "seconds", 30);
        long until = sec <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + (long) (secs(p, sec) * 1000);
        Status.dodges.put(p.getUniqueId(), new long[]{dodges, until});
        p.sendMessage(Component.text("Future Sight: you will dodge the next " + dodges + " hits.", NamedTextColor.AQUA));
        snd(p, "block.beacon.activate");
        Fx.futureSight(p);
        return true;
    }

    // ================================================================ ICOR
    static boolean transfusion(Player p) {
        fire(p, Material.REDSTONE_BLOCK, false, v(p, 1, "range", 30), v(p, 1, "projectile-speed", 1.6), v(p, 1, "hit-radius", 1.0), t -> {
            hurt(t, v(p, 1, "damage", 2), p);
            for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
                if (e.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                    p.removePotionEffect(e.getType());
                    t.addPotionEffect(e);
                }
            }
            int borrow = (int) (v(p, 1, "borrow-seconds", 300) * 20);
            for (PotionEffect e : new ArrayList<>(t.getActivePotionEffects())) {
                if (e.getType().getEffectCategory() != PotionEffectType.Category.BENEFICIAL) continue;
                int dur = e.isInfinite() ? borrow : e.getDuration();
                p.addPotionEffect(new PotionEffect(e.getType(), dur, e.getAmplifier()));
            }
            snd(t, "entity.generic.drink");
            Fx.onTarget(t, "blood");
        });
        return true;
    }

    static boolean lifesteal(Player p) {
        LivingEntity t = target(p, v(p, 2, "range", 30));
        if (t == null) { msg(p, "No target in sight."); return false; }
        int ticks = ticks(p, v(p, 2, "seconds", 30));
        if (t instanceof Player tp) Status.healthAdd(tp, "tmp_icor_loss", -v(p, 2, "steal-hp", 4), ticks);
        Status.healthTo(p, "tmp_icor_gain", v(p, 2, "my-hearts", 15), ticks);
        Fx.beam(t.getEyeLocation(), p.getEyeLocation(), Particle.DAMAGE_INDICATOR, 0xB00020);
        Fx.onTarget(t, "lifesteal");
        return true;
    }

    // ================================================================ BERSERKER
    static boolean gambit(Player p) {
        give(p, p, list(p, 1, "effects"), true);
        int delay = ticks(p, v(p, 1, "seconds", 20));
        Bukkit.getScheduler().runTaskLater(pl(), () -> {
            if (!p.isOnline() || p.isDead()) return;
            give(p, p, list(p, 1, "after-effects"), false);
            Status.healthTo(p, "tmp_gambit", v(p, 1, "after-hearts", 8), (int) (v(p, 1, "after-seconds", 20) * 20));
        }, delay);
        snd(p, "entity.ravager.roar");
        return true;
    }

    static boolean rapidStrikes(Player p) {
        int swings = (int) v(p, 2, "swings", 3);
        long gap = (long) v(p, 2, "interval-ticks", 4);
        double r = v(p, 2, "radius", 3.5), dmg = v(p, 2, "damage", 2);
        for (int i = 0; i < swings; i++) {
            Bukkit.getScheduler().runTaskLater(pl(), () -> {
                if (!p.isOnline() || p.isDead() || Status.on(Status.stun, p)) return;
                p.swingMainHand();
                Fx.spin(p, r);
                for (Entity e : p.getNearbyEntities(r, r, r))
                    if (validTarget(p, e)) hurt((LivingEntity) e, dmg, p);
            }, i * gap);
        }
        return true;
    }

    // ================================================================ DOLUS
    static boolean seismic(Player p) {
        fire(p, Material.DIRT, false, v(p, 1, "range", 30), v(p, 1, "projectile-speed", 1.5), v(p, 1, "hit-radius", 1.0), t -> {
            hurt(t, v(p, 1, "damage", 6), p);
            Status.stun(t, v(p, 1, "stun-seconds", 1));
            Fx.onTarget(t, "quake");
        });
        return true;
    }

    /** Eating ores (Dolus). Returns true when the click was handled. */
    static boolean consumeOre(Player p, ItemStack it, EquipmentSlot hand) {
        for (String id : Passives.unlocked(p)) {
            var n = Passives.node(p, id);
            if (n == null) continue;
            var ores = n.getConfigurationSection("ores");
            if (ores == null) continue;
            for (String key : ores.getKeys(false)) {
                var sec = ores.getConfigurationSection(key);
                if (sec == null || !sec.getStringList("materials").contains(it.getType().name())) continue;
                PlayerData d = pl().pd(p);
                String ck = "ore_" + key;
                if (d.remKey(ck) > 0) { msg(p, "Ore power on cooldown (" + String.format("%.0f", d.remKey(ck)) + "s)"); return true; }
                ItemStack cur = p.getInventory().getItem(hand);
                if (cur == null) return true;
                cur.setAmount(cur.getAmount() - 1);
                p.getInventory().setItem(hand, cur.getAmount() <= 0 ? null : cur);
                give(p, p, sec.getStringList("effects"), false);
                if (sec.getInt("enchant-boost", 0) > 0) boostEnchants(p, sec.getInt("enchant-boost"), sec.getDouble("seconds", 30));
                d.startKey(ck, sec.getDouble("cooldown", 0));
                snd(p, "entity.player.burp");
                return true;
            }
        }
        return false;
    }

    static void boostEnchants(Player p, int levels, double seconds) {
        PlayerInventory inv = p.getInventory();
        int[] slots = {inv.getHeldItemSlot(), 40, 36, 37, 38, 39};
        for (int slot : slots) {
            ItemStack s = inv.getItem(slot);
            if (s == null || s.getType().isAir() || s.getEnchantments().isEmpty()) continue;
            if (s.getItemMeta().getPersistentDataContainer().has(pl().boostKey, PersistentDataType.STRING)) continue;
            StringBuilder sb = new StringBuilder();
            Map<Enchantment, Integer> ench = new HashMap<>(s.getEnchantments());
            for (Map.Entry<Enchantment, Integer> e : ench.entrySet()) sb.append(e.getKey().getKey().getKey()).append('=').append(e.getValue()).append(';');
            for (Map.Entry<Enchantment, Integer> e : ench.entrySet()) s.addUnsafeEnchantment(e.getKey(), e.getValue() + levels);
            String tag = sb.toString();
            s.editMeta(m -> m.getPersistentDataContainer().set(pl().boostKey, PersistentDataType.STRING, tag));
            inv.setItem(slot, s);
        }
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(pl(), () -> { Player q = Bukkit.getPlayer(id); if (q != null) revertBoost(q); }, (long) (seconds * 20));
    }

    static void revertBoost(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i <= 40; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir() || !s.hasItemMeta()) continue;
            String tag = s.getItemMeta().getPersistentDataContainer().get(pl().boostKey, PersistentDataType.STRING);
            if (tag == null) continue;
            for (String part : tag.split(";")) {
                if (part.isBlank()) continue;
                String[] kv = part.split("=");
                Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0]));
                if (en != null) s.addUnsafeEnchantment(en, Integer.parseInt(kv[1]));
            }
            s.editMeta(m -> m.getPersistentDataContainer().remove(pl().boostKey));
            inv.setItem(i, s);
        }
    }

    // ================================================================ SAMURAI
    static boolean dualWield(Player p) {
        if (Status.on(Status.dual, p)) return false;
        PlayerData d = pl().pd(p);
        d.savedOffhand = p.getInventory().getItemInOffHand().clone();
        Material m = Material.matchMaterial(s(p, 1, "sword", "NETHERITE_SWORD"));
        ItemStack sw = new ItemStack(m == null ? Material.DIAMOND_SWORD : m);
        sw.editMeta(x -> {
            x.getPersistentDataContainer().set(pl().tempKey, PersistentDataType.BYTE, (byte) 1);
            x.displayName(Component.text("Dual Blade", NamedTextColor.RED));
        });
        p.getInventory().setItemInOffHand(sw);
        double sec = secs(p, v(p, 1, "seconds", 10));
        Status.set(Status.dual, p, sec);
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(pl(), () -> { Player q = Bukkit.getPlayer(id); if (q != null) endDual(q); }, (long) (sec * 20));
        snd(p, "item.armor.equip_netherite");
        return true;
    }

    static void endDual(Player p) {
        Status.dual.remove(p.getUniqueId());
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i <= 40; i++) if (Items.isTemp(inv.getItem(i))) inv.setItem(i, null);
        PlayerData d = pl().pd(p);
        if (d.savedOffhand != null) {
            if (inv.getItemInOffHand().getType().isAir()) inv.setItemInOffHand(d.savedOffhand);
            else inv.addItem(d.savedOffhand).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
            d.savedOffhand = null;
        }
    }

    static boolean swiftSlash(Player p) {
        LivingEntity t = target(p, v(p, 2, "range", 20));
        if (t == null) { msg(p, "No target in sight."); return false; }
        Location tl = t.getLocation();
        Vector back = tl.getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) back = p.getLocation().getDirection().setY(0);
        back.normalize().multiply(-v(p, 2, "behind-distance", 1.5));
        Location dest = tl.clone().add(back);
        dest.setDirection(tl.toVector().subtract(dest.toVector()));
        final Location slashFrom = p.getLocation().add(0, 1, 0);
        p.teleport(dest);
        Fx.slash(slashFrom, dest.clone().add(0, 1, 0), t);
        Status.set(Status.swift, p, v(p, 2, "window-seconds", 10));
        snd(p, "entity.enderman.teleport");
        return true;
    }

    // ================================================================ YMIR
    static boolean forcedDuel(Player p) {
        Duel ex = duels.get(p.getUniqueId());
        if (ex != null) { endDuel(ex, true); return false; }
        LivingEntity t = target(p, v(p, 1, "range", 20));
        if (!(t instanceof Player o)) { msg(p, "Look at a player to duel."); return false; }
        double r = v(p, 1, "radius", 25);
        Location c = p.getLocation().clone();
        Duel d = new Duel(p.getUniqueId(), o.getUniqueId(), c, r);
        duels.put(p.getUniqueId(), d);

        for (Player x : c.getWorld().getPlayers()) {
            if (x.equals(p) || x.equals(o) || !inside(d, x.getLocation())) continue;
            Vector away = x.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            away.normalize().multiply(r + 3);
            Location out = c.clone().add(away);
            out.setY(c.getWorld().getHighestBlockYAt(out) + 1);
            x.teleport(out);
        }
        WorldBorder wb = Bukkit.createWorldBorder();
        wb.setCenter(c);
        wb.setSize(r * 2);
        wb.setWarningDistance(0);
        wb.setDamageAmount(0);
        wb.setDamageBuffer(1000);
        p.setWorldBorder(wb);
        o.setWorldBorder(wb);
        Fx.duelStart(c, r);
        p.sendMessage(Component.text("Forced Duel started. Press the ability again to end it.", NamedTextColor.GOLD));
        o.sendMessage(Component.text("You were pulled into a Forced Duel with " + p.getName() + "!", NamedTextColor.RED));

        double max = v(p, 1, "max-seconds", 0);
        if (max > 0) {
            Bukkit.getScheduler().runTaskLater(pl(), () -> { if (duels.get(d.caster()) == d) endDuel(d, true); }, (long) (max * 20));
        }
        return false;   // cooldown starts when the duel ends
    }

    static boolean inside(Duel d, Location l) {
        Location c = d.center();
        return l.getWorld().equals(c.getWorld()) && Math.abs(l.getX() - c.getX()) <= d.r() && Math.abs(l.getZ() - c.getZ()) <= d.r();
    }

    static void endDuel(Duel d, boolean startCooldown) {
        if (duels.remove(d.caster()) == null) return;
        for (UUID u : new UUID[]{d.caster(), d.other()}) {
            Player q = Bukkit.getPlayer(u);
            if (q != null) q.setWorldBorder(null);
        }
        Player c = Bukkit.getPlayer(d.caster());
        if (c != null && startCooldown) {
            pl().pd(c).startCd(1, v(c, 1, "cooldown", 120));
            c.sendMessage(Component.text("The Forced Duel ended.", NamedTextColor.GOLD));
        }
    }

    static boolean impervious(Player p) {
        Status.set(Status.immortal, p, secs(p, v(p, 2, "seconds", 5)));
        Fx.shield(p, ticks(p, v(p, 2, "seconds", 5)));
        snd(p, "item.totem.use");
        return true;
    }

    // ================================================================ VARUNA
    static boolean tidalPull(Player p) {
        double r = v(p, 1, "radius", 15);
        boolean any = false;
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof Player q) || !validTarget(p, q)) continue;
            hurt(q, v(p, 1, "damage", 4), p);
            pull(q, p.getLocation(), v(p, 1, "pull", 2.5));
            line(q.getLocation().add(0, 1, 0), p.getLocation().add(0, 1, 0), Particle.BUBBLE);
            Fx.onTarget(q, "tidal");
            any = true;
        }
        Fx.tidalWave(p, r);
        if (!any) msg(p, "Nobody in range.");
        return any;
    }

    static boolean suffocation(Player p) {
        LivingEntity t = target(p, v(p, 2, "range", 25));
        if (t == null) { msg(p, "No target in sight."); return false; }
        int secs = (int) Math.round(secs(p, v(p, 2, "seconds", 5)));
        double dmg = v(p, 2, "damage-per-second", 1);
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (!t.isValid() || t.isDead() || i >= secs) { cancel(); return; }
                t.setRemainingAir(0);
                hurtRaw(t, dmg);
                Fx.onTarget(t, "suffocate");
                i++;
            }
        }.runTaskTimer(pl(), 0, 20);
        return true;
    }

    // ================================================================ AGNI
    static boolean magmaBath(Player p) {
        int R = (int) v(p, 1, "radius", 20);
        int dur = ticks(p, v(p, 1, "seconds", 20));
        boolean solid = cfg().getBoolean(path(p, 1, "replace-solid-blocks"), false);
        int perTick = (int) v(p, 1, "blocks-per-tick", 20000);
        Location c = p.getLocation().getBlock().getLocation();
        World w = c.getWorld();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        int size = 2 * R + 1;
        long total = (long) size * size * size;
        List<BlockState> saved = new ArrayList<>();
        Set<Long> mine = new HashSet<>();
        final boolean[] done = {false};
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, dur + 200, 0));
        snd(p, "block.lava.pop");
        Fx.magmaDome(c, R, dur + 60);

        Runnable restore = () -> {
            new BukkitRunnable() {
                int idx = saved.size() - 1;
                @Override public void run() {
                    int n = 0;
                    while (idx >= 0 && n++ < perTick) saved.get(idx--).update(true, false);
                    if (idx < 0) { shell.removeAll(mine); done[0] = true; cancel(); }
                }
            }.runTaskTimer(pl(), 0, 1);
        };

        new BukkitRunnable() {
            long idx = 0;
            @Override public void run() {
                int steps = 0;
                while (idx < total && steps++ < perTick) {
                    long i = idx++;
                    int x = (int) (i % size) - R, y = (int) ((i / size) % size) - R, z = (int) (i / ((long) size * size)) - R;
                    double d2 = (double) x * x + (double) y * y + (double) z * z;
                    if (d2 > (double) R * R) continue;
                    int by = cy + y;
                    if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
                    Block b = w.getBlockAt(cx + x, by, cz + z);
                    Material bm = b.getType();
                    boolean replaceable = b.isPassable() || b.isLiquid() || (solid && bm.getHardness() >= 0 && bm != Material.OBSIDIAN);
                    if (!replaceable) continue;
                    boolean isShell = d2 > (double) (R - 1) * (R - 1);
                    saved.add(b.getState());
                    if (isShell) { b.setType(Material.OBSIDIAN, false); mine.add(b.getBlockKey()); shell.add(b.getBlockKey()); }
                    else b.setType(Material.LAVA, false);
                }
                if (idx >= total) {
                    cancel();
                    Bukkit.getScheduler().runTaskLater(pl(), restore, dur);
                }
            }
        }.runTaskTimer(pl(), 0, 1);
        cleanups.add(() -> { if (done[0]) return; for (int i = saved.size() - 1; i >= 0; i--) saved.get(i).update(true, false); shell.removeAll(mine); done[0] = true; });
        return true;
    }

    static boolean fireball(Player p) {
        UUID id = p.getUniqueId();
        Charge ch = charges.get(id);
        if (ch == null) {
            Charge c = new Charge();
            c.start = System.currentTimeMillis();
            c.bar = Bukkit.createBossBar("Charge", BarColor.RED, BarStyle.SOLID);
            c.bar.addPlayer(p);
            c.task = new BukkitRunnable() {
                @Override public void run() {
                    if (!p.isOnline() || p.isDead()) { cancelCharge(p, false); return; }
                    double pr = chargeProgress(p, c);
                    c.bar.setProgress(Math.max(0, Math.min(1, pr)));
                    Fx.chargeFx(p, pr);
                    double el = (System.currentTimeMillis() - c.start) / 1000.0;
                    if (pr <= 0 && el > v(p, 2, "max-seconds", 60)) cancelCharge(p, true);
                }
            }.runTaskTimer(pl(), 0, 2);
            charges.put(id, c);
            return false;
        }
        double pr = chargeProgress(p, ch);
        double dmg = v(p, 2, "min-damage", 2) + (v(p, 2, "max-damage", 14) - v(p, 2, "min-damage", 2)) * Math.max(0, pr);
        cancelCharge(p, true);
        float power = (float) (v(p, 2, "explosion-power-min", 1.5)
                + (v(p, 2, "explosion-power-max", 6.0) - v(p, 2, "explosion-power-min", 1.5)) * Math.max(0, Math.min(1, pr)));
        fire(p, Material.MAGMA_BLOCK, false, v(p, 2, "range", 60), v(p, 2, "projectile-speed", 1.2), v(p, 2, "hit-radius", 1.2),
                t -> explode(p, t.getLocation().add(0, 1, 0), power, dmg, t),
                loc -> explode(p, loc, power, dmg, null));
        snd(p, "entity.blaze.shoot");
        return false;   // cooldown starts through cancelCharge(.., true)
    }

    static boolean exploding = false;   // lets CombatListener cancel vanilla explosion damage (we deal our own)

    /** Fireball impact: our own armor-ignoring damage plus a real explosion that can break blocks. */
    static void explode(Player p, Location at, float power, double dmg, LivingEntity direct) {
        World w = at.getWorld();
        double radius = Math.max(0.5, v(p, 2, "blast-radius", 5));
        double splash = v(p, 2, "splash-damage-multiplier", 0.6);
        double burn = v(p, 2, "burn-seconds", 5);
        boolean fireOn = cfg().getBoolean(path(p, 2, "set-fire"), true);
        boolean breakBlocks = cfg().getBoolean(path(p, 2, "break-blocks"), true);
        boolean self = cfg().getBoolean(path(p, 2, "self-damage"), false);
        for (Entity e : w.getNearbyEntities(at, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) continue;
            if (e.equals(p) ? !self : trusts(p, e)) continue;
            double f = e.equals(direct) ? 1.0 : Math.max(0, 1 - e.getLocation().distance(at) / radius) * splash;
            if (f <= 0) continue;
            hurt(le, dmg * f, p);
            if (burn > 0) le.setFireTicks((int) (burn * 20));
            Vector away = e.getLocation().toVector().subtract(at.toVector());
            if (away.lengthSquared() > 1e-4) le.setVelocity(away.normalize().multiply(0.6 * f + 0.2).setY(0.35));
        }
        exploding = true;
        try { w.createExplosion(at, power, fireOn, breakBlocks, p); } finally { exploding = false; }
        Fx.explosion(at, power);
    }

    static double chargeProgress(Player p, Charge c) {
        double el = (System.currentTimeMillis() - c.start) / 1000.0;
        double max = v(p, 2, "max-seconds", 60), decay = Math.max(0.1, v(p, 2, "decay-seconds", 8));
        return el <= max ? el / max : Math.max(0, 1 - (el - max) / decay);
    }

    static void cancelCharge(Player p, boolean startCd) {
        Charge c = charges.remove(p.getUniqueId());
        if (c == null) return;
        if (c.task != null) c.task.cancel();
        c.bar.removeAll();
        if (startCd) pl().pd(p).startCd(2, v(p, 2, "cooldown", 90));
    }

    // ================================================================ HEPHAESTUS
    static boolean shatter(Player p) {
        double r = v(p, 1, "radius", 10);
        int drain = (int) v(p, 1, "armor-drain", 25);
        boolean any = false;
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof Player q) || !validTarget(p, q)) continue;
            hurt(q, v(p, 1, "damage", 4), p);
            Gear.armorDelta(q, drain);
            Fx.onTarget(q, "shatter");
            any = true;
        }
        Fx.shockwave(p, r);
        snd(p, "entity.iron_golem.damage");
        if (!any) msg(p, "Nobody in range.");
        return any;
    }

    static boolean piercing(Player p) {
        Material m = Material.matchMaterial(s(p, 2, "item", "NETHERITE_AXE"));
        fire(p, m == null ? Material.IRON_AXE : m, true, v(p, 2, "range", 30), v(p, 2, "projectile-speed", 1.6), v(p, 2, "hit-radius", 1.0), t -> {
            hurt(t, v(p, 2, "damage", 2), p);
            Status.stun(t, v(p, 2, "stun-seconds", 2));
            Fx.onTarget(t, "axe");
            snd(t, "item.trident.hit");
        });
        return true;
    }


    // ================================================================ BOREUS
    static boolean breezyDash(Player p) {
        double dist = v(p, 1, "distance", 30), speed = v(p, 1, "speed", 2.0);
        Vector dir = p.getEyeLocation().getDirection().normalize();
        pl().pd(p).cd.remove("mace");                       // refreshes the axe-smash cooldown
        p.setFallDistance(0);
        snd(p, "entity.breeze.wind_burst");
        new BukkitRunnable() {
            double travelled = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { cancel(); return; }
                RayTraceResult hit = p.getWorld().rayTraceBlocks(p.getEyeLocation(), dir, speed + 0.6, FluidCollisionMode.NEVER, true);
                if (hit != null || travelled >= dist) {
                    if (hit == null) p.setVelocity(dir.clone().multiply(speed));   // keep the momentum at the end
                    cancel();
                    return;
                }
                p.setVelocity(dir.clone().multiply(speed));
                p.setFallDistance(0);
                Fx.dashTrail(p);
                travelled += speed;
            }
        }.runTaskTimer(pl(), 0, 1);
        return true;
    }

    static boolean overdrive(Player p) {
        if (Status.on(Status.overdrive, p)) return false;
        double sec = secs(p, v(p, 2, "seconds", 20));
        Status.set(Status.overdrive, p, sec);
        pl().pd(p).cd.remove("mace");
        applyWindburst(p, true);
        p.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, p.getLocation().add(0, 1, 0), 1);
        snd(p, "entity.breeze.charge");
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(pl(), () -> { Player q = Bukkit.getPlayer(id); if (q != null) endOverdrive(q); }, (long) (sec * 20));
        return true;
    }

    static void endOverdrive(Player p) {
        if (!Status.overdrive.containsKey(p.getUniqueId())) return;
        Status.overdrive.remove(p.getUniqueId());
        applyWindburst(p, false);
        double after = cfg().getDouble("classes.boreus.a2.mace-cooldown-after", 120);
        pl().pd(p).startKey("mace", after);                  // axe-smash passive goes on cooldown
    }

    /** Visual + tagged Windburst on every axe while Overdrive is active. */
    static void applyWindburst(Player p, boolean on) {
        PlayerInventory inv = p.getInventory();
        int lvl = (int) cfg().getDouble("classes.boreus.a2.windburst-level", 1);
        for (int i = 0; i <= 40; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || !s.getType().name().endsWith("_AXE")) continue;
            boolean tagged = s.getItemMeta().getPersistentDataContainer().has(pl().windKey, PersistentDataType.BYTE);
            if (on && !tagged) {
                s.addUnsafeEnchantment(Enchantment.WIND_BURST, lvl);
                s.editMeta(m -> m.getPersistentDataContainer().set(pl().windKey, PersistentDataType.BYTE, (byte) 1));
                inv.setItem(i, s);
            } else if (!on && tagged) {
                s.removeEnchantment(Enchantment.WIND_BURST);
                s.editMeta(m -> m.getPersistentDataContainer().remove(pl().windKey));
                inv.setItem(i, s);
            }
        }
    }


    // ================================================================ AZAROTH / MIDAS
    static boolean deepsCall(Player p) {
        if (!Pets.summon(p)) return false;
        pl().pd(p).startKey("a1", 86400);   // stays "on cooldown" while the Warden lives; real cooldown starts when it dies
        return false;
    }

    static boolean goldenTouch(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { msg(p, "Hold the item you want to duplicate."); return false; }
        if (Tag.SHULKER_BOXES.isTagged(hand.getType())) { msg(p, "Shulker boxes can't be duplicated."); return false; }
        if (hand.getType().name().endsWith("BUNDLE")) { msg(p, "Bundles can't be duplicated."); return false; }
        if (Items.kind(hand) != null) { msg(p, "Energy Marbles and Class Rerollers can't be duplicated."); return false; }
        if (Items.isTemp(hand) || list(p, 1, "blocked-materials").contains(hand.getType().name())) { msg(p, "That item can't be duplicated."); return false; }
        int copies = Math.max(1, (int) v(p, 1, "copies", 1));
        boolean whole = cfg().getBoolean(path(p, 1, "whole-stack"), true);
        for (int i = 0; i < copies; i++) {
            ItemStack c = hand.clone();
            if (!whole) c.setAmount(1);
            p.getInventory().addItem(c).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
        }
        snd(p, "block.amethyst_block.chime");
        return true;
    }

    static boolean bounty(Player p) {
        Status.set(Status.bounty, p, secs(p, v(p, 2, "seconds", 120)));
        p.sendMessage(Component.text("Bounty! Mob drops are x" + (int) v(p, 2, "multiplier", 20) + " for a while.", NamedTextColor.GOLD));
        snd(p, "entity.player.levelup");
        return true;
    }

    // ================================================================ cleanup
    static void cleanup(Player p) {
        UUID u = p.getUniqueId();
        for (Duel d : new ArrayList<>(duels.values()))
            if (d.caster().equals(u) || d.other().equals(u)) endDuel(d, true);
        cancelCharge(p, false);
        Pets.dismiss(p);
        endDual(p);
        revertBoost(p);
        Status.overdrive.remove(u);
        applyWindburst(p, false);
    }

    static void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) cleanup(p);
        for (Runnable r : new ArrayList<>(cleanups)) r.run();
        cleanups.clear();
        shell.clear();
    }
}
