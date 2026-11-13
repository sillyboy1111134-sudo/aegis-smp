package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Soulbound items never drop; they come back in your inventory when you respawn (saved to disk meanwhile). */
final class Soulbound {
    private static final Map<UUID, List<ItemStack>> pending = new HashMap<>();

    private static File file() { return new File(Aegis.I.getDataFolder(), "soulbound.yml"); }

    static void load() {
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        for (String k : y.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                List<ItemStack> list = new ArrayList<>();
                List<?> raw = y.getList(k);
                if (raw != null) for (Object o : raw) if (o instanceof ItemStack i) list.add(i);
                if (!list.isEmpty()) pending.put(id, list);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    static void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> en : pending.entrySet()) y.set(en.getKey().toString(), en.getValue());
        try {
            Aegis.I.getDataFolder().mkdirs();
            y.save(file());
        } catch (IOException ex) {
            Aegis.I.getLogger().warning("Could not save soulbound.yml: " + ex.getMessage());
        }
    }

    static void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory()) return;
        Player p = e.getEntity();
        List<ItemStack> keep = new ArrayList<>();
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack i = it.next();
            if (CE.level(i, "soulbound") > 0) { keep.add(i); it.remove(); }
        }
        if (keep.isEmpty()) return;
        pending.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).addAll(keep);
        save();
    }

    static void restoreLater(Player p) { Bukkit.getScheduler().runTaskLater(Aegis.I, () -> restore(p), 3L); }

    static void restore(Player p) {
        List<ItemStack> l = pending.remove(p.getUniqueId());
        if (l == null || !p.isOnline()) { if (l != null) pending.put(p.getUniqueId(), l); return; }
        for (ItemStack i : l) p.getInventory().addItem(i).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
        save();
        p.sendMessage(Component.text("Your soulbound items returned.", NamedTextColor.LIGHT_PURPLE));
    }
}
