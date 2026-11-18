package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binding vows: permanent sacrifices (hearts, abilities, passives) for permanent gains.
 * A vow can never be undone (only an admin can wipe them; they stay through class rerolls), and anything a vow touches is locked in.
 */
final class Vows {
    static final String[] IDS = {"cart", "trade", "focus", "link", "blood", "phoenix"};
    static final Set<String> DAMAGE_KEYS = Set.of("damage", "beam-damage", "bonus-damage");
    static final Set<String> DURATION_KEYS = Set.of("seconds", "decoy-seconds", "freeze-seconds", "stun-seconds", "bleed-seconds",
            "window-seconds", "shield-break-seconds", "burn-seconds");

    /** Everything the active vows of a player add up to (cached on PlayerData). */
    static final class State {
        final Set<Integer> sacAbilities = new HashSet<>();
        final Set<Integer> lockedAbilities = new HashSet<>();     // buffed abilities can never be sacrificed
        final Set<String> sacPassives = new HashSet<>();
        final Set<String> lockedPassives = new HashSet<>();       // buffed passives can never be sacrificed
        final Map<Integer, Double> dmg = new HashMap<>(), dur = new HashMap<>(), cd = new HashMap<>(), charge = new HashMap<>();
        boolean cart, phoenix;
        double heartsHp;
    }

    static final class Prep {
        String error, param, summary;
        static Prep err(String e) { Prep p = new Prep(); p.error = e; return p; }
        static Prep ok(String param, String summary) { Prep p = new Prep(); p.param = param; p.summary = summary; return p; }
    }

    // ================================================================ config
    static double cfg(String key, double def) { return Aegis.I.getConfig().getDouble("vows." + key, def); }

    static double cap() { return cfg("damage-cap", 10.0); }

    static double heartCost() { return cfg("heart-cost-hp", 4.0); }

    static NamespacedKey cartKey() { return new NamespacedKey(Aegis.I, "vow_cart"); }

    static boolean isVowCart(Entity e) { return e.getPersistentDataContainer().has(cartKey(), PersistentDataType.BYTE); }

    // ================================================================ names / texts
    static String name(String id) {
        return switch (id) {
            case "cart" -> "Vow of the Cart";
            case "trade" -> "Vow of Exchange";
            case "focus" -> "Vow of Focus";
            case "link" -> "Vow of Kinship";
            case "blood" -> "Vow of Blood";
            case "phoenix" -> "Vow of the Phoenix";
            default -> id;
        };
    }

    static String cost(String id) {
        return switch (id) {
            case "cart", "blood" -> "2 permanent hearts";
            case "trade", "phoenix" -> "1 of your abilities";
            case "focus" -> "2 passives";
            default -> "1 passive";   // link
        };
    }

    static String gain(String id) {
        return switch (id) {
            case "cart" -> "your TNT minecarts can explode (nobody else's can), blast capped at 5 hearts";
            case "trade" -> "the other ability gets double damage OR double duration (never above 5 hearts of damage); Agni's Fireball charges in half the time";
            case "focus" -> "one ability: -" + (int) Math.round((1 - cfg("focus-cooldown", 0.6)) * 100) + "% cooldown and +" + (int) Math.round((cfg("focus-duration", 1.5) - 1) * 100) + "% duration";
            case "link" -> "another passive becomes " + (int) Math.round((cfg("passive-buff", 1.5) - 1) * 100) + "% stronger";
            case "blood" -> "all your ability cooldowns -" + (int) Math.round((1 - cfg("blood-cooldown", 0.75)) * 100) + "%";
            case "phoenix" -> "survive a killing blow with " + (int) cfg("phoenix-hearts", 3) + " hearts, once every " + (int) (cfg("phoenix-cooldown", 900) / 60) + " minutes";
            default -> "";
        };
    }

    static String usage(String id) {
        return switch (id) {
            case "trade" -> "/vow take trade <1|2 = ability to sacrifice> <damage|duration|charge>";
            case "focus" -> "/vow take focus <passive> <passive> <1|2 = ability to buff>";
            case "link" -> "/vow take link <passive to sacrifice> <passive to buff>";
            case "phoenix" -> "/vow take phoenix <1|2 = ability to sacrifice>";
            default -> "/vow take " + id;
        };
    }

