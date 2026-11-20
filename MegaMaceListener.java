package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

import java.time.Duration;
import java.util.UUID;

final class MegaMaceListener implements Listener {

    private static boolean ours(org.bukkit.inventory.Recipe r) {
        return r instanceof ShapedRecipe sr && sr.getKey().equals(MegaMace.recipeKey());
    }

    // ---------------------------------------------------------------- only one can ever be crafted
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (ours(e.getRecipe()) && MegaMace.crafted) e.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!ours(e.getRecipe())) return;
        if (MegaMace.crafted) { e.setCancelled(true); return; }
        if (e.isShiftClick()) {
            e.setCancelled(true);
            e.getWhoClicked().sendMessage(Component.text("Click the result normally to craft the Mega Mace.", NamedTextColor.RED));
            return;
        }
        MegaMace.crafted = true;
        MegaMace.crafter = e.getWhoClicked().getName();
        MegaMace.save();

        Component big = Component.text("MEGA MACE", NamedTextColor.DARK_RED, TextDecoration.BOLD);
        Component sub = Component.text(MegaMace.crafter + " has crafted the Mega Mace!", NamedTextColor.GOLD);
        Title title = Title.title(big, sub, Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(1)));
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.showTitle(title);
            o.playSound(o.getLocation(), "entity.wither.spawn", 0.8f, 1f);
        }
        Bukkit.broadcast(Component.text(MegaMace.crafter + " has crafted the Mega Mace!", NamedTextColor.GOLD));
    }

    // ---------------------------------------------------------------- Shift + right-click: Breach <-> Density
    @EventHandler(priority = EventPriority.HIGH)
    public void onToggle(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        ItemStack it = p.getInventory().getItemInMainHand();
        if (!MegaMace.isMace(it)) return;
        if (Status.on(Status.stun, p) || Status.on(Status.freeze, p)) return;
        e.setCancelled(true);
        MegaMace.toggle(p, it);
    }

    // ---------------------------------------------------------------- F: dash (takes priority over the class ability while held)
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!MegaMace.isMace(p.getInventory().getItemInMainHand())) return;
        if (Status.on(Status.stun, p) || Status.on(Status.freeze, p)) return;
        e.setCancelled(true);
        MegaMace.dash(p);
    }

    // ---------------------------------------------------------------- hits: shield break + armor shred combo
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity v)) return;
        ItemStack it = p.getInventory().getItemInMainHand();
        if (!MegaMace.isMace(it)) return;

        // breaks shields like an axe
        if (v instanceof Player vp && vp.isBlocking()) {
            vp.setCooldown(Material.SHIELD, (int) Math.round(Aegis.I.getConfig().getDouble("megamace.shield-disable-seconds", 5) * 20));
            vp.clearActiveItem();
        }

        // consecutive hits without touching the ground
        UUID id = p.getUniqueId();
        if (p.isOnGround()) { MegaMace.streak.remove(id); return; }
        int need = Math.max(1, Aegis.I.getConfig().getInt("megamace.armor-hits", 5));
        int n = MegaMace.streak.merge(id, 1, Integer::sum);
        if (n >= need) {
            MegaMace.streak.remove(id);
            MegaMace.shred(v, Aegis.I.getConfig().getInt("megamace.armor-durability", 80));
            p.sendActionBar(Component.text("Armor shredded!", NamedTextColor.RED));
        } else {
            p.sendActionBar(Component.text("Air combo " + n + "/" + need, NamedTextColor.GOLD));
        }
    }
}
