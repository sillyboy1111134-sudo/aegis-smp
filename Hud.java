package dev.aegis;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Action-bar HUD. In "glyph" mode every ability is ONE resource-pack glyph: the class icon with a small
 * charge bar baked in underneath (9 fill steps, 0..8). The glyph index MUST match tools/generate_resourcepack.py:
 *   char = 0xE000 + classOrdinal * 18 + (ability - 1) * 9 + step
 */
final class Hud {
    static void update(Player p) {
        PlayerData d = Aegis.I.pd(p);
        if (d.rolling) { p.sendActionBar(Component.text("Rolling...", NamedTextColor.GOLD)); return; }
        ClassType t = d.type;
        if (t == null) return;
        boolean glyph = Aegis.I.getConfig().getString("hud.mode", "glyph").equalsIgnoreCase("glyph");

        Component line = Component.empty();
        int abilities = t.a2 == null ? 1 : 2;
        for (int n = 1; n <= abilities; n++) {
            double f = d.fraction(n);
            int step = f >= 1.0 ? 8 : Math.max(0, Math.min(7, (int) Math.floor(f * 8)));
            if (glyph) {
                char c = (char) (0xE000 + t.ordinal() * 18 + (n - 1) * 9 + step);
                line = line.append(Component.text(String.valueOf(c)).font(Key.key("aegis", "hud")).color(NamedTextColor.WHITE));
            } else {
                line = line.append(Component.text(t.abilityName(n) + " " + bar(f), f >= 1.0 ? NamedTextColor.GREEN : NamedTextColor.GOLD));
            }
            line = line.append(Component.text("  "));
        }
        line = line.append(Component.text("\u26A1 " + d.energy + "/" + Aegis.I.maxEnergy(), NamedTextColor.AQUA));
        p.sendActionBar(line);
    }

    private static String bar(double f) {
        int filled = (int) Math.round(f * 8);
        return "[" + "|".repeat(filled) + ".".repeat(8 - filled) + "]";
    }
}
