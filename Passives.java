package dev.aegis;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class Passives {
    static ConfigurationSection node(ClassType t, String pid) {
        return Aegis.I.getConfig().getConfigurationSection("classes." + t.id + ".passives." + pid);
    }

    static ConfigurationSection node(Player p, String pid) {
        ClassType t = Aegis.I.pd(p).type;
        return t == null ? null : node(t, pid);
    }

    /** Energy needed for passive #idx. First = 0, last = max energy, rest spread evenly. */
    static int unlockAt(ClassType t, int idx) {
        int n = t.passives.length;
        int max = Aegis.I.maxEnergy();
        return n <= 1 ? 0 : Math.round((float) idx * max / (n - 1));
    }

    static List<String> unlocked(Player p) {
        PlayerData d = Aegis.I.pd(p);
        List<String> r = new ArrayList<>();
        if (d.type == null) return r;
        for (int i = 0; i < d.type.passives.length; i++)
            if (d.energy >= unlockAt(d.type, i)) r.add(d.type.passives[i]);
        return r;
    }

    static boolean has(Player p, String pid) { return unlocked(p).contains(pid); }

    /** First unlocked passive node that contains the given key, or null. */
    static ConfigurationSection first(Player p, String key) {
        for (String id : unlocked(p)) {
            ConfigurationSection n = node(p, id);
            if (n != null && n.contains(key)) return n;
        }
        return null;
    }

    static void shuffleHotbar(Player v) {
        java.util.List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) items.add(v.getInventory().getItem(i));
        java.util.Collections.shuffle(items);
        for (int i = 0; i < 9; i++) v.getInventory().setItem(i, items.get(i));
    }

    static boolean roll(double percent) {
        return ThreadLocalRandom.current().nextDouble() * 100.0 < percent;
    }

    // ------------------------------------------------------------ permanent effects + attributes
    static void refresh(Player p) {
        PlayerData d = Aegis.I.pd(p);
        for (PotionEffectType t : new ArrayList<>(d.applied)) {
            PotionEffect e = p.getPotionEffect(t);
            if (e != null && e.isInfinite()) p.removePotionEffect(t);
        }
        d.applied.clear();

        AttributeInstance kb = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        NamespacedKey key = new NamespacedKey(Aegis.I, "kb_res");
        if (kb != null)
            for (AttributeModifier m : new ArrayList<>(kb.getModifiers())) if (m.getKey().equals(key)) kb.removeModifier(m);
        if (d.type == null) return;

        double kbv = 0;
        for (String id : unlocked(p)) {
            ConfigurationSection n = node(d.type, id);
            if (n == null) continue;
            for (String s : n.getStringList("permanent-effects")) {
                String[] a = s.split(":");
                PotionEffectType t = Abilities.fx(a[0]);
                if (t == null) continue;
                int amp = a.length > 1 ? Integer.parseInt(a[1].trim()) - 1 : 0;
                p.addPotionEffect(new PotionEffect(t, PotionEffect.INFINITE_DURATION, Math.max(0, amp), false, false, true));
                d.applied.add(t);
            }
            kbv += n.getDouble("knockback-resistance", 0);
        }
        if (kb != null && kbv > 0)
            kb.addModifier(new AttributeModifier(key, Math.min(1.0, kbv), AttributeModifier.Operation.ADD_NUMBER));
    }

    // ------------------------------------------------------------ proc actions
    static void action(LivingEntity target, ConfigurationSection n) {
        switch (n.getString("action", "")) {
            case "effect" -> {
                PotionEffectType t = Abilities.fx(n.getString("effect"));
                if (t != null)
                    target.addPotionEffect(new PotionEffect(t, (int) (n.getDouble("seconds", 3) * 20), Math.max(0, n.getInt("level", 1) - 1)));
            }
            case "bleed" -> Status.bleed(target, n.getDouble("seconds", 5));
            case "ignite" -> target.setFireTicks((int) (n.getDouble("seconds", 5) * 20));
            case "enchain" -> Status.set(Status.enchain, target, n.getDouble("seconds", 2));
            case "stun" -> Status.stun(target, n.getDouble("seconds", 1.5));
            case "shuffle" -> { if (target instanceof Player v) shuffleHotbar(v); }   // reshuffles the whole hotbar
            default -> { }
        }
        Fx.proc(target, n);
    }

    /** Standing on / in any listed sculk block (incl. catalysts, shriekers, sensors, veins). */
    static boolean onSculk(Player p, ConfigurationSection n) {
        java.util.Set<String> mats = new java.util.HashSet<>(n.getStringList("sculk-blocks"));
        org.bukkit.Location l = p.getLocation();
        for (double dy : new double[]{-0.1, 0.5, -1.0})
            if (mats.contains(l.clone().add(0, dy, 0).getBlock().getType().name())) return true;
        return false;
    }

    // ------------------------------------------------------------ once-per-second work
    static void tick(Player p, int counter) {
        PlayerData d = Aegis.I.pd(p);
        if (d.type == null || p.isDead()) return;
        for (String id : unlocked(p)) {
            ConfigurationSection n = node(d.type, id);
            if (n == null) continue;

            String when = n.getString("when", "");
            if ((when.equals("water_or_rain") && p.isInWaterOrRain()) || (when.equals("lava") && p.isInLava())
                    || (when.equals("sculk") && onSculk(p, n))) {
                for (String s : n.getStringList("effects")) {
                    String[] a = s.split(":");
                    PotionEffectType t = Abilities.fx(a[0]);
                    if (t == null) continue;
                    p.addPotionEffect(new PotionEffect(t, 60, Math.max(0, Integer.parseInt(a[1].trim()) - 1), false, false, true));
                }
            }

            if (n.getStringList("ignored-by").contains("WARDEN"))
                for (org.bukkit.entity.Warden wd : p.getWorld().getNearbyEntitiesByType(org.bukkit.entity.Warden.class, p.getLocation(), 64)) wd.clearAnger(p);

            if (n.contains("repair-amount")) {
                int every = Math.max(1, n.getInt("repair-every-seconds", 5));
                if (counter % every == 0) Gear.armorDelta(p, -n.getInt("repair-amount", 2));
            }

            if (n.contains("glow-radius")) {
                double r = n.getDouble("glow-radius", 50);
                for (Player o : p.getWorld().getPlayers()) {
                    if (o.equals(p) || o.isDead() || Abilities.trusts(p, o)) continue;
                    if (o.getLocation().distanceSquared(p.getLocation()) <= r * r)
                        o.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 50, 0, false, false, false));
                }
            }
        }
    }
}
