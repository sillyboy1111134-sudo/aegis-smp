package dev.aegis;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Candle;
import org.bukkit.block.data.type.SculkShrieker;
import org.bukkit.block.data.type.Stairs;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Procedural Ancient City, generated the way a real one is laid out:
 *  1. the city centre: a raised dais with the (inactive) reinforced-deepslate portal, on a deepslate-tile plaza
 *  2. main roads running out from the plaza along the portal's axes, plus a ring road
 *  3. soul-lantern lamp posts along the roads
 *  4. buildings on a grid beside the roads, doors facing the centre; intact near the middle,
 *     increasingly ruined (collapsed roofs, broken towers, loose walls, rubble) toward the edge
 *  5. sculk grows over everything that is left, denser near the centre, with veins, sensors, shriekers
 *     (decorative only) and catalysts clustered around the portal
 * Everything follows the local terrain. The result is only a block plan; Domain applies and restores it.
 */
final class AncientCity {
    record Pos(int x, int y, int z) { }

    record Opts(double ruin, double density, Set<String> protectedMats, int innerW, int innerH,
                double vein, double sensor, double calibrated, double shrieker, double catalyst) { }

    static final int NONE = Integer.MIN_VALUE;

    final Map<Pos, BlockData> blocks = new LinkedHashMap<>();
    private final Set<Long> claimed = new HashSet<>();
    private final World w;
    private final int px, pz, py, R, size, fx, fz, rx, rz;
    private final BlockFace face;
    private final Random rnd;
    private final Opts o;
    private final int[] gy;

    private AncientCity(World w, int px, int pz, int py, int R, BlockFace face, Random rnd, Opts o) {
        this.w = w; this.px = px; this.pz = pz; this.py = py; this.R = R; this.face = face; this.rnd = rnd; this.o = o;
        this.size = 2 * R + 1;
        this.fx = face.getModX(); this.fz = face.getModZ();
        this.rx = -fz; this.rz = fx;                // the portal's "right"
        this.gy = new int[size * size];
    }

    static AncientCity plan(World w, int px, int pz, int py, int R, BlockFace face, Random rnd, Opts o) {
        AncientCity c = new AncientCity(w, px, pz, py, R, face, rnd, o);
        c.scanGround();
        c.build();
        return c;
    }

    // ================================================================ terrain
    private static boolean liquid(Material m) { return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN; }

