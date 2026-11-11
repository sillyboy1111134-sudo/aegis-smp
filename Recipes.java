package dev.aegis;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ShapedRecipe;

import java.util.List;

/** Optional crafting recipes for the Energy Marble ("energy") and Class Reroller ("reroll"). Off by default. */
final class Recipes {
    static void register() {
        for (String kind : new String[]{"energy", "reroll"}) {
            NamespacedKey key = new NamespacedKey(Aegis.I, "recipe_" + kind);
            Bukkit.removeRecipe(key);
            ConfigurationSection s = Aegis.I.getConfig().getConfigurationSection("recipes." + kind);
            if (s == null || !s.getBoolean("enabled", false)) continue;
            try {
                ShapedRecipe r = new ShapedRecipe(key, Items.make(kind, Math.max(1, s.getInt("amount", 1))));
                List<String> shape = s.getStringList("shape");
                r.shape(shape.toArray(new String[0]));
                ConfigurationSection ing = s.getConfigurationSection("ingredients");
                if (ing == null) continue;
                for (String k : ing.getKeys(false)) {
                    Material m = Material.matchMaterial(ing.getString(k, ""));
                    if (m != null) r.setIngredient(k.charAt(0), m);
                }
                Bukkit.addRecipe(r);
            } catch (Exception ex) {
                Aegis.I.getLogger().warning("Bad recipe for " + kind + ": " + ex.getMessage());
            }
        }
    }
}
