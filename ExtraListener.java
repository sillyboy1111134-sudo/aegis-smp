package dev.aegis;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Event hooks for Rogue and Yggdrasil. */
final class ExtraListener implements Listener {

    // ---------------------------------------------------------------- Rogue: stealth breaks when you are hit; decoys
    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof ArmorStand st && NewClasses.isDecoy(st)) {
            e.setCancelled(true);
            NewClasses.popDecoy(st);
            return;
        }
        if (!e.isCancelled() && e.getEntity() instanceof Player p && Status.on(NewClasses.stealth, p)) NewClasses.endStealth(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (NewClasses.isDecoy(e.getRightClicked())) e.setCancelled(true);   // nobody can take the decoy's gear
    }

    // ---------------------------------------------------------------- Yggdrasil: health potions act like Instant Health III
    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (it.getType() != Material.POTION) return;
        Player p = e.getPlayer();
        ConfigurationSection n = Passives.first(p, "instant-heal-level");
        if (n == null) return;
        double vanilla = NewClasses.vanillaHeal(it);
        double extra = NewClasses.potHeal(n) - vanilla;
        if (vanilla <= 0 || extra <= 0) return;
        Bukkit.getScheduler().runTask(Aegis.I, () -> { if (p.isOnline() && !p.isDead()) NewClasses.heal(p, extra); });
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        ThrownPotion tp = e.getPotion();
        int amp = -1;
        for (PotionEffect ef : tp.getEffects())
            if (ef.getType().equals(PotionEffectType.INSTANT_HEALTH)) amp = Math.max(amp, ef.getAmplifier());
        if (amp < 0) return;
        double vanilla = 4 << Math.min(8, amp);
        for (LivingEntity le : e.getAffectedEntities()) {
            if (!(le instanceof Player p)) continue;
            ConfigurationSection n = Passives.first(p, "instant-heal-level");
            if (n == null) continue;
            double extra = (NewClasses.potHeal(n) - vanilla) * e.getIntensity(le);
            if (extra > 0) Bukkit.getScheduler().runTask(Aegis.I, () -> { if (p.isOnline() && !p.isDead()) NewClasses.heal(p, extra); });
        }
    }

    // ---------------------------------------------------------------- Yggdrasil: kill hearts
    @EventHandler
    public void onKill(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        Player k = v.getKiller();
        if (k == null || k.equals(v)) return;
        ConfigurationSection n = Passives.first(k, "kill-hp");
        if (n == null) return;
        if (n.getBoolean("players-only", true) && !(v instanceof Player)) return;
        NewClasses.addKillHeart(k, n);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        NewClasses.clearKillHearts(e.getEntity());
    }

    // ================================================================ Jackpot / Fateweaver: items that are not used up
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ConfigurationSection n = Passives.first(p, "keep-chance");
        if (n != null && Passives.roll(n.getDouble("keep-chance", 10))) { MoreClasses.keepUse(p, e.getHand()); return; }
        if (e.getBlockPlaced().getType() == Material.COBWEB) {
            ConfigurationSection w = Passives.first(p, "web-save-chance");
            if (w != null && Passives.roll(w.getDouble("web-save-chance", 25))) MoreClasses.keepUse(p, e.getHand());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUseConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ConfigurationSection n = Passives.first(p, "keep-chance");
        if (n != null && Passives.roll(n.getDouble("keep-chance", 10)))
            MoreClasses.keepUse(p, p.getInventory().getItemInMainHand().isSimilar(e.getItem()) ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND);
    }

    @EventHandler(ignoreCancelled = true)
    public void onThrow(PlayerLaunchProjectileEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || e.getItemStack().getType() == Material.TRIDENT) return;
        ConfigurationSection n = Passives.first(p, "keep-chance");
        if (n != null && Passives.roll(n.getDouble("keep-chance", 10))) e.setShouldConsume(false);
    }

    // ---------------------------------------------------------------- Jackpot: gambler's gap
    @EventHandler(ignoreCancelled = true)
    public void onGapLuck(PlayerItemConsumeEvent e) {
        Material m = e.getItem().getType();
        if (m != Material.GOLDEN_APPLE && m != Material.ENCHANTED_GOLDEN_APPLE) return;
        Player p = e.getPlayer();
        ConfigurationSection n = Passives.first(p, "gap-fail-chance");
        if (n == null) return;
        final boolean fail = Passives.roll(n.getDouble("gap-fail-chance", 10));
        final boolean dbl = !fail && Passives.roll(n.getDouble("gap-double-chance", 10));
        if (!fail && !dbl) return;
        final int food = p.getFoodLevel();
        final float sat = p.getSaturation();
        final double abs = p.getAbsorptionAmount();
        final PotionEffect regen = p.getPotionEffect(PotionEffectType.REGENERATION);
        final PotionEffect absFx = p.getPotionEffect(PotionEffectType.ABSORPTION);
        Bukkit.getScheduler().runTask(Aegis.I, () -> {
            if (!p.isOnline()) return;
            if (fail) {
                p.setFoodLevel(food);
                p.setSaturation(sat);
                p.removePotionEffect(PotionEffectType.REGENERATION);
                p.removePotionEffect(PotionEffectType.ABSORPTION);
                if (regen != null) p.addPotionEffect(regen);
                if (absFx != null) p.addPotionEffect(absFx);
                p.setAbsorptionAmount(abs);
                p.sendMessage(Component.text("The golden apple was a dud!", NamedTextColor.RED));
            } else {
                PotionEffect a = p.getPotionEffect(PotionEffectType.ABSORPTION);
                if (a != null) {
                    p.removePotionEffect(PotionEffectType.ABSORPTION);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, a.getDuration(), Math.min(254, a.getAmplifier() * 2 + 1), a.isAmbient(), a.hasParticles(), a.hasIcon()));
                }
                p.setSaturation((float) Math.min(p.getFoodLevel(), sat + (p.getSaturation() - sat) * 2));
                p.sendMessage(Component.text("JACKPOT! Double absorption and saturation.", NamedTextColor.GOLD));
            }
        });
    }

    /** High Roller / Luck of the Draw "no potion effects" phase: nothing can be applied. */
    @EventHandler(ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || !Status.on(MoreClasses.noEffects, p)) return;
        EntityPotionEffectEvent.Action a = e.getAction();
        if (a == EntityPotionEffectEvent.Action.ADDED || a == EntityPotionEffectEvent.Action.CHANGED) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- Fateweaver: thread of fate shares damage
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLinkDamage(EntityDamageEvent e) {
        if (MoreClasses.mirroring) return;
        UUID other = MoreClasses.links.get(e.getEntity().getUniqueId());
        if (other == null) return;
        Entity o = Bukkit.getEntity(other);
        double dmg = e.getFinalDamage();
        if (!(o instanceof LivingEntity lo) || lo.isDead() || dmg <= 0) return;
        MoreClasses.mirroring = true;
        try { Abilities.hurtRaw(lo, dmg); } finally { MoreClasses.mirroring = false; }
    }

    // ---------------------------------------------------------------- Singularity
    @EventHandler(ignoreCancelled = true)
    public void onArrow(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Arrow ar) || !(ar.getShooter() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity v) || v.equals(p) || Abilities.trusts(p, v)) return;
        ConfigurationSection n = Passives.first(p, "arrow-pull");
        if (n != null) {
            final double str = n.getDouble("arrow-pull", 1.2);
            Bukkit.getScheduler().runTask(Aegis.I, () -> { if (v.isValid() && p.isOnline()) Abilities.pull(v, p.getLocation(), str); });
        }
        ConfigurationSection low = Passives.first(p, "low-hp-bonus");
        if (low != null && v.getHealth() < low.getDouble("hp-threshold", 12)) e.setDamage(e.getDamage() * low.getDouble("low-hp-bonus", 1.25));
    }

    @EventHandler(ignoreCancelled = true)
    public void onVoid(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.VOID || !(e.getEntity() instanceof Player p)) return;
        if (Passives.first(p, "void-immune") == null) return;
        e.setCancelled(true);
        MoreClasses.rescueFromVoid(p);
    }

    // ---------------------------------------------------------------- Spartan
    @EventHandler
    public void onSteedDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Horse h && MoreClasses.isSteed(h)) {
            e.getDrops().clear();   // no free saddle / armor
            e.setDroppedExp(0);
        }
    }

    @EventHandler
    public void onSteedInventory(InventoryOpenEvent e) {
        if (e.getInventory().getHolder() instanceof Horse h && MoreClasses.isSteed(h)) e.setCancelled(true);
    }

    @EventHandler
    public void onFeather(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.FEATHER) return;
        Player p = e.getPlayer();
        ConfigurationSection n = Passives.first(p, "feather-dash-cooldown");
        if (n == null) return;
        PlayerData d = Aegis.I.pd(p);
        double rem = d.remKey("feather");
        if (rem > 0) {
            p.sendActionBar(Component.text("Feather dash ready in " + (int) Math.ceil(rem) + "s", NamedTextColor.GRAY));
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
        d.startKey("feather", n.getDouble("feather-dash-cooldown", 30));
        Vector dir = p.getLocation().getDirection().multiply(n.getDouble("feather-dash-speed", 1.4));
        dir.setY(Math.max(0.25, dir.getY()));
        p.setVelocity(dir);
        p.getWorld().playSound(p.getLocation(), "entity.phantom.flap", 1f, 1.4f);
        Fx.burst(p.getLocation().add(0, 0.5, 0), Particle.CLOUD, 8, .05);
    }

    /** Spears break shields when the Spartan is moving fast enough. */
    @EventHandler
    public void onSpear(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof Player vp) || !vp.isBlocking()) return;
        ConfigurationSection n = Passives.first(p, "shield-break-speed");
        if (n == null || !p.getInventory().getItemInMainHand().getType().name().endsWith("_SPEAR")) return;
        if (MoreClasses.speedOf(p) < n.getDouble("shield-break-speed", 10)) return;
        if (e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) e.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0);
        MoreClasses.breakShield(vp, (int) Math.round(n.getDouble("shield-cooldown", 5) * 20));
    }
}
