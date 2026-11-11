package dev.aegis;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

final class Items {
    /** kind = "energy" or "reroll". They are PLAYER_HEADs; the resource pack gives them the orb look. */
    static ItemStack make(String kind, int amount) {
        Aegis pl = Aegis.I;
        ItemStack it = new ItemStack(Material.PLAYER_HEAD, Math.max(1, amount));
        SkullMeta m = (SkullMeta) it.getItemMeta();

        String tex = pl.getConfig().getString("items." + kind + ".texture", "");
        if (tex != null && !tex.isBlank()) {
            PlayerProfile pr = Bukkit.createProfile(UUID.nameUUIDFromBytes(("ascend-" + kind).getBytes()), null);
            pr.setProperty(new ProfileProperty("textures", tex));
            m.setPlayerProfile(pr);
        }

        boolean energy = kind.equals("energy");
        String name = pl.getConfig().getString("items." + kind + ".name", energy ? "Energy Marble" : "Class Reroller");
        m.displayName(Component.text(name, energy ? NamedTextColor.AQUA : NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text(energy ? "Right-click: +1 energy" : "Right-click: reroll your class", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));

        if (pl.getConfig().getBoolean("items.use-item-model", true)) {
            try { m.setItemModel(new NamespacedKey("aegis", kind)); } catch (Throwable ignored) { }
        }
        m.getPersistentDataContainer().set(pl.itemKey, PersistentDataType.STRING, kind);
        it.setItemMeta(m);
        return it;
    }

    /** "energy", "reroll" or null. */
    static String kind(ItemStack s) {
        if (s == null || s.getType() != Material.PLAYER_HEAD || !s.hasItemMeta()) return null;
        return s.getItemMeta().getPersistentDataContainer().get(Aegis.I.itemKey, PersistentDataType.STRING);
    }

    static boolean isTemp(ItemStack s) {
        if (s == null || s.getType().isAir() || !s.hasItemMeta()) return false;
        ItemMeta m = s.getItemMeta();
        return m.getPersistentDataContainer().has(Aegis.I.tempKey, PersistentDataType.BYTE);
    }
}
