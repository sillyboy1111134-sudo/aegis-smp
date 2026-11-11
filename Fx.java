package dev.aegis;

import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** All the visual flair. Turn it down with particles.intensity or off with particles.enabled. */
final class Fx {
    static boolean on() { return Aegis.I.getConfig().getBoolean("particles.enabled", true); }

    static int n(int c) { return Math.max(1, (int) Math.round(c * Aegis.I.getConfig().getDouble("particles.intensity", 1.0))); }

    // ================================================================ primitives
    static void part(Location l, Particle pt, int count, double dx, double dy, double dz, double speed) {
        if (on()) l.getWorld().spawnParticle(pt, l, n(count), dx, dy, dz, speed);
    }

    static void dust(Location l, int count, double spread, int rgb, float size) {
        if (on()) l.getWorld().spawnParticle(Particle.DUST, l, n(count), spread, spread, spread, 0,
                new Particle.DustOptions(Color.fromRGB(rgb), size));
    }

    static void block(Location l, Material m, int count, double spread) {
        if (on()) l.getWorld().spawnParticle(Particle.BLOCK, l, n(count), spread, spread, spread, 0, m.createBlockData());
    }

    /** Particles flying outward in every direction. */
    static void burst(Location l, Particle pt, int count, double speed) {
        if (!on()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int c = n(count);
        for (int i = 0; i < c; i++) {
            Vector v = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
            if (v.lengthSquared() < 1e-6) continue;
            v.normalize();
            l.getWorld().spawnParticle(pt, l, 0, v.getX(), v.getY(), v.getZ(), speed);
        }
    }

    // ================================================================ shapes
    static void ring(Location c, double r, int pts, Consumer<Location> f) {
        for (int i = 0; i < pts; i++) {
            double a = Math.PI * 2 * i / pts;
            f.accept(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r));
        }
    }

