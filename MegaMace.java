package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Mega Mace: a unique legendary weapon (only one can ever be crafted). */
final class MegaMace {
    static boolean crafted = false;
    static String crafter = "";
    static final Map<UUID, Integer> streak = new HashMap<>();   // consecutive airborne hits

    static NamespacedKey markKey() { return new NamespacedKey(Aegis.I, "mega_mace"); }

    static NamespacedKey recipeKey() { return new NamespacedKey(Aegis.I, "recipe_mega_mace"); }

    private static File file() { return new File(Aegis.I.getDataFolder(), "megamace.yml"); }

    static void load() {
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        crafted = y.getBoolean("crafted", false);
        crafter = y.getString("crafter", "");
    }

    static void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("crafted", crafted);
        y.set("crafter", crafter);
        try { Aegis.I.getDataFolder().mkdirs(); y.save(file()); }
        catch (IOException ex) { Aegis.I.getLogger().warning("Could not save megamace.yml: " + ex.getMessage()); }
    }

    static boolean isMace(ItemStack it) {
        return it != null && it.getType() == Material.MACE && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(markKey(), PersistentDataType.BYTE);
    }

    private static double cfg(String key, double def) { return Aegis.I.getConfig().getDouble("megamace." + key, def); }

    // ---------------------------------------------------------------- item + recipe
    static ItemStack create() {
        ItemStack it = new ItemStack(Material.MACE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Mega Mace", NamedTextColor.DARK_RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.getPersistentDataContainer().set(markKey(), PersistentDataType.BYTE, (byte) 1);
        int lvl = (int) cfg("enchant-level", 2);
        m.addEnchant(Enchantment.WIND_BURST, 1, true);
        m.addEnchant(Enchantment.BREACH, lvl, true);
        // vanilla mace damage, but a sword's attack speed (1.6 instead of 0.6)
        m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(new NamespacedKey(Aegis.I, "mm_damage"), 4.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(new NamespacedKey(Aegis.I, "mm_speed"), -2.4, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        it.setItemMeta(m);
        refreshLore(it);
        return it;
    }

    static void refreshLore(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        boolean breach = m.hasEnchant(Enchantment.BREACH);
        int lvl = (int) cfg("enchant-level", 2);
        List<Component> lore = new ArrayList<>();
        lore.add(line("Mode: " + (breach ? "Breach " : "Density ") + lvl, breach ? NamedTextColor.RED : NamedTextColor.AQUA));
        lore.add(line("Shift + Right-click: switch Breach / Density", NamedTextColor.GRAY));
        lore.add(line("F: dash " + (int) cfg("dash-distance", 10) + " blocks", NamedTextColor.GRAY));
        lore.add(line((int) cfg("armor-hits", 5) + " airborne hits: -" + (int) cfg("armor-durability", 80) + " durability on each armor piece", NamedTextColor.GRAY));
        lore.add(line("Sword attack speed, breaks shields like an axe", NamedTextColor.GRAY));
        lore.add(line("Damage cap: " + (cfg("damage-cap", 10.0) / 2.0) + " hearts per hit", NamedTextColor.GRAY));
        m.lore(lore);
        it.setItemMeta(m);
    }

    private static Component line(String s, NamedTextColor c) { return Component.text(s, c).decoration(TextDecoration.ITALIC, false); }

    static void register() {
        Bukkit.removeRecipe(NamespacedKey.minecraft("mace"));   // no craftable maces
        Bukkit.removeRecipe(recipeKey());
        ShapedRecipe r = new ShapedRecipe(recipeKey(), create());
        r.shape("NHN", " S ", " B ");
        r.setIngredient('N', Material.NETHERITE_BLOCK);
        r.setIngredient('H', Material.HEAVY_CORE);
        r.setIngredient('S', Material.NETHER_STAR);
        r.setIngredient('B', Material.BREEZE_ROD);
        Bukkit.addRecipe(r);
    }

    // ---------------------------------------------------------------- abilities
    static void toggle(Player p, ItemStack it) {
        ItemMeta m = it.getItemMeta();
        boolean breach = m.hasEnchant(Enchantment.BREACH);
        m.removeEnchant(Enchantment.BREACH);
        m.removeEnchant(Enchantment.DENSITY);
        int lvl = (int) cfg("enchant-level", 2);
        m.addEnchant(breach ? Enchantment.DENSITY : Enchantment.BREACH, lvl, true);
        m.addEnchant(Enchantment.WIND_BURST, 1, true);   // always keeps Wind Burst
        it.setItemMeta(m);
        refreshLore(it);
        p.sendActionBar(Component.text("Mega Mace: " + (breach ? "Density " : "Breach ") + lvl, breach ? NamedTextColor.AQUA : NamedTextColor.RED));
        p.getWorld().playSound(p.getLocation(), "block.anvil.use", 0.6f, 1.6f);
    }

    static void dash(Player p) {
        PlayerData d = Aegis.I.pd(p);
        double rem = d.remKey("megamace_dash");
        if (rem > 0) {
            p.sendActionBar(Component.text("Dash ready in " + (int) Math.ceil(rem) + "s", NamedTextColor.GRAY));
            return;
        }
        final int steps = 5;
        final double perTick = cfg("dash-distance", 10) / steps;   // blocks per tick, 5 ticks = the dash distance
        final double carry = cfg("dash-carry", 0.5);               // share of the dash speed you keep afterwards
        final Vector dir = p.getEyeLocation().getDirection().normalize();
        d.startKey("megamace_dash", cfg("dash-cooldown", 5));
        p.getWorld().playSound(p.getLocation(), "entity.wind_charge.wind_burst", 1f, 1f);
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { cancel(); return; }
                if (i >= steps) {
                    p.setVelocity(dir.clone().multiply(perTick * carry));   // keeps your momentum instead of stopping dead
                    cancel();
                    return;
                }
                p.setVelocity(dir.clone().multiply(perTick));               // normal collisions, so walls stop you naturally
                Fx.part(p.getLocation().add(0, 1, 0), Particle.CLOUD, 5, .3, .4, .3, .02);
                i++;
            }
        }.runTaskTimer(Aegis.I, 0, 1);
    }

    // ---------------------------------------------------------------- armor shred
    static void shred(LivingEntity v, int amount) {
        EntityEquipment eq = v.getEquipment();
        if (eq == null) return;
        eq.setHelmet(damage(eq.getHelmet(), amount));
        eq.setChestplate(damage(eq.getChestplate(), amount));
        eq.setLeggings(damage(eq.getLeggings(), amount));
        eq.setBoots(damage(eq.getBoots(), amount));
        v.getWorld().playSound(v.getLocation(), "entity.item.break", 1f, 0.7f);
    }

    private static ItemStack damage(ItemStack s, int amount) {
        if (s == null || s.getType().isAir()) return s;
        ItemMeta m = s.getItemMeta();
        int max = s.getType().getMaxDurability();
        if (!(m instanceof Damageable dm) || m.isUnbreakable() || max <= 0) return s;
        int nd = dm.getDamage() + amount;
        if (nd >= max) return null;   // the piece breaks
        dm.setDamage(nd);
        s.setItemMeta(dm);
        return s;
    }

    static void tick(Player p) {
        if (!streak.isEmpty() && p.isOnGround()) streak.remove(p.getUniqueId());   // touching the ground resets the combo
    }
}
