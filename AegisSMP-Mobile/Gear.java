package dev.aegis;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class Gear {
    static final Set<String> PIECES = Set.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");

    static String cat(Material m) {
        String n = m.name();
        if (n.contains("HORSE") || n.contains("WOLF")) return "OTHER";
        String s = n.substring(n.lastIndexOf('_') + 1);
        return PIECES.contains(s) ? "ARMOR" : s;
    }

    /**
     * Global rule: netherite gear is turned into diamond. Only categories a class passive allows
     * (netherite-upgrade: [SWORD] / [AXE] / [ARMOR]) are kept as netherite, and diamond items of
     * those categories are upgraded to netherite.
     */
    static void scan(Player p) {
        Set<String> allow = new HashSet<>();
        double tdmg = 0, tspeed = 0;
        int riptide = 0;
        for (String id : Passives.unlocked(p)) {
            ConfigurationSection n = Passives.node(p, id);
            if (n == null) continue;
            for (String c : n.getStringList("netherite-upgrade")) allow.add(c.toUpperCase());
            if (n.contains("trident-attack-damage")) { tdmg = n.getDouble("trident-attack-damage"); tspeed = n.getDouble("trident-attack-speed", 1.6); }
            if (n.contains("trident-riptide")) riptide = n.getInt("trident-riptide");
        }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i <= 40; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir() || Items.isTemp(s)) continue;
            ItemStack r = swapMaterial(s, allow);
            if (s.getType() == Material.TRIDENT || r.getType() == Material.TRIDENT) r = tridentFix(r, tdmg, tspeed, riptide);
            if (r != s) inv.setItem(i, r);
        }
    }

    private static ItemStack swapMaterial(ItemStack s, Set<String> allow) {
        String name = s.getType().name();
        if (name.startsWith("NETHERITE_") && !name.endsWith("INGOT") && !name.endsWith("SCRAP")
                && !name.endsWith("BLOCK") && !name.contains("TEMPLATE")) {
            Material dm = Material.getMaterial("DIAMOND_" + name.substring(10));
            if (dm != null && !allow.contains(cat(s.getType()))) return s.withType(dm);
        } else if (name.startsWith("DIAMOND_")) {
            String c = cat(s.getType());
            if (allow.contains(c)) {
                Material nm = Material.getMaterial("NETHERITE_" + name.substring(8));
                if (nm != null) return s.withType(nm);
            }
        }
        return s;
    }

    /** Varuna trident: 10 attack damage, sword attack speed, auto riptide. Reverts when the passive is gone. */
    private static ItemStack tridentFix(ItemStack s, double dmg, double speed, int riptide) {
        if (s.getType() != Material.TRIDENT) return s;
        Aegis pl = Aegis.I;
        NamespacedKey dmgKey = new NamespacedKey(pl, "trident_dmg"), spdKey = new NamespacedKey(pl, "trident_spd");
        NamespacedKey ripKey = new NamespacedKey(pl, "trident_rip");
        ItemMeta meta = s.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        boolean tagged = pdc.has(pl.gearKey, PersistentDataType.BYTE);
        boolean ripTagged = pdc.has(ripKey, PersistentDataType.BYTE);
        boolean changed = false;

        if (dmg > 0 && !tagged) {
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(dmgKey, dmg - 1.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(spdKey, speed - 4.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            pdc.set(pl.gearKey, PersistentDataType.BYTE, (byte) 1);
            changed = true;
        } else if (dmg <= 0 && tagged) {
            meta.setAttributeModifiers(null);
            pdc.remove(pl.gearKey);
            changed = true;
        }

        if (riptide > 0 && !ripTagged && !meta.hasEnchant(Enchantment.RIPTIDE)) {
            meta.addEnchant(Enchantment.RIPTIDE, riptide, true);
            pdc.set(ripKey, PersistentDataType.BYTE, (byte) 1);
            changed = true;
        } else if (riptide <= 0 && ripTagged) {
            meta.removeEnchant(Enchantment.RIPTIDE);
            pdc.remove(ripKey);
            changed = true;
        }
        if (!changed) return s;
        ItemStack out = s.clone();
        out.setItemMeta(meta);
        return out;
    }

    // ------------------------------------------------------------ armor durability
    /** delta > 0 damages every armor piece, delta < 0 repairs it. Never breaks a piece. */
    static void armorDelta(LivingEntity e, int delta) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null) return;
        ItemStack[] arr = eq.getArmorContents();
        for (ItemStack s : arr) {
            if (s == null || s.getType().isAir()) continue;
            if (!(s.getItemMeta() instanceof Damageable dm)) continue;
            int max = s.getType().getMaxDurability();
            if (max <= 0) continue;
            dm.setDamage(Math.max(0, Math.min(max - 1, dm.getDamage() + delta)));
            s.setItemMeta(dm);
        }
        eq.setArmorContents(arr);
    }

    /** Extra armor wear for "deals 1.25x armor damage" (ratio = 0.25), with random rounding. */
    static void extraArmor(LivingEntity v, double ratio, double dmg) {
        if (ratio <= 0) return;
        double x = ratio * Math.max(1.0, dmg / 4.0);
        int n = (int) x;
        if (ThreadLocalRandom.current().nextDouble() < x - n) n++;
        if (n > 0) armorDelta(v, n);
    }

    static boolean isArmor(Material m) { return PIECES.contains(m.name().substring(m.name().lastIndexOf('_') + 1)); }
}
