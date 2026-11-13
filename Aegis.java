package dev.aegis;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class Aegis extends JavaPlugin {
    public static Aegis I;
    public final Map<UUID, PlayerData> data = new HashMap<>();
    public NamespacedKey itemKey, tempKey, gearKey, boostKey, windKey, petKey;
    private File dataFile;

    @Override
    public void onEnable() {
        I = this;
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "item");
        tempKey = new NamespacedKey(this, "temp");
        gearKey = new NamespacedKey(this, "gear");
        boostKey = new NamespacedKey(this, "boost");
        windKey = new NamespacedKey(this, "windburst");
        petKey = new NamespacedKey(this, "pet_owner");
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        getServer().getPluginManager().registerEvents(new CombatListener(), this);
        getServer().getPluginManager().registerEvents(new UtilityListener(), this);

        Commands c = new Commands();
        for (String n : new String[]{"ability", "trust", "aegis", "myclass"}) {
            PluginCommand cmd = getCommand(n);
            if (cmd != null) { cmd.setExecutor(c); cmd.setTabCompleter(c); }
        }
        Recipes.register();
        Tasks.start();
        for (Player p : Bukkit.getOnlinePlayers()) { Status.clearMods(p); Passives.refresh(p); }
    }

    @Override
    public void onDisable() {
        Abilities.shutdown();
        for (Player p : Bukkit.getOnlinePlayers()) Status.clearMods(p);
        saveData();
    }

    public PlayerData pd(UUID id) {
        return data.computeIfAbsent(id, k -> {
            PlayerData d = new PlayerData();
            d.energy = getConfig().getInt("general.starting-energy", 2);
            d.cmdMode = getConfig().getBoolean("general.default-command-mode", false);
            return d;
        });
    }

    public PlayerData pd(Player p) { return pd(p.getUniqueId()); }

    public int maxEnergy() { return getConfig().getInt("general.max-energy", 5); }

    public ClassType randomClass(ClassType not) {
        List<ClassType> l = new ArrayList<>(Arrays.asList(ClassType.values()));
        l.remove(not);
        return l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    // ------------------------------------------------------------------ persistence
    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("players");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                PlayerData d = new PlayerData();
                d.type = ClassType.byId(s.getString(key + ".class", ""));
                d.energy = s.getInt(key + ".energy", getConfig().getInt("general.starting-energy", 2));
                d.cmdMode = s.getBoolean(key + ".cmd", false);
                for (String t : s.getStringList(key + ".trusted")) d.trusted.add(UUID.fromString(t));
                data.put(id, d);
            } catch (Exception ignored) { }
        }
    }

    public void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerData> e : data.entrySet()) {
            String k = "players." + e.getKey();
            PlayerData d = e.getValue();
            y.set(k + ".class", d.type == null ? "" : d.type.id);
            y.set(k + ".energy", d.energy);
            y.set(k + ".cmd", d.cmdMode);
            List<String> t = new ArrayList<>();
            for (UUID u : d.trusted) t.add(u.toString());
            y.set(k + ".trusted", t);
        }
        try { getDataFolder().mkdirs(); y.save(dataFile); } catch (IOException ex) { getLogger().warning("Could not save data.yml: " + ex.getMessage()); }
    }
}
