package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Event hooks for the ultimates. */
final class UltListener implements Listener {

    // ---- Agni: no water buckets inside A Piece of Hell (except for its owner)
    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (e.getBucket() != Material.WATER_BUCKET) return;
        UltImpl.Dom d = UltImpl.domainAt(e.getBlock().getLocation(), "hell");
        if (d != null && !d.owner.equals(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("The water evaporates in this hellish place.", NamedTextColor.RED));
        }
    }

    // ---- domains are unbreakable; hitting the World Tree hurts it
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!TempBuild.isProtected(b)) return;
        e.setCancelled(true);
        UltImpl.treeHit(b, e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageBlock(BlockDamageEvent e) {
        if (TempBuild.isProtected(e.getBlock())) {
            UltImpl.treeHit(e.getBlock(), e.getPlayer());
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(TempBuild::isProtected); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(TempBuild::isProtected); }

    // ---- Hephaestus: things crafted in the Workshop are better
    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !UltImpl.workshopActive(p)) return;
        if (e.isShiftClick()) {
            e.setCancelled(true);
            p.sendMessage(Component.text("Click the result normally inside the Workshop.", NamedTextColor.RED));
            return;
        }
        ItemStack res = e.getCurrentItem();
        if (res == null || res.getType().isAir()) return;
        e.setCurrentItem(UltImpl.enhance(res.clone()));
    }

    // ---- Rogue's shadow clones (the AI is in ShadowClones)
    /** The vanilla zombie AI never picks targets for a clone: the clone AI does all the fighting itself. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClone(EntityTargetLivingEntityEvent e) {
        if (ShadowClones.isClone(e.getEntity())) e.setCancelled(true);
    }

    /** Only the clone AI's own swings count; a vanilla zombie bite from a clone is cancelled. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCloneSwing(EntityDamageByEntityEvent e) {
        if (ShadowClones.isClone(e.getDamager()) && !ShadowClones.swinging) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloneBlock(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Zombie z && ShadowClones.isClone(z) && ShadowClones.shieldBlocks(z, e.getDamager())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity se) damager = se;
        // a clone that gets hit by a player hunts that player
        if (e.getEntity() instanceof Zombie z && ShadowClones.isClone(z)) ShadowClones.provoke(z, damager);
        // Boreus: every hit in the air gives another boost
        if (damager instanceof Player p && e.getEntity() instanceof LivingEntity) UltImpl.skyHit(p);
    }

    @EventHandler
    public void onCloneDeath(EntityDeathEvent e) {
        if (!ShadowClones.isClone(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        ShadowClones.died(e.getEntity());
    }

    // ---- Boreus: Sky Ruler never hurts the rider (crashing into walls, landing)
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSkyCrash(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !UltImpl.inSky(p)) return;
        switch (e.getCause()) { case FALL, FLY_INTO_WALL -> e.setCancelled(true); default -> { } }
    }

    // ---- Boreus: the borrowed elytra never drops and can't be taken off
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) { UltImpl.fixDrops(e); }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (UltImpl.isUltElytra(e.getCurrentItem()) || UltImpl.isUltElytra(e.getCursor())) e.setCancelled(true);
    }

    // ---- Spartan: left-click with a sword hurls an Ares' Wrath spear
    @EventHandler
    public void onLeftClick(PlayerInteractEvent e) {
        if (!e.getAction().isLeftClick()) return;
        Player p = e.getPlayer();
        if (UltImpl.aresArmed(p)) UltImpl.aresFire(p);
    }
}