    static void sphere(Location c, double r, int pts, Consumer<Location> f) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < pts; i++) {
            double y = 1 - 2.0 * (i + 0.5) / pts;
            double rr = Math.sqrt(1 - y * y);
            double a = golden * i;
            f.accept(c.clone().add(Math.cos(a) * rr * r, y * r, Math.sin(a) * rr * r));
        }
    }

    static void line(Location a, Location b, double step, Consumer<Location> f) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.01) return;
        d.normalize();
        for (double i = 0; i <= len; i += step) f.accept(a.clone().add(d.clone().multiply(i)));
    }

    /** Ring that grows (or shrinks) over a number of ticks. */
    static void expand(Location c, double from, double to, int ticks, int pts, Consumer<Location> f) {
        if (!on()) return;
        Location base = c.clone();
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                double r = from + (to - from) * t / Math.max(1, ticks);
                ring(base, r, pts, f);
                if (++t > ticks) cancel();
            }
        }.runTaskTimer(Aegis.I, 0, 1);
    }

    /** Runs f every tick at the entity's position for a while. */
    static void follow(Entity e, int ticks, BiConsumer<Location, Integer> f) {
        if (!on()) return;
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!e.isValid() || t >= ticks) { cancel(); return; }
                f.accept(e.getLocation(), t);
                t++;
            }
        }.runTaskTimer(Aegis.I, 0, 1);
    }

    static void spiral(Location base, int t, double r, double h, int strands, Consumer<Location> f) {
        for (int s = 0; s < strands; s++) {
            double a = t * 0.35 + s * Math.PI * 2 / strands;
            double y = (t * 0.09) % h;
            f.accept(base.clone().add(Math.cos(a) * r, y, Math.sin(a) * r));
        }
    }

    static void beam(Location a, Location b, Particle pt, int rgb) {
        if (!on()) return;
        line(a, b, 0.35, l -> { part(l, pt, 1, 0.03, 0.03, 0.03, 0.01); dust(l, 1, 0.03, rgb, 1.3f); });
    }

    // ================================================================ caster effects (ability just fired)
    static void cast(Player pl, ClassType t, int n) {
        if (!on()) return;
        Location feet = pl.getLocation().add(0, 0.2, 0);
        Location mid = pl.getLocation().add(0, 1, 0);
        switch (t) {
            case SHACKLED -> {
                if (n == 1) {
                    expand(feet, .5, 5, 10, 28, l -> { part(l, Particle.CRIT, 1, 0, 0, 0, 0); dust(l, 1, 0, 0x9E9E9E, 1.4f); });
                    burst(mid, Particle.ENCHANTED_HIT, 40, .5);
                } else {
                    burst(mid.clone().add(pl.getLocation().getDirection()), Particle.CRIT, 30, .4);
                    expand(feet, .3, 3, 8, 20, l -> part(l, Particle.CRIT, 1, 0, 0, 0, 0));
                }
            }
            case MOANDER -> {
                if (n == 1) {
                    expand(feet, .5, 8, 14, 36, l -> { part(l, Particle.SOUL, 1, 0, .1, 0, .02); dust(l, 1, 0, 0x1B5E20, 1.5f); });
                    follow(pl, 40, (l, k) -> spiral(l, k, 1.3, 2.5, 3, x -> { part(x, Particle.SOUL, 1, 0, 0, 0, 0); part(x, Particle.HAPPY_VILLAGER, 1, 0, 0, 0, 0); }));
                } else {
                    burst(mid, Particle.SOUL_FIRE_FLAME, 30, .15);
                    dust(mid, 20, .6, 0x101010, 1.8f);
                }
            }
            case CHRONO -> {
                if (n == 2) {
                    int dur = Abilities.ticks(pl, Abilities.v(pl, 2, "seconds", 20));
                    expand(feet, 4, .5, 10, 30, l -> { part(l, Particle.REVERSE_PORTAL, 1, 0, .1, 0, 0); dust(l, 1, 0, 0xFFD54F, 1.4f); });
                    follow(pl, dur, (l, k) -> {
                        if (k % 2 != 0) return;
                        for (int s = 0; s < 2; s++) {
                            double a = -k * 0.3 + s * Math.PI;
                            double y = 2.4 - ((k * 0.08) % 2.4);
                            Location x = l.clone().add(Math.cos(a) * 1.1, y, Math.sin(a) * 1.1);
                            part(x, Particle.REVERSE_PORTAL, 1, 0, 0, 0, 0);
                            dust(x, 1, 0, 0xFFD54F, 1.2f);
                        }
                    });
                }
            }
            case ICOR -> {
                if (n == 1) { dust(mid, 25, .5, 0xB00020, 1.6f); burst(mid, Particle.DAMAGE_INDICATOR, 10, .2); }
                else {
                    expand(feet, .5, 5, 10, 28, l -> dust(l, 1, 0, 0xB00020, 1.6f));
                    part(mid.clone().add(0, 1, 0), Particle.HEART, 8, .5, .4, .5, 0);
                }
            }
            case BERSERKER -> {
                if (n == 1) {
                    follow(pl, 60, (l, k) -> {
                        part(l.clone().add(0, 1, 0), Particle.FLAME, 2, .4, .6, .4, .01);
                        spiral(l, k, 1.0, 2.2, 2, x -> dust(x, 1, 0, 0xD50000, 1.4f));
                    });
                    expand(feet, .5, 6, 10, 30, l -> { dust(l, 1, 0, 0xD50000, 1.6f); part(l, Particle.FLAME, 1, 0, .05, 0, .01); });
                    part(pl.getEyeLocation().add(0, .5, 0), Particle.ANGRY_VILLAGER, 4, .3, .2, .3, 0);
                    burst(mid, Particle.FLAME, 40, .2);
                }
            }
            case DOLUS -> {
                block(feet, Material.STONE, 40, .8);
                expand(feet, .5, 4, 8, 24, l -> block(l, Material.DIRT, 2, .15));
                burst(feet, Particle.POOF, 20, .15);
            }
            case SAMURAI -> {
                if (n == 1) {
                    follow(pl, 50, (l, k) -> spiral(l, k, 1.0, 2.2, 3, x -> { dust(x, 1, 0, 0xEEEEEE, 1.2f); part(x, Particle.ENCHANTED_HIT, 1, 0, 0, 0, 0); }));
                    burst(mid, Particle.SWEEP_ATTACK, 3, 0);
                }
            }
            case HEPHAESTUS -> {
                if (n == 2) burst(mid, Particle.CRIT, 30, .4);
            }
            case BOREUS -> {
                if (n == 1) {
                    expand(feet, .3, 4, 8, 24, l -> part(l, Particle.CLOUD, 1, 0, .05, 0, .02));
                    part(mid, Particle.GUST_EMITTER_SMALL, 1, 0, 0, 0, 0);
                } else {
                    int dur = Abilities.ticks(pl, Abilities.v(pl, 2, "seconds", 20));
                    for (int h = 0; h < 3; h++) { final double yy = h * 1.0; expand(feet.clone().add(0, yy, 0), .5, 5, 12, 28, l -> { part(l, Particle.CLOUD, 1, 0, 0, 0, .02); dust(l, 1, 0, 0x80DEEA, 1.4f); }); }
                    part(mid, Particle.GUST_EMITTER_LARGE, 1, 0, 0, 0, 0);
                    follow(pl, dur, (l, k) -> spiral(l, k, 1.2, 2.4, 3, x -> { part(x, Particle.CLOUD, 1, 0, 0, 0, 0); if (k % 4 == 0) part(x, Particle.END_ROD, 1, 0, 0, 0, 0); }));
                }
            }
            default -> { }
        }
    }

    // ================================================================ target effects
    static void onTarget(LivingEntity t, String kind) {
        if (!on()) return;
        Location feet = t.getLocation().add(0, 0.3, 0);
        Location mid = t.getLocation().add(0, t.getHeight() / 2, 0);
        switch (kind) {
            case "chain" -> {
                for (int h = 0; h < 3; h++) {
                    final double yy = h * 0.7;
                    expand(feet.clone().add(0, yy, 0), 1.8, 0.6, 8, 20, l -> { part(l, Particle.CRIT, 1, 0, 0, 0, 0); dust(l, 1, 0, 0x9E9E9E, 1.4f); });
                }
                burst(mid, Particle.ENCHANTED_HIT, 30, .4);
                follow(t, 40, (l, k) -> spiral(l, k, .8, 2, 2, x -> dust(x, 1, 0, 0x757575, 1.2f)));
            }
            case "wither" -> {
                burst(mid, Particle.SOUL_FIRE_FLAME, 25, .12);
                dust(mid, 30, .5, 0x101010, 1.8f);
                follow(t, 60, (l, k) -> { if (k % 3 == 0) part(l.clone().add(0, t.getHeight(), 0), Particle.SMOKE, 2, .2, .1, .2, .01); });
            }
            case "blood" -> {
                block(mid, Material.REDSTONE_BLOCK, 30, .5);
                burst(mid, Particle.DAMAGE_INDICATOR, 10, .2);
                dust(mid, 30, .6, 0xB00020, 1.6f);
            }
            case "lifesteal" -> {
                expand(feet, 2, .3, 8, 24, l -> dust(l, 1, 0, 0xB00020, 1.6f));
                part(mid.clone().add(0, 1, 0), Particle.HEART, 6, .4, .3, .4, 0);
            }
            case "tidal" -> {
                burst(mid, Particle.BUBBLE_POP, 25, .25);
                part(mid, Particle.SPLASH, 40, .4, .6, .4, 0);
                for (double y = 0; y < 3; y += .5) part(feet.clone().add(0, y, 0), Particle.SPLASH, 6, .2, 0, .2, 0);
            }
            case "suffocate" -> {
                Location eye = t.getEyeLocation();
                part(eye, Particle.BUBBLE, 14, .3, .3, .3, .05);
                sphere(eye, .6, 10, x -> dust(x, 1, 0, 0x29B6F6, 1.3f));
                part(eye, Particle.DRIPPING_WATER, 4, .3, .2, .3, 0);
            }
            case "shatter" -> {
                block(mid, Material.IRON_BLOCK, 40, .5);
                burst(mid, Particle.CRIT, 25, .4);
                part(mid, Particle.EXPLOSION, 1, 0, 0, 0, 0);
            }
            case "axe" -> {
                burst(mid, Particle.CRIT, 35, .5);
                part(mid, Particle.SWEEP_ATTACK, 1, 0, 0, 0, 0);
                part(mid, Particle.ENCHANTED_HIT, 30, .4, .6, .4, .3);
            }
            case "quake" -> {
                block(mid, Material.DIRT, 50, .5);
                expand(feet, .3, 3, 6, 20, l -> block(l, Material.DIRT, 2, .1));
                part(mid, Particle.EXPLOSION, 1, 0, 0, 0, 0);
            }
            case "fire" -> {
                part(mid, Particle.EXPLOSION_EMITTER, 1, 0, 0, 0, 0);
                burst(mid, Particle.FLAME, 60, .3);
                expand(feet, .5, 5, 8, 36, l -> { part(l, Particle.FLAME, 1, 0, .1, 0, .02); part(l, Particle.LAVA, 1, 0, 0, 0, 0); });
                burst(mid, Particle.LARGE_SMOKE, 20, .15);
            }
            default -> { }
        }
    }

    /** Projectile trail, called every tick by Abilities.fire. */
    static void trail(Location l, Material m) {
        if (!on()) return;
        switch (m) {
            case REDSTONE_BLOCK -> { dust(l, 6, .15, 0xB00020, 1.4f); part(l, Particle.DAMAGE_INDICATOR, 1, .1, .1, .1, 0); }
            case DIRT -> block(l, Material.DIRT, 8, .2);
            case MAGMA_BLOCK -> { part(l, Particle.FLAME, 8, .2, .2, .2, .02); part(l, Particle.LAVA, 1, .1, .1, .1, 0); part(l, Particle.SMOKE, 3, .15, .15, .15, .01); dust(l, 4, .2, 0xFF6D00, 1.6f); }
            default -> { part(l, Particle.CRIT, 4, .1, .1, .1, .05); part(l, Particle.ENCHANTED_HIT, 2, .1, .1, .1, .05); part(l, Particle.CLOUD, 1, .05, .05, .05, 0); }
        }
    }

    static void slash(Location from, Location to, LivingEntity target) {
        if (!on()) return;
        line(from, to, 0.3, l -> { part(l, Particle.REVERSE_PORTAL, 1, .05, .05, .05, 0); dust(l, 1, .05, 0xEEEEEE, 1.2f); });
        sphere(from, 1.0, 30, l -> part(l, Particle.PORTAL, 1, 0, 0, 0, .2));
        sphere(to, 1.0, 30, l -> part(l, Particle.PORTAL, 1, 0, 0, 0, .2));
        Location m = target.getLocation().add(0, target.getHeight() / 2, 0);
        part(m, Particle.SWEEP_ATTACK, 2, .3, .3, .3, 0);
        burst(m, Particle.CRIT, 30, .5);
    }

    static void spin(Player p, double r) {
        if (!on()) return;
        expand(p.getLocation().add(0, 1, 0), .5, r, 3, 24, l -> { part(l, Particle.SWEEP_ATTACK, 1, 0, 0, 0, 0); dust(l, 1, 0, 0xC62828, 1.5f); });
        burst(p.getLocation().add(0, 1, 0), Particle.CRIT, 20, .5);
    }

    static void shockwave(Player p, double r) {
        if (!on()) return;
        Location g = p.getLocation().add(0, 0.2, 0);
        expand(g, .5, r, 8, 40, l -> { part(l, Particle.CRIT, 1, 0, .05, 0, .05); part(l, Particle.LARGE_SMOKE, 1, 0, .1, 0, .01); dust(l, 1, 0, 0xFF6D00, 1.6f); });
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            line(g, g.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 0.6, l -> part(l, Particle.CRIT, 1, 0, 0, 0, 0));
        }
        part(g.clone().add(0, .8, 0), Particle.EXPLOSION, 6, r / 4, .4, r / 4, 0);
        block(g, Material.IRON_BLOCK, 50, 1.2);
    }

    static void tidalWave(Player p, double r) {
        if (!on()) return;
        expand(p.getLocation().add(0, .3, 0), r, .5, 12, 40, l -> { part(l, Particle.SPLASH, 2, .1, .1, .1, 0); part(l, Particle.BUBBLE_POP, 1, 0, .2, 0, 0); dust(l, 1, 0, 0x29B6F6, 1.5f); });
        burst(p.getLocation().add(0, 1, 0), Particle.SPLASH, 40, .4);
    }

    static void timeStop(Player p, double r, int dur) {
        if (!on()) return;
        Location c = p.getLocation().add(0, 1, 0);
        for (int h = 0; h < 3; h++) {
            final double yy = h - 1;
            expand(c.clone().add(0, yy, 0), 1, r, 12, 48, l -> { part(l, Particle.END_ROD, 1, 0, 0, 0, 0); dust(l, 1, 0, 0xFFD54F, 1.5f); });
        }
        part(c, Particle.END_ROD, 60, 1, 1, 1, .3);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= dur) { cancel(); return; }
                if (t % 5 == 0) sphere(c, r, 70, x -> dust(x, 1, 0, 0xFFD54F, 1.3f));
                double a = t * 0.25;
                line(c, c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 0.5, x -> part(x, Particle.END_ROD, 1, 0, 0, 0, 0));
                line(c, c.clone().add(Math.cos(a * 0.2) * r * .6, 0, Math.sin(a * 0.2) * r * .6), 0.5, x -> part(x, Particle.END_ROD, 1, 0, 0, 0, 0));
                t++;
            }
        }.runTaskTimer(Aegis.I, 0, 1);
    }

    static void shield(Player p, int ticks) {
        if (!on()) return;
        burst(p.getLocation().add(0, 1, 0), Particle.TOTEM_OF_UNDYING, 40, .4);
        follow(p, ticks, (l, k) -> {
            if (k % 2 == 0) sphere(l.clone().add(0, 1, 0), 1.3, 14, x -> { dust(x, 1, 0, 0xFFD54F, 1.3f); if (k % 6 == 0) part(x, Particle.END_ROD, 1, 0, 0, 0, 0); });
        });
    }

    static void duelStart(Location c, double r) {
        if (!on()) return;
        burst(c.clone().add(0, 1, 0), Particle.FLAME, 60, .4);
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
            Location corner = c.clone().add(sx * r, 0, sz * r);
            for (double y = -2; y <= 12; y += 0.5) { Location l = corner.clone().add(0, y, 0); dust(l, 1, 0, 0xFF1744, 2f); part(l, Particle.END_ROD, 1, 0, 0, 0, 0); }
        }
    }

    static void arenaWalls(Abilities.Duel d, Player p) {
        if (!on()) return;
        Location c = d.center(), pl = p.getLocation();
        if (!pl.getWorld().equals(c.getWorld())) return;
        World w = pl.getWorld();
        double dx = pl.getX() - c.getX(), dz = pl.getZ() - c.getZ(), r = d.r();
        if (r - Math.abs(dx) < 12) {
            double wx = c.getX() + Math.signum(dx) * r;
            for (double k = -10; k <= 10; k += 2) for (double y = -1; y <= 5; y += 1.5) dust(new Location(w, wx, pl.getY() + y, pl.getZ() + k), 1, 0, 0xFF1744, 1.6f);
        }
        if (r - Math.abs(dz) < 12) {
            double wz = c.getZ() + Math.signum(dz) * r;
            for (double k = -10; k <= 10; k += 2) for (double y = -1; y <= 5; y += 1.5) dust(new Location(w, pl.getX() + k, pl.getY() + y, wz), 1, 0, 0xFF1744, 1.6f);
        }
    }

    static void magmaDome(Location c, int R, int ticks) {
        if (!on()) return;
        Location g = c.clone().add(0.5, 0.2, 0.5);
        expand(g, 1, R, 20, 60, l -> { part(l, Particle.FLAME, 2, 0, .2, 0, .03); part(l, Particle.LAVA, 1, 0, 0, 0, 0); });
        burst(g.clone().add(0, 1, 0), Particle.EXPLOSION_EMITTER, 1, 0);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= ticks) { cancel(); return; }
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < n(30); i++) {
                    Vector v = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
                    if (v.lengthSquared() < 1e-6) continue;
                    v.normalize().multiply((R - 1) * Math.cbrt(r.nextDouble()));
                    Location x = g.clone().add(v);
                    c.getWorld().spawnParticle(i % 3 == 0 ? Particle.LAVA : (i % 3 == 1 ? Particle.FLAME : Particle.SMOKE), x, 1, 0, 0, 0, 0.01);
                }
                sphere(g, R - 0.5, 25, x -> dust(x, 1, 0, 0xFF6D00, 1.6f));
                t += 4;
            }
        }.runTaskTimer(Aegis.I, 0, 4);
    }

    static void chargeFx(Player p, double pr) {
        if (!on()) return;
        Location head = p.getLocation().add(0, 2.8, 0);
        int orbit = 3 + (int) (pr * 5);
        double ang = System.currentTimeMillis() / 90.0, rad = .4 + pr * 1.0;
        for (int i = 0; i < orbit; i++) {
            double a = ang + i * Math.PI * 2 / orbit;
            Location x = head.clone().add(Math.cos(a) * rad, Math.sin(ang * .5 + i) * .2, Math.sin(a) * rad);
            part(x, Particle.FLAME, 1, 0, 0, 0, .01);
        }
        double sp = .2 + pr * .25;
        part(head, Particle.FLAME, 2 + (int) (pr * 8), sp, sp, sp, .01);
        part(head, Particle.LARGE_SMOKE, 1, sp, sp, sp, .01);
        dust(head, 3, sp, 0xFF6D00, 1.8f);
        ring(p.getLocation().add(0, .1, 0), 1 + pr * 3, 16, l -> part(l, Particle.FLAME, 1, 0, 0, 0, 0));
    }

    // ================================================================ passives / small effects
    static void proc(LivingEntity t, ConfigurationSection n) {
        if (!on()) return;
        Location m = t.getLocation().add(0, t.getHeight() / 2, 0);
        String fx = n.getString("effect", "");
        switch (n.getString("action", "")) {
            case "bleed" -> { dust(m, 25, .4, 0xB71C1C, 1.5f); burst(m, Particle.DAMAGE_INDICATOR, 8, .1); }
            case "ignite" -> { burst(m, Particle.FLAME, 40, .12); part(m, Particle.LAVA, 8, .4, .4, .4, 0); }
            case "enchain" -> expand(t.getLocation().add(0, .5, 0), 1.6, .5, 8, 20, l -> { part(l, Particle.CRIT, 1, 0, 0, 0, 0); dust(l, 1, 0, 0x9E9E9E, 1.3f); });
            case "shuffle" -> burst(m, Particle.WITCH, 25, .2);
            case "effect" -> {
                switch (fx) {
                    case "wither" -> { burst(m, Particle.SOUL, 15, .1); dust(m, 25, .5, 0x101010, 1.7f); }
                    case "poison" -> { part(m, Particle.HAPPY_VILLAGER, 12, .4, .5, .4, 0); dust(m, 20, .5, 0x4CAF50, 1.5f); }
                    case "slowness" -> { burst(m, Particle.SNOWFLAKE, 25, .1); dust(m, 20, .5, 0x90CAF9, 1.5f); }
                    default -> burst(m, Particle.ENCHANTED_HIT, 15, .2);
                }
            }
            default -> { }
        }
    }

    static void stars(LivingEntity e, double sec) {
        if (!on()) return;
        follow(e, (int) (sec * 20), (l, t) -> {
            if (t % 2 != 0) return;
            double a = t * 0.5;
            for (int s = 0; s < 3; s++) {
                Location x = l.clone().add(Math.cos(a + s * 2.09) * .5, e.getHeight() + .3, Math.sin(a + s * 2.09) * .5);
                part(x, Particle.CRIT, 1, 0, 0, 0, 0);
                part(x, Particle.ENCHANTED_HIT, 1, 0, 0, 0, 0);
            }
        });
    }

    static void smash(Location l, boolean overdrive) {
        if (!on()) return;
        Location g = l.clone().add(0, .2, 0);
        expand(g, .5, overdrive ? 7 : 5, 8, 36, x -> { part(x, Particle.CLOUD, 1, 0, .1, 0, .05); block(x, Material.STONE, 1, .1); });
        block(g, Material.STONE, 40, 1);
        if (overdrive) { part(g, Particle.EXPLOSION_EMITTER, 1, 0, 0, 0, 0); part(g, Particle.GUST_EMITTER_LARGE, 1, 0, 0, 0, 0); }
        else part(g, Particle.EXPLOSION, 3, .4, .2, .4, 0);
    }

    static void windburst(Player p) {
        if (!on()) return;
        part(p.getLocation(), Particle.GUST_EMITTER_SMALL, 1, 0, 0, 0, 0);
        follow(p, 14, (l, k) -> { part(l, Particle.CLOUD, 6, .4, .1, .4, .02); part(l, Particle.END_ROD, 2, .3, .1, .3, .02); });
    }

    static void comboCrit(LivingEntity v) {
        if (!on()) return;
        Location m = v.getLocation().add(0, v.getHeight() / 2, 0);
        burst(m, Particle.CRIT, 30, .4);
        part(m, Particle.SWEEP_ATTACK, 2, .3, .3, .3, 0);
        part(m, Particle.ENCHANTED_HIT, 30, .4, .5, .4, .3);
    }

    static void heal(Player p) {
        if (!on()) return;
        Location m = p.getLocation().add(0, 1, 0);
        part(m.clone().add(0, .8, 0), Particle.HEART, 3, .4, .3, .4, 0);
        dust(m, 10, .5, 0xB00020, 1.3f);
    }

    static void dashTrail(Player p) {
        if (!on()) return;
        Location l = p.getLocation().add(0, 1, 0);
        part(l, Particle.CLOUD, 8, .35, .5, .35, .02);
        part(l, Particle.ELECTRIC_SPARK, 4, .3, .4, .3, .05);
        part(l, Particle.POOF, 3, .3, .4, .3, .02);
        dust(l, 4, .35, 0x80DEEA, 1.4f);
    }

    static void energy(Player p) {
        if (!on()) return;
        Location m = p.getLocation().add(0, 1, 0);
        burst(m, Particle.ELECTRIC_SPARK, 50, .6);
        expand(p.getLocation().add(0, .2, 0), .5, 3, 8, 24, l -> { part(l, Particle.ELECTRIC_SPARK, 1, 0, .1, 0, 0); dust(l, 1, 0, 0x40C4FF, 1.4f); });
        follow(p, 30, (l, k) -> spiral(l, k, .9, 2.2, 3, x -> dust(x, 1, 0, 0x40C4FF, 1.3f)));
    }

    static void reveal(Player p) {
        if (!on()) return;
        Location m = p.getLocation().add(0, 1, 0);
        burst(m, Particle.FIREWORK, 80, .5);
        burst(m, Particle.TOTEM_OF_UNDYING, 60, .6);
        expand(p.getLocation().add(0, .2, 0), .5, 5, 12, 32, l -> { part(l, Particle.END_ROD, 1, 0, .1, 0, .02); dust(l, 1, 0, 0xFFD54F, 1.5f); });
        follow(p, 40, (l, k) -> spiral(l, k, 1.1, 2.4, 4, x -> part(x, Particle.END_ROD, 1, 0, 0, 0, 0)));
    }

    /** Constant, class-coloured aura. Gets denser with every energy. */
    static void ambient(Player p) {
        if (!on() || !Aegis.I.getConfig().getBoolean("particles.ambient", true)) return;
        PlayerData d = Aegis.I.pd(p);
        if (d.type == null || d.rolling || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;
        double a = System.currentTimeMillis() / 400.0;
        int cnt = 1 + d.energy;
        Location base = p.getLocation().add(0, 1, 0);
        for (int i = 0; i < cnt; i++) {
            double ang = a + i * (Math.PI * 2 / cnt);
            Location l = base.clone().add(Math.cos(ang) * 0.9, Math.sin(a * 1.3 + i) * 0.6, Math.sin(ang) * 0.9);
            switch (d.type) {
                case SHACKLED -> dust(l, 1, 0, 0x9E9E9E, 1.1f);
                case MOANDER -> part(l, Particle.SOUL, 1, 0, 0, 0, 0);
                case CHRONO -> part(l, Particle.END_ROD, 1, 0, 0, 0, 0);
                case ICOR -> dust(l, 1, 0, 0xB00020, 1.2f);
                case BERSERKER -> { dust(l, 1, 0, 0x8B0000, 1.2f); if (i == 0) part(l, Particle.CRIT, 1, 0, 0, 0, 0); }
                case DOLUS -> block(l, Material.STONE, 1, 0);
                case SAMURAI -> part(l, Particle.ENCHANTED_HIT, 1, 0, 0, 0, 0);
                case YMIR -> part(l, Particle.SNOWFLAKE, 1, 0, 0, 0, 0);
                case VARUNA -> { dust(l, 1, 0, 0x29B6F6, 1.1f); if (i == 0) part(l, Particle.DRIPPING_WATER, 1, 0, 0, 0, 0); }
                case AGNI -> part(l, Particle.FLAME, 1, 0, 0, 0, 0);
                case HEPHAESTUS -> { dust(l, 1, 0, 0xFF6D00, 1.2f); if (i == 0) part(l, Particle.LAVA, 1, 0, 0, 0, 0); }
                case BOREUS -> part(l, Particle.CLOUD, 1, 0, 0, 0, 0);
            }
        }
    }
}
