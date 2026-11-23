package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.Random;

/** The show around every ultimate: far bigger than a normal ability. */
final class UltFx {
    private static final Random RND = new Random();

    static int color(String id) {
        return switch (id) {
            case "bloodbath" -> 0xB00020;
            case "indomitable" -> 0x4FC3F7;
            case "tides" -> 0x29B6F6;
            case "hell" -> 0xFF5722;
            case "workshop" -> 0xFF6D00;
            case "skyruler" -> 0x81D4FA;
            case "guardian", "domain" -> 0x00ACC1;
            case "goldengod", "idolgamble", "flowtime" -> 0xFFC107;
            case "shadows" -> 0x2A0A3A;
            case "avoidance" -> 0xB388FF;
            case "supremesingularity" -> 0x5B1FD6;
            case "areswrath", "defiantrage" -> 0xC62828;
            case "desolation" -> 0x1B5E20;
            case "tremor" -> 0x8D6E63;
            case "worldtree" -> 0x66BB6A;
            case "chains" -> 0x9E9E9E;
            case "swordgod" -> 0xCFD8DC;
            default -> 0xFFFFFF;
        };
    }

    static Particle theme(String id) {
        return switch (id) {
            case "bloodbath" -> Particle.CRIMSON_SPORE;
            case "indomitable" -> Particle.SNOWFLAKE;
            case "tides" -> Particle.SPLASH;
            case "hell", "defiantrage" -> Particle.FLAME;
            case "workshop" -> Particle.LAVA;
            case "skyruler" -> Particle.CLOUD;
            case "guardian", "domain" -> Particle.SCULK_SOUL;
            case "goldengod" -> Particle.WAX_ON;
            case "idolgamble" -> Particle.TOTEM_OF_UNDYING;
            case "shadows" -> Particle.SQUID_INK;
            case "avoidance" -> Particle.WITCH;
            case "supremesingularity" -> Particle.REVERSE_PORTAL;
            case "areswrath", "chains" -> Particle.CRIT;
            case "desolation" -> Particle.SOUL;
            case "tremor" -> Particle.POOF;
            case "worldtree" -> Particle.HAPPY_VILLAGER;
            case "swordgod" -> Particle.ENCHANTED_HIT;
            default -> Particle.END_ROD;
        };
    }

    private static boolean on() { return Aegis.I.getConfig().getBoolean("particles.enabled", true); }

    /** Announcement, lightning, a pillar of light, shockwaves, a huge burst and a long swirling aura. */
    static void cast(Player p, Ults.Def def) {
        final String id = def.id();
        final int rgb = color(id);
        final Particle th = theme(id);
        final Location base = p.getLocation().clone();
        final World w = base.getWorld();
        Title title = Title.title(Component.text(def.name().toUpperCase(), TextColor.color(rgb), TextDecoration.BOLD),
                Component.text(p.getName() + " unleashes an ultimate!", NamedTextColor.GOLD),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1)));
        for (Player o : w.getPlayers()) {
            if (o.getLocation().distanceSquared(base) > 90 * 90) continue;
            o.showTitle(title);
            o.playSound(o.getLocation(), "entity.ender_dragon.growl", 0.8f, 1.3f);
            o.playSound(o.getLocation(), "entity.lightning_bolt.thunder", 0.7f, 0.8f);
        }
        if (!on()) return;
        for (int i = 0; i < 4; i++) w.strikeLightningEffect(base.clone().add((RND.nextDouble() - .5) * 10, 0, (RND.nextDouble() - .5) * 10));   // visual only
        w.playSound(base, "entity.firework_rocket.blast", 2f, 0.6f);
        w.playSound(base, "block.beacon.activate", 2f, 0.7f);
        final Location mid = base.clone().add(0, 1, 0);
        Fx.burst(mid, th, 160, .9);
        Fx.burst(mid, Particle.END_ROD, 90, .7);
        Fx.burst(mid, Particle.FIREWORK, 70, .6);
        // pillar of light rising 40 blocks
        new BukkitRunnable() {
            int h = 0;
            @Override public void run() {
                for (int k = 0; k < 4; k++, h++) {
                    if (h > 40) { cancel(); return; }
                    Location l = base.clone().add(0, h, 0);
                    Fx.dust(l, 6, .35, rgb, 2.2f);
                    Fx.part(l, Particle.END_ROD, 2, .2, .2, .2, 0.01);
                }
            }
        }.runTaskTimer(Aegis.I, 0, 1);
        // four shockwaves
        for (int i = 0; i < 4; i++) {
            final int delay = i * 5;
            org.bukkit.Bukkit.getScheduler().runTaskLater(Aegis.I, () ->
                    Fx.expand(base.clone().add(0, 0.2, 0), .5, 16, 14, 72, l -> { Fx.dust(l, 1, 0, rgb, 2f); Fx.part(l, th, 1, 0, 0, 0, 0); }), delay);
        }
        // long aura around the caster
        final int life = (int) (Math.min(30, Math.max(4, def.active())) * 20);
        Fx.follow(p, life, (l, k) -> {
            Location b = l.clone();
            Fx.spiral(b, k, 1.6, 3.2, 3, x -> { Fx.dust(x, 1, 0, rgb, 1.8f); Fx.part(x, th, 1, 0, 0, 0, 0); });
            Fx.spiral(b, k + 25, 1.0, 3.2, 2, x -> Fx.dust(x, 1, 0, rgb, 1.3f));
            double a = k * 0.35;
            for (int i = 0; i < 8; i++) {   // orbiting sparks
                double ang = a + i * Math.PI / 4;
                Fx.part(b.clone().add(Math.cos(ang) * 1.5, 1.0 + Math.sin(k * 0.2 + i) * .6, Math.sin(ang) * 1.5), Particle.END_ROD, 1, 0, 0, 0, 0);
            }
            for (int i = 0; i < 6; i++) {   // halo
                double ang = -a + i * Math.PI / 3;
                Fx.dust(b.clone().add(Math.cos(ang) * 0.45, 2.45, Math.sin(ang) * 0.45), 1, 0, rgb, 1.2f);
            }
            if (k % 4 == 0) Fx.ring(b.clone().add(0, 0.1, 0), 2.6, 28, x -> Fx.dust(x, 1, 0, rgb, 1.3f));
        });
    }

    /** Shimmering surface of a domain (a hemisphere of the given radius around its centre). */
    static void shimmer(UltImpl.Dom d) {
        if (!on()) return;
        int rgb = color(d.id);
        Particle th = theme(d.id);
        for (int i = 0; i < 45; i++) {
            double theta = RND.nextDouble() * Math.PI * 2, phi = RND.nextDouble() * Math.PI / 2;
            Location l = d.c.clone().add(d.r * Math.cos(phi) * Math.cos(theta), d.r * Math.sin(phi), d.r * Math.cos(phi) * Math.sin(theta));
            Fx.dust(l, 1, 0.05, rgb, 1.7f);
            if (i % 3 == 0) Fx.part(l, th, 1, 0, 0, 0, 0.01);
        }
    }

    /** Smaller flash for a life being used up and similar moments. */
    static void pulse(Player p, String id) {
        if (!on()) return;
        final int rgb = color(id);
        final Particle th = theme(id);
        Location base = p.getLocation().clone();
        Fx.burst(base.clone().add(0, 1, 0), th, 100, .8);
        Fx.expand(base.clone().add(0, 0.2, 0), .5, 10, 12, 60, l -> Fx.dust(l, 1, 0, rgb, 1.8f));
        p.getWorld().strikeLightningEffect(base);
    }
}
