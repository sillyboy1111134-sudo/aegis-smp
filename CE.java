package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Custom enchantments, stored on the item as "id=level;id=level" (max 2 per item by default). */
final class CE {
    enum Type { SWORD, AXE, PICKAXE, CROSSBOW, BOW, ROD, SPEAR, ARMOR, ANY }

    record Def(String id, String name, int max, Type... types) { }

    static final TextColor LORE = TextColor.color(0xB388FF);

    static final List<Def> DEFS = List.of(
            new Def("bleed", "Bleed", 3, Type.SWORD),
            new Def("reel", "Reel", 3, Type.CROSSBOW),
            new Def("might", "Might", 5, Type.CROSSBOW),
            new Def("tri_shot", "Tri Shot", 1, Type.BOW),
            new Def("grapple", "Grapple", 1, Type.ROD),
            new Def("timber", "Timber", 1, Type.AXE),
            new Def("vein_miner", "Vein Miner", 1, Type.PICKAXE),
            new Def("executioner", "Executioner", 3, Type.SWORD),
            new Def("soulbound", "Soulbound", 1, Type.ANY),
            new Def("lifeleach", "Lifeleach", 5, Type.SWORD),
            new Def("excavation", "Excavation", 4, Type.PICKAXE),
            new Def("ember", "Ember", 1, Type.CROSSBOW),
            new Def("charge", "Charge", 3, Type.SPEAR),
            new Def("penetration", "Penetration", 4, Type.SPEAR),
            new Def("heavy_blow", "Heavy Blow", 1, Type.AXE),
            new Def("indomitable", "Indomitable", 1, Type.ARMOR),
            new Def("unmovable", "Unmovable", 1, Type.ARMOR),
            new Def("immortal", "Immortal", 1, Type.ARMOR),
            new Def("double_attack", "Double Attack", 1, Type.SWORD, Type.AXE),
            new Def("reshuffle", "Reshuffle", 1, Type.SWORD, Type.AXE));

    static NamespacedKey key() { return new NamespacedKey(Aegis.I, "custom_enchants"); }

    static Def def(String id) {
        for (Def d : DEFS) if (d.id().equalsIgnoreCase(id)) return d;
        return null;
    }

    static boolean enabled(Def d) { return Aegis.I.getConfig().getBoolean("custom-enchants." + d.id() + ".enabled", true); }

    static int max(Def d) { return Aegis.I.getConfig().getInt("custom-enchants." + d.id() + ".max-level", d.max()); }

    static String name(Def d) { return Aegis.I.getConfig().getString("custom-enchants." + d.id() + ".name", d.name()); }

    static int maxPerItem() { return Aegis.I.getConfig().getInt("custom-enchants.max-per-item", 2); }

    static boolean typeFits(Type t, Material m) {
        String n = m.name();
        return switch (t) {
            case SWORD -> n.endsWith("_SWORD");
            case AXE -> n.endsWith("_AXE");
            case PICKAXE -> n.endsWith("_PICKAXE");
            case CROSSBOW -> m == Material.CROSSBOW;
            case BOW -> m == Material.BOW;
            case ROD -> m == Material.FISHING_ROD;
            case SPEAR -> n.endsWith("_SPEAR");
            case ARMOR -> Gear.isArmor(m);
            case ANY -> true;
        };
    }

    static boolean fits(Def d, Material m) {
        for (Type t : d.types()) if (typeFits(t, m)) return true;
        return false;
    }

    // ------------------------------------------------------------ storage
    static Map<String, Integer> read(ItemStack s) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (s == null || s.getType().isAir() || !s.hasItemMeta()) return m;
        String str = s.getItemMeta().getPersistentDataContainer().get(key(), PersistentDataType.STRING);
        if (str == null || str.isEmpty()) return m;
        for (String part : str.split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            try { m.put(kv[0], Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    static int level(ItemStack s, String id) {
        Integer l = read(s).get(id);
        return l == null ? 0 : l;
    }

    static void write(ItemStack s, Map<String, Integer> m) {
        s.editMeta(meta -> {
            if (m.isEmpty()) meta.getPersistentDataContainer().remove(key());
            else {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> en : m.entrySet()) sb.append(en.getKey()).append('=').append(en.getValue()).append(';');
                meta.getPersistentDataContainer().set(key(), PersistentDataType.STRING, sb.toString());
            }
            relore(meta, m);
        });
    }

    private static void relore(ItemMeta meta, Map<String, Integer> m) {
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.removeIf(c -> LORE.equals(c.color()));
        int i = 0;
        for (Map.Entry<String, Integer> en : m.entrySet()) {
            Def d = def(en.getKey());
            if (d == null) continue;
            String text = name(d) + (max(d) > 1 ? " " + roman(en.getValue()) : "");
            lore.add(i++, Component.text(text, LORE).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore.isEmpty() ? null : lore);
        meta.setEnchantmentGlintOverride(m.isEmpty() ? null : Boolean.TRUE);
    }

    /** Adds (or raises) an enchant. Refuses a 3rd one. */
    static boolean add(ItemStack s, String id, int level) {
        Def d = def(id);
        if (d == null) return false;
        Map<String, Integer> m = read(s);
        if (!m.containsKey(d.id()) && m.size() >= maxPerItem()) return false;
        m.put(d.id(), Math.max(1, Math.min(max(d), level)));
        write(s, m);
        return true;
    }

    static void strip(ItemStack s) { write(s, new LinkedHashMap<>()); }

    static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    static int durabilityLeft(ItemStack s) {
        if (s == null || !(s.getItemMeta() instanceof Damageable dm)) return 0;
        return s.getType().getMaxDurability() - dm.getDamage();
    }
}
