package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

final class Commands implements CommandExecutor, TabCompleter {
    private final Aegis pl = Aegis.I;

    private void say(CommandSender s, String m, NamedTextColor c) { s.sendMessage(Component.text(m, c)); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        switch (c.getName().toLowerCase()) {
            case "ability": return ability(s, a);
            case "trust": return trust(s, a);
            case "aegis": return admin(s, a);
            case "myclass": return myclass(s);
            default: return false;
        }
    }

    // ------------------------------------------------------------------ /ability
    private boolean ability(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { say(s, "Players only.", NamedTextColor.RED); return true; }
        PlayerData d = pl.pd(p);
        if (a.length == 0) { say(p, "Usage: /ability <1|2|mode>", NamedTextColor.RED); return true; }
        if (a[0].equalsIgnoreCase("mode")) {
            d.cmdMode = !d.cmdMode;
            say(p, d.cmdMode ? "Command mode ON - use /ability 1 and /ability 2. F / Shift+F no longer activate abilities."
                             : "Command mode OFF - F is Ability 1, Shift+F is Ability 2.", NamedTextColor.GOLD);
            return true;
        }
        if (!d.cmdMode) { say(p, "Command mode is off. Run /ability mode first (or use F / Shift+F).", NamedTextColor.RED); return true; }
        if (a[0].equals("1") || a[0].equals("2")) Abilities.use(p, Integer.parseInt(a[0]));
        else say(p, "Usage: /ability <1|2|mode>", NamedTextColor.RED);
        return true;
    }

    // ------------------------------------------------------------------ /trust
    private boolean trust(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { say(s, "Players only.", NamedTextColor.RED); return true; }
        PlayerData d = pl.pd(p);
        if (a.length == 0) { say(p, "Usage: /trust <add|remove|list> [player]", NamedTextColor.RED); return true; }
        if (a[0].equalsIgnoreCase("list")) {
            String names = d.trusted.stream().map(u -> {
                String n = Bukkit.getOfflinePlayer(u).getName();
                return n == null ? u.toString() : n;
            }).collect(Collectors.joining(", "));
            say(p, "Trusted: " + (names.isEmpty() ? "nobody" : names), NamedTextColor.GOLD);
            return true;
        }
        if (a.length < 2) { say(p, "Usage: /trust <add|remove> <player>", NamedTextColor.RED); return true; }
        OfflinePlayer t = Bukkit.getPlayerExact(a[1]);
        if (t == null) t = Bukkit.getOfflinePlayer(a[1]);
        if (t.getUniqueId().equals(p.getUniqueId())) { say(p, "You can't trust yourself.", NamedTextColor.RED); return true; }
        if (!t.hasPlayedBefore() && !t.isOnline()) { say(p, "Unknown player.", NamedTextColor.RED); return true; }
        if (a[0].equalsIgnoreCase("add")) {
            d.trusted.add(t.getUniqueId());
            say(p, "You now trust " + t.getName() + ". Your abilities and passives won't affect them.", NamedTextColor.GREEN);
            if (t.getPlayer() != null) say(t.getPlayer(), p.getName() + " trusts you.", NamedTextColor.GREEN);
        } else if (a[0].equalsIgnoreCase("remove")) {
            d.trusted.remove(t.getUniqueId());
            say(p, "You no longer trust " + t.getName() + ".", NamedTextColor.YELLOW);
        } else say(p, "Usage: /trust <add|remove|list> [player]", NamedTextColor.RED);
        return true;
    }

