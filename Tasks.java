package dev.aegis;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

final class Tasks {
    private static int counter = 0;

    static void start() {
        Aegis pl = Aegis.I;

        // HUD
        new BukkitRunnable() {
            @Override public void run() { for (Player p : Bukkit.getOnlinePlayers()) Hud.update(p); }
        }.runTaskTimer(pl, 10, Math.max(1, pl.getConfig().getInt("hud.update-ticks", 4)));

        // once per second: bleed, conditional / periodic passives
        new BukkitRunnable() {
            @Override public void run() {
                counter++;
                Status.tickBleed();
                Pets.tick();
                for (Player p : Bukkit.getOnlinePlayers()) Passives.tick(p, counter);
            }
        }.runTaskTimer(pl, 20, 20);

        // netherite <-> diamond rule, trident upgrades
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) if (!pl.pd(p).rolling) Gear.scan(p);
            }
        }.runTaskTimer(pl, 20, Math.max(1, pl.getConfig().getInt("gear.scan-ticks", 10)));

        // Agni: swim through lava like water
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.isInLava() || Passives.first(p, "lava-swim") == null) continue;
                    Vector v = p.getVelocity();
                    if (p.isSprinting()) v = p.getLocation().getDirection().multiply(0.4);
                    else if (!p.isSneaking()) v.setY(Math.max(v.getY(), -0.02));
                    p.setVelocity(v);
                }
            }
        }.runTaskTimer(pl, 1, 1);

        // class aura
        new BukkitRunnable() {
            @Override public void run() { for (Player p : Bukkit.getOnlinePlayers()) Fx.ambient(p); }
        }.runTaskTimer(pl, 10, 4);

        // Forced Duel walls
        new BukkitRunnable() {
            @Override public void run() {
                for (Abilities.Duel d : new java.util.ArrayList<>(Abilities.duels.values()))
                    for (java.util.UUID u : new java.util.UUID[]{d.caster(), d.other()}) {
                        Player q = Bukkit.getPlayer(u);
                        if (q != null) Fx.arenaWalls(d, q);
                    }
            }
        }.runTaskTimer(pl, 10, 10);

        // autosave
        new BukkitRunnable() {
            @Override public void run() { pl.saveData(); }
        }.runTaskTimer(pl, 6000, 6000);
    }
}