    /** Position of a passive in its class (vows remember the slot, so they survive a class reroll). */
    static int idx(ClassType t, String pid) {
        for (int i = 0; i < t.passives.length; i++) if (t.passives[i].equals(pid)) return i;
        return -1;
    }

    /** A stored token is a passive slot number (or, for old data, an id). Returns the passive id of the current class, or null. */
    static String pidOf(ClassType t, String token) {
        if (t == null || token == null || token.isEmpty()) return null;
        try {
            int i = Integer.parseInt(token.trim());
            return i >= 0 && i < t.passives.length ? t.passives[i] : null;
        } catch (NumberFormatException e) {
            return token;
        }
    }

    private static String pName(ClassType t, String token) {
        String id = pidOf(t, token);
        return id == null ? "a passive slot your class doesn't have" : passiveName(t, id);
    }

    static String passiveName(ClassType t, String pid) {
        for (int i = 0; i < t.passives.length; i++) if (t.passives[i].equals(pid)) return t.passiveNames[i];
        return pid;
    }

    // ================================================================ state
    static Map<String, String> parse(String param) {
        Map<String, String> m = new LinkedHashMap<>();
        if (param == null || param.isEmpty()) return m;
        for (String part : param.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(part.substring(0, i), part.substring(i + 1));
        }
        return m;
    }

    private static int toInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static void mul(Map<Integer, Double> m, int n, double f) { m.merge(n, f, (a, b) -> a * b); }

    static State state(Player p) {
        PlayerData d = Aegis.I.pd(p);
        if (d.vowState == null) d.vowState = build(d);
        return d.vowState;
    }

    static void invalidate() {
        for (Player p : Bukkit.getOnlinePlayers()) Aegis.I.pd(p).vowState = null;
    }

    private static State build(PlayerData d) {
        State s = new State();
        ClassType ct = d.type;
        for (Map.Entry<String, String> e : d.vows.entrySet()) {
            Map<String, String> pr = parse(e.getValue());
            switch (e.getKey()) {
                case "cart" -> { s.cart = true; s.heartsHp += heartCost(); }
                case "blood" -> { s.heartsHp += heartCost(); mul(s.cd, 1, cfg("blood-cooldown", 0.75)); mul(s.cd, 2, cfg("blood-cooldown", 0.75)); }
                case "trade" -> {
                    int sac = toInt(pr.getOrDefault("sac", "0"), 0), buff = 3 - sac;
                    s.sacAbilities.add(sac);
                    s.lockedAbilities.add(buff);
                    switch (pr.getOrDefault("kind", "")) {
                        case "damage" -> mul(s.dmg, buff, 2.0);
                        case "duration" -> mul(s.dur, buff, 2.0);
                        case "charge" -> s.charge.put(buff, 0.5);
                        default -> { }
                    }
                }
                case "focus" -> {
                    for (String tok : pr.getOrDefault("p", "").split(",")) {
                        String pid = pidOf(ct, tok);
                        if (pid != null) s.sacPassives.add(pid);
                    }
                    int ab = toInt(pr.getOrDefault("a", "0"), 0);
                    s.lockedAbilities.add(ab);
                    mul(s.cd, ab, cfg("focus-cooldown", 0.6));
                    mul(s.dur, ab, cfg("focus-duration", 1.5));
                }
                case "link" -> {
                    String sp = pidOf(ct, pr.getOrDefault("sac", "")), bp = pidOf(ct, pr.getOrDefault("buff", ""));
                    if (sp != null) s.sacPassives.add(sp);
                    if (bp != null) s.lockedPassives.add(bp);
                }
                case "phoenix" -> {
                    int sac = toInt(pr.getOrDefault("sac", "0"), 0);
                    s.phoenix = true;
                    s.sacAbilities.add(sac);
                    s.lockedAbilities.add(3 - sac);
                }
                default -> { }
            }
        }
        if (ct != null && ct.a2 == null) s.sacAbilities.clear();   // after a reroll into a one-ability class you always keep it
        return s;
    }

