package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /ult [info|list|status]   (admin: /ult grant <player>, /ult remove <player>). Ultimates are awakened with an Awakening Stone. */
final class UltCommand implements CommandExecutor, TabCompleter {

    private static void say(CommandSender p, String s, NamedTextColor c) { p.sendMessage(Component.text(s, c)); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length >= 1 && (a[0].equalsIgnoreCase("remove") || a[0].equalsIgnoreCase("grant"))) { admin(s, a); return true; }
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase();
        switch (sub) {
            case "status", "info" -> status(p);
            case "list" -> list(p);
            default -> say(p, "/ult <status|list>", NamedTextColor.RED);
        }
        return true;
    }

    private void admin(CommandSender s, String[] a) {
        if (!s.hasPermission("aegis.admin")) { say(s, "No permission.", NamedTextColor.RED); return; }
        boolean grant = a[0].equalsIgnoreCase("grant");
        if (a.length < 2) { say(s, "/ult " + a[0].toLowerCase() + " <player>", NamedTextColor.RED); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { say(s, "Player not online.", NamedTextColor.RED); return; }
        PlayerData d = Aegis.I.pd(t);
        if (grant) {
            if (d.type == null) { say(s, t.getName() + " has no class yet.", NamedTextColor.RED); return; }
            Ults.grant(t);
            say(s, "Awakened " + t.getName() + "'s ultimate.", NamedTextColor.GREEN);
        } else {
            if (!d.awakened) { say(s, t.getName() + " has not awakened an ultimate.", NamedTextColor.RED); return; }
            Ults.remove(t);
            say(s, "Removed " + t.getName() + "'s awakening.", NamedTextColor.GREEN);
            say(t, "An admin removed your awakened ultimate.", NamedTextColor.GRAY);
        }
    }

    private void status(Player p) {
        PlayerData d = Aegis.I.pd(p);
        ClassType t = d.type;
        if (t == null) { say(p, "You have no class yet.", NamedTextColor.GRAY); return; }
        Ults.Def def = Ults.of(t);
        if (def == null) { say(p, "Your class has no ultimate.", NamedTextColor.GRAY); return; }
        p.sendMessage(Component.text(t.display + " ultimate: ", NamedTextColor.GOLD).append(Component.text(def.name(), NamedTextColor.YELLOW, TextDecoration.BOLD)));
        say(p, def.desc(), NamedTextColor.AQUA);
        say(p, "Cooldown: " + (int) Ults.cooldown(def) + "s", NamedTextColor.GRAY);
        int slot = Ults.slotFor(t, def);
        say(p, def.passive() ? "Passive: it triggers by itself at max energy and replaces no ability."
                : (t == ClassType.DOLUS ? "At max energy it becomes your second ability." : "At max energy it replaces " + t.abilityName(slot) + "."), NamedTextColor.GRAY);
        if (!d.awakened) say(p, "Not awakened yet: use an Awakening Stone at " + Aegis.I.maxEnergy() + " energy.", NamedTextColor.RED);
        else if (d.energy < Aegis.I.maxEnergy()) say(p, "Awakened, but asleep until you are back at " + Aegis.I.maxEnergy() + " energy.", NamedTextColor.YELLOW);
        else say(p, "Awakened and ready.", NamedTextColor.GREEN);
    }

    private void list(Player p) {
        say(p, "=== Ultimates (one per class) ===", NamedTextColor.GOLD);
        say(p, "Use an Awakening Stone at max energy to awaken the ultimate of your class. It costs no passives and follows you when you change class.", NamedTextColor.GRAY);
        for (ClassType t : ClassType.values()) {
            Ults.Def d = Ults.of(t);
            if (d == null) continue;
            p.sendMessage(Component.text(t.display + " - " + d.name(), NamedTextColor.YELLOW, TextDecoration.BOLD).append(Component.text("  " + d.desc(), NamedTextColor.AQUA)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String cur = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        if (a.length == 1) { out.addAll(List.of("status", "list")); if (s.hasPermission("aegis.admin")) out.addAll(List.of("grant", "remove")); }
        else if (a.length == 2 && s.hasPermission("aegis.admin") && (a[0].equalsIgnoreCase("grant") || a[0].equalsIgnoreCase("remove")))
            for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName());
        out.removeIf(x -> !x.toLowerCase().startsWith(cur));
        return out;
    }
}
