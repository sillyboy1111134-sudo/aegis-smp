package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Slot-machine class roll. The real result is chosen up front but NOT shown (and not applied)
 * until the reel stops. Used on first join and by the Class Reroller.
 */
final class Roll {
    // ticks between frames - starts fast, slows down like a slot machine
    private static final int[] DELAYS = {2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 6, 7, 8, 10, 12, 14, 18};

    static void start(Player p, ClassType result) {
        PlayerData d = Aegis.I.pd(p);
        if (d.rolling) return;
        d.rolling = true;
        d.pending = result;
        p.playSound(p.getLocation(), "block.note_block.bell", 1f, 1f);
        step(p.getUniqueId(), 0);
    }

    private static void step(UUID id, int i) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;                       // quit handler finishes the roll
        PlayerData d = Aegis.I.pd(p);
        if (!d.rolling) return;

        if (i >= DELAYS.length) { finish(p); return; }

        ClassType[] all = ClassType.values();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ClassType mid = all[r.nextInt(all.length)], left = all[r.nextInt(all.length)], right = all[r.nextInt(all.length)];
        Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ZERO);
        p.showTitle(Title.title(
                Component.text(mid.display, NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("\u2039 " + left.display + "  \u2022  " + right.display + " \u203A", NamedTextColor.DARK_GRAY),
                times));
        p.playSound(p.getLocation(), "block.note_block.hat", 0.7f, 0.6f + 0.04f * i);
        Fx.part(p.getLocation().add(0, 2.3, 0), org.bukkit.Particle.END_ROD, 4, .3, .2, .3, .02);

        Bukkit.getScheduler().runTaskLater(Aegis.I, () -> step(id, i + 1), DELAYS[i]);
    }

    /** Applies the rolled class and reveals it. Safe to call early (e.g. on quit). */
    static void finish(Player p) {
        PlayerData d = Aegis.I.pd(p);
        if (!d.rolling) return;
        d.rolling = false;
        ClassType t = d.pending;
        d.pending = null;
        if (t == null) t = Aegis.I.randomClass(d.type);

        Abilities.cleanup(p);
        Status.clear(p);
        d.cd.clear();
        d.type = t;
        Passives.refresh(p);

        if (p.isOnline()) {
            p.showTitle(Title.title(
                    Component.text(t.display, NamedTextColor.GREEN, TextDecoration.BOLD),
                    Component.text(t.a1 + (t.a2 == null ? "" : "  \u2022  " + t.a2), NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), "entity.player.levelup", 1f, 1f);
            p.playSound(p.getLocation(), "ui.toast.challenge_complete", 1f, 1f);
            Fx.reveal(p);
        }
    }
}