    // ================================================================ buffs applied by the rest of the plugin
    /** Called for every ability config read (Abilities.v): applies cooldown, damage, duration and charge vows. */
    static double scale(Player p, int n, String key, double base) {
        if (Aegis.I.pd(p).vows.isEmpty()) return base;
        State s = state(p);
        ClassType ct = Aegis.I.pd(p).type;
        if (ct == ClassType.AGNI && n == 2 && (DAMAGE_KEYS.contains(key) || DURATION_KEYS.contains(key))) return base;   // Fireball: charge-up only
        if (key.equals("cooldown")) return base * Math.max(cfg("min-cooldown-mult", 0.5), s.cd.getOrDefault(n, 1.0));
        if (DAMAGE_KEYS.contains(key)) {
            double m = Math.min(2.0, s.dmg.getOrDefault(n, 1.0));
            if (m <= 1.0 || base >= cap()) return base;
            return Math.min(cap(), base * m);   // a vow never pushes an ability past 5 hearts
        }
        if (DURATION_KEYS.contains(key)) return base * Math.min(2.0, s.dur.getOrDefault(n, 1.0));
        if (key.equals("max-seconds")) return base * s.charge.getOrDefault(n, 1.0);
        return base;
    }

    /** Passive config node, stronger when a Vow of Kinship bound it. */
    static ConfigurationSection scalePassive(Player p, String pid, ConfigurationSection n) {
        if (n == null || Aegis.I.pd(p).vows.isEmpty() || !state(p).lockedPassives.contains(pid)) return n;
        double k = cfg("passive-buff", 1.5);
        MemoryConfiguration c = new MemoryConfiguration();
        for (String key : n.getKeys(false)) {
            Object o = n.get(key);
            Object sc = scaleValue(key, o, k);
            c.set(key, sc != null ? sc : o);
        }
        return c;
    }

    static boolean passiveBuffable(ClassType t, String pid) {
        ConfigurationSection n = Passives.node(t, pid);
        if (n == null) return false;
        for (String key : n.getKeys(false)) if (scaleValue(key, n.get(key), 1.5) != null) return true;
        return false;
    }

