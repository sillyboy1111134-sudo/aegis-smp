package dev.aegis;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Locale;
import java.util.UUID;

/** Midas: mobs can drop their head. Vanilla heads where they exist, otherwise a named player head. */
final class Heads {
    static ItemStack headFor(LivingEntity v, ConfigurationSection node) {
        switch (v.getType()) {
            case ZOMBIE: return new ItemStack(Material.ZOMBIE_HEAD);
            case SKELETON: return new ItemStack(Material.SKELETON_SKULL);
            case WITHER_SKELETON: return new ItemStack(Material.WITHER_SKELETON_SKULL);
            case CREEPER: return new ItemStack(Material.CREEPER_HEAD);
            case PIGLIN: return new ItemStack(Material.PIGLIN_HEAD);
            case ENDER_DRAGON: return new ItemStack(Material.DRAGON_HEAD);
            default: break;
        }
        String type = v.getType().name();
        String pretty = capitalize(type.replace('_', ' '));
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) it.getItemMeta();
        String tex = node == null ? "" : node.getString("textures." + type, "");
        if (tex != null && !tex.isBlank()) {
            PlayerProfile pr = Bukkit.createProfile(UUID.nameUUIDFromBytes(("aegis-head-" + type).getBytes()), null);
            pr.setProperty(new ProfileProperty("textures", tex));
            m.setPlayerProfile(pr);
        }
        m.displayName(Component.text(pretty + " Head", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(m);
        return it;
    }

    private static String capitalize(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split(" "))
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return sb.toString().trim();
    }
}
