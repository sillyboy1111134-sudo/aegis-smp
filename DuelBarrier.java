package dev.aegis;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Forced Duel arena walls: real entities, so everybody can see them (the world border only shows for the two duelists). */
final class DuelBarrier {
    private static final Map<UUID, List<BlockDisplay>> walls = new HashMap<>();

    static void show(Abilities.Duel d) {
        hide(d.caster());
        Location c = d.center();
        World w = c.getWorld();
        double r = d.r(), y0 = c.getY() - 4, h = 16;
        List<BlockDisplay> list = new ArrayList<>();
        list.add(wall(w, c.getX() - r, y0, c.getZ() - r - 0.1, 2 * r, h, 0.2));
        list.add(wall(w, c.getX() - r, y0, c.getZ() + r - 0.1, 2 * r, h, 0.2));
        list.add(wall(w, c.getX() - r - 0.1, y0, c.getZ() - r, 0.2, h, 2 * r));
        list.add(wall(w, c.getX() + r - 0.1, y0, c.getZ() - r, 0.2, h, 2 * r));
        walls.put(d.caster(), list);
    }

    private static BlockDisplay wall(World w, double x, double y, double z, double sx, double sy, double sz) {
        return w.spawn(new Location(w, x, y, z), BlockDisplay.class, b -> {
            b.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
            b.setTransformation(new Transformation(new Vector3f(0f, 0f, 0f), new AxisAngle4f(), new Vector3f((float) sx, (float) sy, (float) sz), new AxisAngle4f()));
            b.setViewRange(4f);
            b.setPersistent(false);
        });
    }

    static void hide(UUID caster) {
        List<BlockDisplay> l = walls.remove(caster);
        if (l != null) for (BlockDisplay b : l) b.remove();
    }

    static void shutdown() {
        for (List<BlockDisplay> l : new ArrayList<>(walls.values())) for (BlockDisplay b : l) b.remove();
        walls.clear();
    }
}