    private static Object scaleValue(String key, Object o, double k) {
        if (o instanceof Number num) {
            double v = num.doubleValue();
            if (key.contains("chance")) return Math.min(100.0, v * k);
            if (key.equals("heal-on-hit") || key.startsWith("extra-") || key.equals("arrow-pull")) return v * k;
            if (key.equals("knockback-resistance")) return Math.min(1.0, v * k);
            if ((key.endsWith("bonus") || key.endsWith("multiplier")) && v > 1) return 1 + (v - 1) * k;
            return null;
        }
        if (o instanceof List<?> l && key.contains("effects") && !l.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object x : l) {
                String[] pr = String.valueOf(x).split(":");
                int lvl = pr.length > 1 ? toInt(pr[1], 1) : 1;
                out.add(pr[0] + ":" + Math.min(4, lvl + 1));
            }
            return out;
        }
        return null;
    }

    static void syncHearts(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        NamespacedKey key = new NamespacedKey(Aegis.I, "vow_hearts");
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) if (m.getKey().equals(key)) a.removeModifier(m);
        double hp = Aegis.I.pd(p).vows.isEmpty() ? 0 : state(p).heartsHp;
        if (hp > 0) a.addModifier(new AttributeModifier(key, -hp, AttributeModifier.Operation.ADD_NUMBER));
        if (p.getHealth() > a.getValue()) p.setHealth(a.getValue());
    }

    /** Vow of the Phoenix: survives a killing blow. */
    static boolean tryPhoenix(Player p) {
        if (Aegis.I.pd(p).vows.isEmpty() || !state(p).phoenix) return false;
        PlayerData d = Aegis.I.pd(p);
        if (d.remKey("vow_phoenix") > 0) return false;
        d.startKey("vow_phoenix", cfg("phoenix-cooldown", 900));
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        double max = a == null ? 20 : a.getValue();
        p.setHealth(Math.min(max, cfg("phoenix-hearts", 3) * 2));
        p.setNoDamageTicks(40);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, .5, 1, .5, .5);
        p.getWorld().playSound(p.getLocation(), "item.totem.use", 1f, 1f);
        p.sendMessage(Component.text("Your Vow of the Phoenix saves you!", NamedTextColor.GOLD));
        return true;
    }

    // ================================================================ taking a vow
    private static Integer ability(String s) {
        int n = toInt(s, 0);
        return n == 1 || n == 2 ? n : null;
    }

    private static boolean hasKey(ClassType t, int n, Set<String> keys, boolean belowCap) {
        ConfigurationSection c = Aegis.I.getConfig().getConfigurationSection("classes." + t.id + ".a" + n);
        if (c == null) return false;
        for (String k : keys) if (c.isSet(k) && (!belowCap || c.getDouble(k) < cap())) return true;
        return false;
    }

    private static String checkSacPassive(Player p, State s, String pid) {
        if (!Passives.unlockedRaw(p).contains(pid)) return "'" + pid + "' is not one of your unlocked passives.";
        if (s.sacPassives.contains(pid)) return "That passive is already sacrificed.";
        if (s.lockedPassives.contains(pid)) return "That passive is bound to a vow and can't be sacrificed.";
        return null;
    }

    static Prep prepare(Player p, String id, String[] a) {
        PlayerData d = Aegis.I.pd(p);
        ClassType t = d.type;
        if (t == null || d.rolling) return Prep.err("You need a class first.");
        if (!Arrays.asList(IDS).contains(id)) return Prep.err("Unknown vow. Use /vow list.");
        if (d.vows.containsKey(id)) return Prep.err("You already took the " + name(id) + ".");
        if (d.vows.size() >= (int) cfg("max-vows", 2)) return Prep.err("You can only take " + (int) cfg("max-vows", 2) + " vows.");
        State s = state(p);
        String use = "Usage: " + usage(id);

        switch (id) {
            case "cart", "blood" -> {
                if (s.heartsHp + heartCost() > cfg("max-heart-sacrifice-hp", 8))
                    return Prep.err("You cannot sacrifice more than " + (int) (cfg("max-heart-sacrifice-hp", 8) / 2) + " hearts in total.");
                return Prep.ok("", "You permanently lose " + (int) (heartCost() / 2) + " hearts.");
            }
            case "trade" -> {
                if (t.a2 == null) return Prep.err("You need two abilities for this vow.");
                if (a.length < 2) return Prep.err(use);
                Integer sac = ability(a[0]);
                if (sac == null) return Prep.err(use);
                int buff = 3 - sac;
                if (!s.sacAbilities.isEmpty()) return Prep.err("You already sacrificed an ability.");
                if (s.lockedAbilities.contains(sac)) return Prep.err(t.abilityName(sac) + " is bound to a vow and can't be sacrificed.");
                String kind = a[1].toLowerCase();
                boolean agniFire = t == ClassType.AGNI && buff == 2;
                String what;
                switch (kind) {
                    case "damage" -> {
                        if (agniFire) return Prep.err("Fireball can't get more damage, only a faster charge. Use: charge");
                        if (!hasKey(t, buff, DAMAGE_KEYS, true)) return Prep.err(t.abilityName(buff) + " has no damage that can be doubled (nothing above 5 hearts).");
                        what = "double damage (capped at 5 hearts)";
                    }
                    case "duration" -> {
                        if (agniFire) return Prep.err("Fireball only gets a faster charge. Use: charge");
                        if (!hasKey(t, buff, DURATION_KEYS, false)) return Prep.err(t.abilityName(buff) + " has no duration to double.");
                        what = "double duration";
                    }
                    case "charge" -> {
                        if (!agniFire) return Prep.err("Only Agni's Fireball can charge faster.");
                        what = "half the charge-up time";
                    }
                    default -> { return Prep.err(use); }
                }
                return Prep.ok("sac=" + sac + ";kind=" + kind, "You permanently lose " + t.abilityName(sac) + ". " + t.abilityName(buff) + " gets " + what + ".");
            }
            case "focus" -> {
                if (a.length < 3) return Prep.err(use);
                String p1 = a[0].toLowerCase(), p2 = a[1].toLowerCase();
                Integer ab = ability(a[2]);
                if (ab == null || (ab == 2 && t.a2 == null)) return Prep.err(use);
                if (p1.equals(p2)) return Prep.err("Pick two different passives.");
                if (s.sacAbilities.contains(ab)) return Prep.err("That ability was sacrificed.");
                String e1 = checkSacPassive(p, s, p1);
                if (e1 != null) return Prep.err(e1);
                String e2 = checkSacPassive(p, s, p2);
                if (e2 != null) return Prep.err(e2);
                return Prep.ok("p=" + idx(t, p1) + "," + idx(t, p2) + ";a=" + ab, "You permanently lose " + passiveName(t, p1) + " and " + passiveName(t, p2) + ". " + t.abilityName(ab) + ": " + gain("focus") + ".");
            }
            case "link" -> {
                if (a.length < 2) return Prep.err(use);
                String sac = a[0].toLowerCase(), buff = a[1].toLowerCase();
                if (sac.equals(buff)) return Prep.err("Pick two different passives.");
                String e1 = checkSacPassive(p, s, sac);
                if (e1 != null) return Prep.err(e1);
                if (!Passives.unlockedRaw(p).contains(buff)) return Prep.err("'" + buff + "' is not one of your unlocked passives.");
                if (s.sacPassives.contains(buff)) return Prep.err("That passive was sacrificed.");
                if (!passiveBuffable(t, buff)) return Prep.err(passiveName(t, buff) + " has nothing that can be made stronger.");
                return Prep.ok("sac=" + idx(t, sac) + ";buff=" + idx(t, buff), "You permanently lose " + passiveName(t, sac) + ". " + passiveName(t, buff) + " becomes " + (int) Math.round((cfg("passive-buff", 1.5) - 1) * 100) + "% stronger.");
            }
            case "phoenix" -> {
                if (t.a2 == null) return Prep.err("You need two abilities for this vow.");
                if (a.length < 1) return Prep.err(use);
                Integer sac = ability(a[0]);
                if (sac == null) return Prep.err(use);
                if (!s.sacAbilities.isEmpty()) return Prep.err("You already sacrificed an ability.");
                if (s.lockedAbilities.contains(sac)) return Prep.err(t.abilityName(sac) + " is bound to a vow and can't be sacrificed.");
                return Prep.ok("sac=" + sac, "You permanently lose " + t.abilityName(sac) + ". " + gain("phoenix") + ".");
            }
            default -> { return Prep.err("Unknown vow. Use /vow list."); }
        }
    }

    static void apply(Player p, String id, String param) {
        PlayerData d = Aegis.I.pd(p);
        d.vows.put(id, param);
        d.vowState = null;
        Passives.refresh(p);   // hearts, permanent effects and sacrificed passives
        Aegis.I.saveData();
    }

    /** Human readable line for /vow status. */
    static String describe(Player p, String id, String param) {
        ClassType t = Aegis.I.pd(p).type;
        Map<String, String> m = parse(param);
        if (t == null) return name(id);
        String extra = switch (id) {
            case "trade" -> {
                int sac = toInt(m.getOrDefault("sac", "0"), 0);
                String kind = m.getOrDefault("kind", "");
                yield "lost " + t.abilityName(sac) + ", " + t.abilityName(3 - sac) + ": " + (kind.equals("charge") ? "half charge time" : "double " + kind);
            }
            case "focus" -> {
                StringBuilder sb = new StringBuilder("lost ");
                String[] ps = m.getOrDefault("p", "").split(",");
                for (int i = 0; i < ps.length; i++) sb.append(i == 0 ? "" : " + ").append(pName(t, ps[i]));
                yield sb + ", " + t.abilityName(toInt(m.getOrDefault("a", "0"), 1)) + ": " + gain("focus");
            }
            case "link" -> "lost " + pName(t, m.getOrDefault("sac", "")) + ", " + pName(t, m.getOrDefault("buff", "")) + " is stronger";
            case "phoenix" -> "lost " + t.abilityName(toInt(m.getOrDefault("sac", "0"), 1)) + ", " + gain("phoenix");
            default -> "lost 2 hearts, " + gain(id);
        };
        return name(id) + " - " + extra;
    }
}
