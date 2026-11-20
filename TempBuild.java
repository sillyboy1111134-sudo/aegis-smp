package dev.aegis;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Blocks placed for a domain: remembers what was there before and puts it back (in batches) when the domain ends. */
final class TempBuild {
    static final List<TempBuild> ALL = new ArrayList<>();
    private static final int PER_TICK = 2500;

    final World w;
    private final Map<Long, BlockData> original = new LinkedHashMap<>();
    final Set<Long> placed = new HashSet<>();
    private boolean restored;

    TempBuild(World w) {
        this.w = w;
        ALL.add(this);
    }

    static long key(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) (y + 2048) & 0xFFFL);
    }

    static int kx(long k) { int x = (int) (k >> 38); return x >= (1 << 25) ? x - (1 << 26) : x; }

    static int kz(long k) { int z = (int) ((k >> 12) & 0x3FFFFFFL); return z >= (1 << 25) ? z - (1 << 26) : z; }

    static int ky(long k) { return (int) (k & 0xFFFL) - 2048; }

    /** Places the plan over a few ticks. */
    void place(Map<Long, BlockData> plan, Runnable done) {
        final Iterator<Map.Entry<Long, BlockData>> it = new ArrayList<>(plan.entrySet()).iterator();
        new BukkitRunnable() {
            @Override public void run() {
                int n = 0;
                while (it.hasNext() && n < PER_TICK) {
                    Map.Entry<Long, BlockData> e = it.next();
                    put(e.getKey(), e.getValue());
                    n++;
                }
                if (!it.hasNext()) { cancel(); if (done != null) done.run(); }
            }
        }.runTaskTimer(Aegis.I, 0, 1);
    }

    private void put(long k, BlockData d) {
        int x = kx(k), y = ky(k), z = kz(k);
        if (restored || y < w.getMinHeight() || y >= w.getMaxHeight()) return;
        Block b = w.getBlockAt(x, y, z);
        Material cur = b.getType();
        if (cur == Material.BEDROCK || cur.getHardness() < 0) return;
        if (cur.isInteractable() && b.getState() instanceof TileState) return;   // chests, furnaces ... stay untouched
        original.putIfAbsent(k, b.getBlockData());
        b.setBlockData(d, false);
        placed.add(k);
    }

    boolean owns(Block b) {
        return b.getWorld().equals(w) && placed.contains(key(b.getX(), b.getY(), b.getZ()));
    }

    static boolean isProtected(Block b) {
        for (TempBuild t : ALL) if (!t.restored && t.owns(b)) return true;
        return false;
    }

    /** Puts everything back over a few ticks. */
    void restore() {
        if (restored) return;
        restored = true;
        ALL.remove(this);
        List<Long> keys = new ArrayList<>(original.keySet());
        Collections.reverse(keys);
        final Iterator<Long> it = keys.iterator();
        new BukkitRunnable() {
            @Override public void run() {
                int n = 0;
                while (it.hasNext() && n < PER_TICK) {
                    long k = it.next();
                    w.getBlockAt(kx(k), ky(k), kz(k)).setBlockData(original.get(k), false);
                    n++;
                }
                if (!it.hasNext()) { cancel(); placed.clear(); original.clear(); }
            }
        }.runTaskTimer(Aegis.I, 0, 1);
    }

    /** Immediately (server shutdown). */
    void restoreNow() {
        if (restored && original.isEmpty()) return;
        restored = true;
        List<Long> keys = new ArrayList<>(original.keySet());
        Collections.reverse(keys);
        for (long k : keys) w.getBlockAt(kx(k), ky(k), kz(k)).setBlockData(original.get(k), false);
        original.clear();
        placed.clear();
    }

    static void shutdown() {
        for (TempBuild t : new ArrayList<>(ALL)) t.restoreNow();
        ALL.clear();
    }

    /** Hemisphere shell (and a little below ground) around a centre; solid terrain is left alone. */
    static void dome(Map<Long, BlockData> plan, Location c, double r, int thick, Material m) {
        World w = c.getWorld();
        BlockData bd = m.createBlockData();
        int px = c.getBlockX(), py = c.getBlockY(), pz = c.getBlockZ();
        double rin = r, rout = r + thick;
        int ext = (int) Math.ceil(rout);
        for (int dy = -4; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++)
                for (int dz = -ext; dz <= ext; dz++) {
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 < rin * rin || d2 >= rout * rout) continue;
                    int x = px + dx, y = py + dy, z = pz + dz;
                    if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
                    if (w.getBlockAt(x, y, z).getType().isSolid()) continue;
                    plan.put(key(x, y, z), bd);
                }
    }
}