    // ------------------------------------------------------------------ /myclass
    private boolean myclass(CommandSender s) {
        if (!(s instanceof Player p)) { say(s, "Players only.", NamedTextColor.RED); return true; }
        PlayerData d = pl.pd(p);
        if (d.rolling) { say(p, "Rolling...", NamedTextColor.GOLD); return true; }
        if (d.type == null) { say(p, "You have no class yet.", NamedTextColor.RED); return true; }
        ClassType t = d.type;
        say(p, t.display + "  \u26A1 " + d.energy + "/" + pl.maxEnergy(), NamedTextColor.GOLD);
        say(p, "Ability 1: " + t.a1 + (t.a2 == null ? "" : "   Ability 2: " + t.a2), NamedTextColor.YELLOW);
        for (int i = 0; i < t.passives.length; i++) {
            int need = Passives.unlockAt(t, i);
            boolean on = d.energy >= need;
            say(p, (on ? "\u2714 " : "\u2716 ") + t.passiveNames[i] + (on ? "" : "  (needs " + need + " energy)"),
                    on ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY);
        }
        return true;
    }

    // ------------------------------------------------------------------ /aegis (admin)
    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("aegis.admin")) { say(s, "No permission.", NamedTextColor.RED); return true; }
        if (a.length == 0) { say(s, "/aegis <give|setclass|setenergy|reload>", NamedTextColor.RED); return true; }
        switch (a[0].toLowerCase()) {
            case "reload" -> { pl.reloadConfig(); Recipes.register(); say(s, "Config reloaded.", NamedTextColor.GREEN); }
            case "give" -> {
                if (a.length < 3) { say(s, "/aegis give <player> <marble|reroller> [amount]", NamedTextColor.RED); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { say(s, "Player not online.", NamedTextColor.RED); return true; }
                String kind = a[2].equalsIgnoreCase("reroller") || a[2].equalsIgnoreCase("reroll") ? "reroll" : "energy";
                int amt = a.length > 3 ? Math.max(1, Integer.parseInt(a[3])) : 1;
                t.getInventory().addItem(Items.make(kind, amt)).values().forEach(x -> t.getWorld().dropItemNaturally(t.getLocation(), x));
                say(s, "Gave " + amt + " " + (kind.equals("energy") ? "Energy Marble(s)" : "Class Reroller(s)") + " to " + t.getName(), NamedTextColor.GREEN);
            }
            case "setclass" -> {
                if (a.length < 3) { say(s, "/aegis setclass <player> <class>", NamedTextColor.RED); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                ClassType ct = ClassType.byId(a[2]);
                if (t == null || ct == null) { say(s, "Unknown player or class.", NamedTextColor.RED); return true; }
                PlayerData d = pl.pd(t);
                Abilities.cleanup(t);
                Status.clear(t);
                d.cd.clear();
                d.type = ct;
                Passives.refresh(t);
                say(s, t.getName() + " is now " + ct.display, NamedTextColor.GREEN);
            }
            case "setenergy" -> {
                if (a.length < 3) { say(s, "/aegis setenergy <player> <0-5>", NamedTextColor.RED); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { say(s, "Player not online.", NamedTextColor.RED); return true; }
                pl.pd(t).energy = Math.max(0, Math.min(pl.maxEnergy(), Integer.parseInt(a[2])));
                Passives.refresh(t);
                say(s, t.getName() + " energy = " + pl.pd(t).energy, NamedTextColor.GREEN);
            }
            default -> say(s, "/aegis <give|setclass|setenergy|reload>", NamedTextColor.RED);
        }
        return true;
    }

    // ------------------------------------------------------------------ tab complete
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String name = c.getName().toLowerCase();
        if (name.equals("ability") && a.length == 1) out.addAll(List.of("1", "2", "mode"));
        else if (name.equals("trust")) {
            if (a.length == 1) out.addAll(List.of("add", "remove", "list"));
            else if (a.length == 2) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (name.equals("aegis")) {
            if (a.length == 1) out.addAll(List.of("give", "setclass", "setenergy", "reload"));
            else if (a.length == 2) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            else if (a.length == 3 && a[0].equalsIgnoreCase("give")) out.addAll(List.of("marble", "reroller"));
            else if (a.length == 3 && a[0].equalsIgnoreCase("setclass")) for (ClassType t : ClassType.values()) out.add(t.id);
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        return out.stream().filter(x -> x.toLowerCase().startsWith(last)).collect(Collectors.toList());
    }
}
