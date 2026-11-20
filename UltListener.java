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

    // ---- Rogue's shadow clones
    @EventHandler(ignoreCancelled = true)
    public void onClone(EntityTargetLivingEntityEvent e) {
        Entity z = e.getEntity();
        if (!UltImpl.isClone(z) || e.getTarget() == null) return;
        UUID owner = UltImpl.cloneOwner.get(z.getUniqueId());
        LivingEntity t = e.getTarget();
        Player o = owner == null ? null : org.bukkit.Bukkit.getPlayer(owner);
        if (t.getUniqueId().equals(owner) || (o != null && Abilities.trusts(o, t))) { e.setCancelled(true); return; }
        if (UltImpl.isRunner(z) && !t.getUniqueId().equals(UltImpl.provoked.get(z.getUniqueId()))) e.setCancelled(true);   // runners only fight back
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity se) damager = se;
        // a clone that gets hit hunts whoever hit it
        if (e.getEntity() instanceof Zombie z && UltImpl.isClone(z) && damager instanceof LivingEntity att) {
            UUID owner = UltImpl.cloneOwner.get(z.getUniqueId());
            Player o = owner == null ? null : org.bukkit.Bukkit.getPlayer(owner);
            if (!att.getUniqueId().equals(owner) && (o == null || !Abilities.trusts(o, att))) {
                UltImpl.provoked.put(z.getUniqueId(), att.getUniqueId());
                z.setTarget(att);
            }
        }
        // Boreus: every hit in the air gives another boost
        if (damager instanceof Player p && e.getEntity() instanceof LivingEntity) UltImpl.skyHit(p);
    }

    @EventHandler
    public void onCloneDeath(EntityDeathEvent e) {
        if (!UltImpl.isClone(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        UltImpl.cloneDied(e.getEntity());
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