    private void scanGround() {
        for (int ix = 0; ix < size; ix++) {
            for (int iz = 0; iz < size; iz++) {
                int x = px - R + ix, z = pz - R + iz, found = NONE;
                if ((double) (ix - R) * (ix - R) + (double) (iz - R) * (iz - R) <= (double) R * R) {
                    for (int y = py + 10; y >= py - 16; y--) {
                        Material m = w.getBlockAt(x, y, z).getType();
                        if (m.isAir() || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m) || (m.isPassable() && !liquid(m))) continue;
                        found = liquid(m) ? NONE : y;
                        break;
                    }
                }
                gy[ix * size + iz] = found;
            }
        }
    }

    private int ground(int x, int z) {
        int ix = x - px + R, iz = z - pz + R;
        if (ix < 0 || iz < 0 || ix >= size || iz >= size) return NONE;
        return gy[ix * size + iz];
    }

    // ================================================================ helpers
    private int wx(int u, int v) { return px + u * rx + v * fx; }
    private int wz(int u, int v) { return pz + u * rz + v * fz; }
    private long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private void claim(int x, int z) { claimed.add(key(x, z)); }
    private boolean isClaimed(int x, int z) { return claimed.contains(key(x, z)); }

    private void put(int x, int y, int z, Material m) { blocks.put(new Pos(x, y, z), m.createBlockData()); }
    private void put(int x, int y, int z, BlockData d) { blocks.put(new Pos(x, y, z), d); }

    private void clear(int x, int y, int z) {
        Material m = w.getBlockAt(x, y, z).getType();
        if (!m.isAir() && !liquid(m)) put(x, y, z, Material.AIR);
    }

    private Material brick() { return rnd.nextDouble() < 0.22 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS; }
    private Material tile() { return rnd.nextDouble() < 0.20 ? Material.CRACKED_DEEPSLATE_TILES : Material.DEEPSLATE_TILES; }
    private boolean chance(double p) { return rnd.nextDouble() < p; }

    private BlockData candle() {
        Material[] cs = {Material.CANDLE, Material.GRAY_CANDLE, Material.LIGHT_GRAY_CANDLE, Material.CYAN_CANDLE};
        BlockData bd = cs[rnd.nextInt(cs.length)].createBlockData();
        if (bd instanceof Candle c) { c.setCandles(1 + rnd.nextInt(3)); c.setLit(true); }
        return bd;
    }

    private BlockData veinData() {
        BlockData bd = Material.SCULK_VEIN.createBlockData();
        if (bd instanceof MultipleFacing mf) mf.setFace(BlockFace.DOWN, true);
        return bd;
    }

    /** Level a column to `level`: fill hollows below, clear anything above (trees, hills). */
    private void flatten(int x, int z, int level, int clearUp) {
        int g = ground(x, z);
        if (g == NONE) g = level - 1;
        for (int y = Math.max(g + 1, level - 8); y < level; y++) put(x, y, z, Material.DEEPSLATE);
        for (int y = level + 1; y <= level + clearUp; y++) clear(x, y, z);
    }

    // ================================================================ build order
    private void build() {
        int r0 = Math.max(9, (int) (R * 0.30));    // plaza
        int r1 = (int) (R * 0.50);                 // ring road
        int r2 = (int) (R * 0.78);                 // where the city turns to ruins
        plaza(r0);
        portal();
        roads(r0, r1);
        buildings(r0, r1, r2);
        lamps(r0, r1);
        sculk();
    }

    // ---------------------------------------------------------------- 1. city centre
    private void plaza(int r0) {
        for (int dx = -r0; dx <= r0; dx++) {
            for (int dz = -r0; dz <= r0; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > r0) continue;
                int x = px + dx, z = pz + dz;
                claim(x, z);
                flatten(x, z, py, 14);
                Material m;
                if (d > r0 - 1.5) m = Material.POLISHED_DEEPSLATE;
                else if (dx == 0 || dz == 0) m = Material.POLISHED_DEEPSLATE;
                else if (Math.abs(d - r0 * 0.6) < 0.7) m = Material.CHISELED_DEEPSLATE;
                else m = tile();
                put(x, py, z, m);
            }
        }
    }

    private void portal() {
        // two-tier dais
        for (int u = -5; u <= 5; u++) for (int v = -3; v <= 2; v++) {
            boolean edge = Math.abs(u) == 5 || v == -3 || v == 2;
            put(wx(u, v), py + 1, wz(u, v), edge ? Material.POLISHED_DEEPSLATE : brick());
        }
        for (int u = -4; u <= 4; u++) for (int v = -2; v <= 1; v++)
            put(wx(u, v), py + 2, wz(u, v), ((u + v) & 1) == 0 ? tile() : Material.POLISHED_DEEPSLATE);

        // steps up toward the portal (stairs ascend toward +v, i.e. toward the portal)
        for (int u = -3; u <= 3; u++) {
            put(wx(u, -4), py + 1, wz(u, -4), stair(face));
            put(wx(u, -3), py + 2, wz(u, -3), stair(face));
        }

        // the reinforced-deepslate frame (inactive portal)
        int outW = o.innerW() + 2, outH = o.innerH() + 2, half = outW / 2, baseY = py + 3;
        for (int u = -half; u <= half; u++) for (int h = 0; h < outH; h++) {
            boolean frame = Math.abs(u) == half || h == 0 || h == outH - 1;
            if (frame) put(wx(u, 0), baseY + h, wz(u, 0), Material.REINFORCED_DEEPSLATE);
        }

        // corner posts with soul lanterns
        for (int u : new int[]{-5, 5}) for (int v : new int[]{-3, 2}) {
            for (int y = py + 2; y <= py + 4; y++) put(wx(u, v), y, wz(u, v), y == py + 2 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_BRICKS);
            put(wx(u, v), py + 5, wz(u, v), Material.SOUL_LANTERN);
        }
        // catalysts hugging the dais (a real city centre is where sculk spreads from)
        for (int u : new int[]{-4, 4}) put(wx(u, 3), py + 1, wz(u, 3), Material.SCULK_CATALYST);
        for (int u : new int[]{-5, 5}) put(wx(u, -4), py + 1, wz(u, -4), Material.SCULK_CATALYST);
    }

    private BlockData stair(BlockFace ascend) {
        BlockData bd = Material.DEEPSLATE_BRICK_STAIRS.createBlockData();
        if (bd instanceof Stairs s) s.setFacing(ascend);
        return bd;
    }

    // ---------------------------------------------------------------- 2. roads
    private void roads(int r0, int r1) {
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > R || d < r0 - 0.5) continue;
                boolean axis = Math.abs(dx) <= 1 || Math.abs(dz) <= 1;
                boolean ring = Math.abs(d - r1) < 1.4;
                if (!axis && !ring) continue;
                int x = px + dx, z = pz + dz, g = ground(x, z);
                if (g == NONE || isClaimed(x, z)) continue;
                Material m;
                if (axis && (dx == 0 || dz == 0)) m = chance(0.5) ? Material.POLISHED_DEEPSLATE : tile();
                else m = chance(0.2) ? Material.POLISHED_DEEPSLATE : tile();
                put(x, g, z, m);
                for (int y = g + 1; y <= g + 3; y++) clear(x, y, z);
                claim(x, z);
            }
        }
    }

    // ---------------------------------------------------------------- 3. lamp posts
    private void lamps(int r0, int r1) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            for (int s = r0 + 2; s <= R - 3; s += 7) {
                for (int side : new int[]{-2, 2}) {
                    int x = px + d[0] * s + (d[0] == 0 ? side : 0), z = pz + d[1] * s + (d[1] == 0 ? side : 0);
                    post(x, z);
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + Math.PI / 8;
            post(px + (int) Math.round(Math.cos(a) * (r1 + 2)), pz + (int) Math.round(Math.sin(a) * (r1 + 2)));
        }
    }

    private void post(int x, int z) {
        int g = ground(x, z);
        if (g == NONE || isClaimed(x, z)) return;
        for (int y = g + 1; y <= g + 3; y++) put(x, y, z, Material.DEEPSLATE_BRICK_WALL);
        put(x, g + 4, z, Material.SOUL_LANTERN);
        claim(x, z);
    }

    // ---------------------------------------------------------------- 4. buildings
    private void buildings(int r0, int r1, int r2) {
        int n = R / 12 + 2;
        for (int i = -n; i < n; i++) {
            for (int j = -n; j < n; j++) {
                int dx = 6 + 12 * i, dz = 6 + 12 * j;          // cell centres stay clear of the axis roads
                double d = Math.hypot(dx, dz);
                if (d < r0 + 5 || d > R - 6 || Math.abs(d - r1) < 3) continue;
                int cx = px + dx, cz = pz + dz;
                if (ground(cx, cz) == NONE) continue;

                double decay = Math.min(0.85, (d < r1 ? 0.04 : d < r2 ? 0.22 : 0.5) * o.ruin());
                double roll = rnd.nextDouble();
                double dens = o.density();
                if (d < r1) {                                    // intact inner city: houses and halls
                    if (roll < 0.9 * dens) box(cx, cz, 5 + rnd.nextInt(3), 5 + rnd.nextInt(3), 4, decay, true, 0, 0.12);
                } else if (d < r2) {                             // middle: mixed, aging
                    if (roll < 0.40 * dens) box(cx, cz, 5 + rnd.nextInt(3), 5 + rnd.nextInt(3), 4, decay, true, 0, 0.2);
                    else if (roll < 0.62 * dens) box(cx, cz, 5, 5, 8 + rnd.nextInt(4), decay, false, 0.55, 0);
                    else if (roll < 0.76 * dens) wall(cx, cz, decay);
                    else if (roll < 0.85 * dens) rubble(cx, cz);
                } else {                                         // outskirts: ruins
                    if (roll < 0.22 * dens) box(cx, cz, 5 + rnd.nextInt(3), 5 + rnd.nextInt(3), 4, decay, true, 0, 0.1);
                    else if (roll < 0.50 * dens) box(cx, cz, 5, 5, 8 + rnd.nextInt(5), decay, false, 0.6, 0);
                    else if (roll < 0.72 * dens) wall(cx, cz, decay);
                    else if (roll < 0.90 * dens) rubble(cx, cz);
                }
            }
        }
    }

    /** House (roof=true) or tower (roof=false, topBreak = how much the upper part has crumbled). */
    private void box(int cx, int cz, int bw, int bd, int h, double decay, boolean roof, double topBreak, double windows) {
        int x0 = cx - bw / 2, x1 = x0 + bw - 1, z0 = cz - bd / 2, z1 = z0 + bd - 1;
        int gb = ground(cx, cz);
        if (gb == NONE) return;
        for (int[] c : new int[][]{{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}}) {
            int g = ground(c[0], c[1]);
            if (g == NONE || Math.abs(g - gb) > 4) return;       // too steep / over water: skip this plot
        }
        int ddx = px - cx, ddz = pz - cz;
        boolean doorX = Math.abs(ddx) >= Math.abs(ddz);          // door faces the city centre
        int doorSign = doorX ? Integer.signum(ddx) : Integer.signum(ddz);
        int doorX0 = (x0 + x1) / 2, doorZ0 = (z0 + z1) / 2;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                flatten(x, z, gb, h + 3);
                claim(x, z);
                put(x, gb, z, ((x + z) & 1) == 0 ? Material.POLISHED_DEEPSLATE : tile());

                boolean wallX = x == x0 || x == x1, wallZ = z == z0 || z == z1;
                if (wallX || wallZ) {
                    boolean corner = wallX && wallZ;
                    boolean door = (doorX && x == (doorSign > 0 ? x1 : x0) && z == doorZ0)
                            || (!doorX && z == (doorSign > 0 ? z1 : z0) && x == doorX0);
                    for (int y = 1; y <= h; y++) {
                        if (door && y <= 2) continue;
                        double frac = (double) y / h;
                        double skip = decay * (0.35 + 0.65 * frac) + topBreak * frac * frac;
                        if (chance(skip)) continue;
                        if (!corner && y == 2 && chance(windows)) continue;          // window gap
                        put(x, gb + y, z, corner ? Material.POLISHED_DEEPSLATE : brick());
                    }
                }
                if (roof && !chance(decay * 1.3)) put(x, gb + h + 1, z, Material.DEEPSLATE_TILE_SLAB);
            }
        }
        if (roof && decay < 0.3 && chance(0.7) && bw > 3 && bd > 3) {           // lived-in interior
            int ix = x0 + 1 + rnd.nextInt(bw - 2), iz = z0 + 1 + rnd.nextInt(bd - 2);
            put(ix, gb + 1, iz, Material.DARK_OAK_PLANKS);
            put(ix, gb + 2, iz, candle());
            put(x0 + 1, gb + 1, z0 + 1, Material.SOUL_LANTERN);
        }
        if (!roof) {                                                              // rubble ring around a tower
            for (int k = 0; k < 6; k++) {
                int rx2 = x0 - 1 + rnd.nextInt(bw + 2), rz2 = z0 - 1 + rnd.nextInt(bd + 2);
                int g = ground(rx2, rz2);
                if (g != NONE && !isClaimedInside(rx2, rz2, x0, x1, z0, z1)) put(rx2, g + 1, rz2, chance(0.5) ? Material.COBBLED_DEEPSLATE : Material.DEEPSLATE);
            }
        }
    }

    private boolean isClaimedInside(int x, int z, int x0, int x1, int z0, int z1) { return x >= x0 && x <= x1 && z >= z0 && z <= z1; }

    private void wall(int cx, int cz, double decay) {
        boolean alongX = rnd.nextBoolean();
        int len = 7 + rnd.nextInt(5), hh = 3 + rnd.nextInt(2);
        for (int i = 0; i < len; i++) {
            int x = alongX ? cx - len / 2 + i : cx, z = alongX ? cz : cz - len / 2 + i;
            int g = ground(x, z);
            if (g == NONE || isClaimed(x, z)) continue;
            claim(x, z);
            for (int y = g + 1; y <= g + hh + 1; y++) clear(x, y, z);
            for (int y = 1; y <= hh; y++) {
                if (chance(decay * 0.8 + 0.35 * y / hh)) continue;
                put(x, g + y, z, brick());
            }
            if ((i == 0 || i == len - 1) && !chance(decay)) put(x, g + hh + 1, z, Material.SOUL_LANTERN);
        }
    }

    private void rubble(int cx, int cz) {
        for (int k = 0; k < 9; k++) {
            int x = cx + rnd.nextInt(5) - 2, z = cz + rnd.nextInt(5) - 2, g = ground(x, z);
            if (g == NONE || isClaimed(x, z)) continue;
            int hh = 1 + rnd.nextInt(3);
            for (int y = 1; y <= hh; y++) put(x, g + y, z, chance(0.6) ? Material.COBBLED_DEEPSLATE : Material.DEEPSLATE);
        }
    }

    // ---------------------------------------------------------------- 5. sculk takes over the rest
    private void sculk() {
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > R) continue;
                int x = px + dx, z = pz + dz, g = ground(x, z);
                if (g == NONE || isClaimed(x, z)) continue;
                Block b = w.getBlockAt(x, g, z);
                Material m = b.getType();
                if (m.getHardness() < 0 || o.protectedMats().contains(m.name()) || m.name().startsWith("SCULK")) continue;
                if (b.getState(false) instanceof TileState) continue;

                double pSculk = 0.93 - 0.4 * d / R;                  // denser near the centre
                put(x, g, z, chance(pSculk) ? Material.SCULK : (chance(0.5) ? Material.DEEPSLATE : Material.COBBLED_DEEPSLATE));

                Material above = w.getBlockAt(x, g + 1, z).getType();
                if (!above.isAir() && above.isPassable() && !liquid(above)) { put(x, g + 1, z, Material.AIR); above = Material.AIR; }
                if (!above.isAir()) continue;

                double near = 1 + 3 * (1 - d / R);                    // catalysts cluster toward the centre
                double t1 = o.vein();
                double t2 = t1 + o.sensor();
                double t3 = t2 + o.calibrated();
                double t4 = t3 + o.shrieker();
                double t5 = t4 + o.catalyst() * near;
                double r = rnd.nextDouble();
                if (r < t1) put(x, g + 1, z, veinData());
                else if (r < t2) put(x, g + 1, z, Material.SCULK_SENSOR);
                else if (r < t3) put(x, g + 1, z, Material.CALIBRATED_SCULK_SENSOR);
                else if (r < t4) {
                    BlockData bd = Material.SCULK_SHRIEKER.createBlockData();
                    if (bd instanceof SculkShrieker ss) ss.setCanSummon(false);   // decoration only - never summons a Warden
                    put(x, g + 1, z, bd);
                } else if (r < t5) put(x, g + 1, z, Material.SCULK_CATALYST);
            }
        }
    }
}
