package dev.aegis;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Minimal Sponge schematic (.schem v2 / v3) reader, so a WorldEdit schematic can be pasted without WorldEdit. */
final class Schematic {
    final int w, h, l;
    final BlockData[] palette;
    final int[] ids;

    private Schematic(int w, int h, int l, BlockData[] palette, int[] ids) {
        this.w = w;
        this.h = h;
        this.l = l;
        this.palette = palette;
        this.ids = ids;
    }

    @SuppressWarnings("unchecked")
    static Schematic load(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(f))))) {
            in.readByte();
            readString(in);
            Map<String, Object> root = readCompound(in);
            Map<String, Object> s = root.get("Schematic") instanceof Map<?, ?> m ? (Map<String, Object>) m : root;
            int w = ((Number) s.get("Width")).intValue(), h = ((Number) s.get("Height")).intValue(), l = ((Number) s.get("Length")).intValue();
            Map<String, Object> pal;
            byte[] data;
            if (s.get("Blocks") instanceof Map<?, ?> bm) {
                pal = (Map<String, Object>) bm.get("Palette");
                data = (byte[]) bm.get("Data");
            } else {
                pal = (Map<String, Object>) s.get("Palette");
                data = (byte[]) s.get("BlockData");
            }
            int max = 0;
            for (Object o : pal.values()) max = Math.max(max, ((Number) o).intValue());
            BlockData[] palette = new BlockData[max + 1];
            for (Map.Entry<String, Object> e : pal.entrySet()) {
                BlockData bd;
                try { bd = Bukkit.createBlockData(e.getKey()); } catch (IllegalArgumentException ex) { bd = Material.AIR.createBlockData(); }
                palette[((Number) e.getValue()).intValue()] = bd;
            }
            int[] ids = new int[w * h * l];
            int idx = 0, i = 0;
            while (i < data.length && idx < ids.length) {
                int val = 0, shift = 0;
                byte b;
                do { b = data[i++]; val |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0 && i < data.length);
                ids[idx++] = val;
            }
            return new Schematic(w, h, l, palette, ids);
        }
    }

    /** Adds the schematic to a plan, centred on the location horizontally with its bottom at the location's height. */
    void paste(Map<Long, BlockData> plan, Location c) {
        int ox = c.getBlockX() - w / 2, oy = c.getBlockY(), oz = c.getBlockZ() - l / 2;
        for (int y = 0; y < h; y++)
            for (int z = 0; z < l; z++)
                for (int x = 0; x < w; x++) {
                    int id = ids[(y * l + z) * w + x];
                    BlockData bd = id >= 0 && id < palette.length ? palette[id] : null;
                    if (bd == null || bd.getMaterial().isAir()) continue;
                    plan.put(TempBuild.key(ox + x, oy + y, oz + z), bd);
                }
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readUnsignedShort()];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> m = new HashMap<>();
        while (true) {
            int type = in.readByte();
            if (type == 0) return m;
            String name = readString(in);
            m.put(name, readTag(in, type));
        }
    }

    private static Object readTag(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return readString(in);
            case 9: {
                int t = in.readByte(), n = in.readInt();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < n; i++) list.add(readTag(in, t));
                return list;
            }
            case 10: return readCompound(in);
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }
}
