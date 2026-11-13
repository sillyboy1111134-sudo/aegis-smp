package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.SculkShrieker;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Azaroth - Warden's Domain: corrupts the area into a deep-dark biome, blinds enemies, fires warden beams. */
final class Domain {
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    static boolean start(Player p) {
        Aegis pl = Aegis.I;
        int R = Math.max(12, (int) Abilities.v(p, 2, "radius", 30));
        int dur = Abilities.ticks(p, Abilities.v(p, 2, "seconds", 60));
        int perTick = Math.max(200, (int) Abilities.v(p, 2, "blocks-per-tick", 1500));
        boolean restore = pl.getConfig().getBoolean(Abilities.path(p, 2, "restore"), true);
        int interval = Math.max(5, (int) Abilities.v(p, 2, "beam-interval-ticks", 40));
        int beams = (int) Abilities.v(p, 2, "beams-per-interval", 3);
        double beamDmg = Abilities.v(p, 2, "beam-damage", 6.0);
        double beamRange = Abilities.v(p, 2, "beam-range", 45);
        double aim = Abilities.v(p, 2, "aim-at-enemies-chance", 0.5);
        List<String> darkness = Abilities.list(p, 2, "effects");
        Set<String> protectedMats = new HashSet<>(Abilities.list(p, 2, "protected-materials"));

        // the portal sits a few blocks in front of you, so you stand on the plaza facing it
        World w = p.getWorld();
        Location cl = p.getLocation();
        BlockFace face = p.getFacing();
        int offset = (int) Abilities.v(p, 2, "center-offset", 5);
        int px = cl.getBlockX() + face.getModX() * offset, pz = cl.getBlockZ() + face.getModZ() * offset;
        int py = cl.getBlockY() - 1;
        for (int y = cl.getBlockY() - 1; y >= cl.getBlockY() - 6; y--) {
            Material m = w.getBlockAt(cl.getBlockX(), y, cl.getBlockZ()).getType();
            if (m.isSolid()) { py = y; break; }
        }

        AncientCity.Opts opts = new AncientCity.Opts(
                Abilities.v(p, 2, "ruin-amount", 1.0), Abilities.v(p, 2, "building-density", 1.0), protectedMats,
                (int) Abilities.v(p, 2, "portal-inner-width", 3), (int) Abilities.v(p, 2, "portal-inner-height", 5),
                Abilities.v(p, 2, "vein-chance", 0.35), Abilities.v(p, 2, "sensor-chance", 0.03),
                Abilities.v(p, 2, "calibrated-chance", 0.015), Abilities.v(p, 2, "shrieker-chance", 0.01),
                Abilities.v(p, 2, "catalyst-chance", 0.015));
        AncientCity city = AncientCity.plan(w, px, pz, py, R, face, new Random(System.nanoTime() ^ pl.getName().hashCode()), opts);

        UUID id = p.getUniqueId();
        Location center = new Location(w, px + 0.5, py + 1.5, pz + 0.5);
        List<BlockState> saved = new ArrayList<>();
        boolean[] restored = {false};
        BukkitTask[] builder = new BukkitTask[1];
        Iterator<Map.Entry<AncientCity.Pos, BlockData>> it = city.blocks.entrySet().iterator();

        // ---- raise the city (batched over ticks)
        builder[0] = new BukkitRunnable() {
            @Override public void run() {
                int n = 0;
                while (it.hasNext() && n++ < perTick) {
                    Map.Entry<AncientCity.Pos, BlockData> en = it.next();
                    AncientCity.Pos pos = en.getKey();
                    if (pos.y() < w.getMinHeight() || pos.y() >= w.getMaxHeight()) continue;
                    Block b = w.getBlockAt(pos.x(), pos.y(), pos.z());
                    Material cur = b.getType();
                    Material want = en.getValue().getMaterial();
                    if (cur == want && want == Material.AIR) continue;
                    if (!cur.isAir() && (cur.getHardness() < 0 || protectedMats.contains(cur.name()) || b.getState(false) instanceof TileState)) continue;
                    saved.add(b.getState());
                    b.setBlockData(en.getValue(), false);
                }
                if (!it.hasNext()) cancel();
            }
        }.runTaskTimer(pl, 0, 1);

        // ---- restore
        Runnable restoreAll = () -> {
            if (builder[0] != null) builder[0].cancel();
            if (!restore) { restored[0] = true; return; }
            new BukkitRunnable() {
                int i = saved.size() - 1;
                @Override public void run() {
                    int n = 0;
                    while (i >= 0 && n++ < perTick) saved.get(i--).update(true, false);
                    if (i < 0) { restored[0] = true; cancel(); }
                }
            }.runTaskTimer(pl, 0, 1);
        };
        if (restore) Abilities.cleanups.add(() -> {
            if (restored[0]) return;
            for (int i = saved.size() - 1; i >= 0; i--) saved.get(i).update(true, false);
            restored[0] = true;
        });

        // ---- effects loop: darkness, warden beams, ambience
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player caster = Bukkit.getPlayer(id);
                if (caster == null || t >= dur) { cancel(); restoreAll.run(); return; }
                if (t % 20 == 0) {
                    for (Player q : w.getPlayers()) {
                        if (q.equals(caster) || q.isDead() || Abilities.trusts(caster, q)) continue;
                        if (q.getLocation().distanceSquared(center) <= (double) R * R) Abilities.give(caster, q, darkness, false);
                    }
                }
                if (t % interval == 0) {
                    for (int i = 0; i < beams; i++) shoot(caster, center, R, beamRange, beamDmg, aim);
                }
                if (t % 5 == 0) Fx.domainAmbient(center, R);
                t += 5;
            }
        }.runTaskTimer(pl, 0, 5);

        p.getWorld().playSound(center, "entity.warden.roar", 1.5f, 0.8f);
        for (Player q : w.getPlayers())
            if (!q.equals(p) && q.getLocation().distanceSquared(center) <= (double) R * R)
                q.sendMessage(Component.text("You are inside a Warden's Domain!", NamedTextColor.DARK_AQUA));
        return true;
    }

    private static void shoot(Player caster, Location center, int R, double range, double dmg, double aim) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World w = center.getWorld();
        double ang = rnd.nextDouble() * Math.PI * 2, rr = R * (0.3 + 0.6 * rnd.nextDouble());
        Location origin = center.clone().add(Math.cos(ang) * rr, 2 + rnd.nextDouble() * Math.min(12, Math.max(2, R * 0.6)), Math.sin(ang) * rr);

        Location aimAt = null;
        if (rnd.nextDouble() < aim) {
            List<Player> enemies = new ArrayList<>();
            for (Player q : w.getPlayers())
                if (!q.equals(caster) && !q.isDead() && !Abilities.trusts(caster, q) && q.getLocation().distanceSquared(center) <= (double) R * R) enemies.add(q);
            if (!enemies.isEmpty()) aimAt = enemies.get(rnd.nextInt(enemies.size())).getLocation().add(0, 1, 0);
        }
        if (aimAt == null) {
            double a2 = rnd.nextDouble() * Math.PI * 2, r2 = R * rnd.nextDouble();
            aimAt = center.clone().add(Math.cos(a2) * r2, rnd.nextDouble() * 2 - 1, Math.sin(a2) * r2);
        }
        Vector dir = aimAt.toVector().subtract(origin.toVector());
        if (dir.lengthSquared() < 1e-4) return;
        dir.normalize().add(new Vector(rnd.nextGaussian() * 0.04, rnd.nextGaussian() * 0.04, rnd.nextGaussian() * 0.04)).normalize();

        RayTraceResult hit = w.rayTrace(origin, dir, range, FluidCollisionMode.NEVER, true, 0.6,
                en -> en instanceof LivingEntity && !en.equals(caster) && !(en instanceof ArmorStand)
                        && !Abilities.trusts(caster, en) && !Pets.isPet(en));
        Location end = hit == null ? origin.clone().add(dir.clone().multiply(range)) : hit.getHitPosition().toLocation(w);
        Fx.sonicBeam(origin, end);
        w.playSound(origin, "entity.warden.sonic_boom", 0.8f, 1f);
        if (hit != null && hit.getHitEntity() instanceof LivingEntity le) Abilities.hurt(le, dmg, caster);
    }
}
