package dev.aegis;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;

/** Vow of the Cart: only carts placed by someone who took the vow can explode. */
final class VowListener implements Listener {
    private final Map<String, Long> placing = new HashMap<>();

    private static String key(Block b) { return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.TNT_MINECART) return;
        if (Aegis.I.pd(e.getPlayer()).vows.isEmpty() || !Vows.state(e.getPlayer()).cart) return;
        long now = System.currentTimeMillis();
        placing.values().removeIf(t -> now - t > 3000);
        placing.put(key(e.getClickedBlock()), now);
    }

    @EventHandler
    public void onCreate(VehicleCreateEvent e) {
        if (!(e.getVehicle() instanceof ExplosiveMinecart cart)) return;
        Long t = placing.remove(key(cart.getLocation().getBlock()));
        if (t != null && System.currentTimeMillis() - t < 3000)
            cart.getPersistentDataContainer().set(Vows.cartKey(), PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent e) {
        if (e.getEntity() instanceof ExplosiveMinecart cart && !Vows.isVowCart(cart)) e.setCancelled(true);
    }
}
