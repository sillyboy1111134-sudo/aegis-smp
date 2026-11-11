package dev.aegis;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Furnace-recipe lookup (built once from the server's recipes) used by Hephaestus' autosmelt. */
final class Smelting {
    private static Map<Material, Material> map;

    static Material result(Material in) {
        if (map == null) build();
        return map.get(in);
    }

    private static void build() {
        map = new HashMap<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (!(r instanceof FurnaceRecipe fr)) continue;
            if (fr.getInputChoice() instanceof RecipeChoice.MaterialChoice mc)
                for (Material m : mc.getChoices()) map.putIfAbsent(m, fr.getResult().getType());
        }
    }
}
