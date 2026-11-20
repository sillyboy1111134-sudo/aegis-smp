package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /ult list | info <ult> | take <ult> <slot> <passive> <passive> | confirm | cancel | status   (admin: remove <player>) */
final class UltCommand implements CommandExecutor, TabCompleter {
    private record Pending(String id, String[] args, long expires) { }

    private final Map<UUID, Pending> pending = new HashMap<>();

    private static void say(CommandSender p, String s, NamedTextColor c) { p.sendMessage(Component.text(s, c)); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("remove")) { remove(s, a); return true; }
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        String sub = a.length == 0 ? "list" : a[0].toLowerCase();
        switch (sub) {
            case "list" -> list(p);
            case "status" -> status(p);
            case "info" -> { if (a.length < 2) say(p, "/ult info <ultimate>", NamedTextColor.RED); else info(p, a[1].toLowerCase()); }
            case "take" -> take(p, a);
            case "confirm" -> confirm(p);
            case "cancel" -> { pending.remove(p.getUniqueId()); say(p, "Cancelled.", NamedTextColor.GRAY); }
            default -> say(p, "/ult <list|info|take|confirm|cancel|status>", NamedTextColor.RED);
        }
        return true;
    }

    private void remove(CommandSender s, String[] a) {
        if (!s.hasPermission("aegis.admin")) { say(s, "No permission.", NamedTextColor.RED); return; }
        if (a.length < 2) { say(s, "/ult remove <player>", NamedTextColor.RED); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { say(s, "Player not online.", NamedTextColor.RED); return; }
        if (Aegis.I.pd(t).ult == null) { say(s, t.getName() + " has no ultimate.", NamedTextColor.RED); return; }
        Ults.remove(t);
        say(s, "Removed " + t.getName() + "'s ultimate. Their ability and passives are back.", NamedTextColor.GREEN);
        say(t, "An admin removed your ultimate.", NamedTextColor.GRAY);
    }

    private void list(Player p) {
        say(p, "=== Ultimates ===", NamedTextColor.GOLD);
        say(p, "At max energy, give up 1 ability (the last one) and 2 passives for an ultimate from ANY class. Below max energy your ability is back but the passives stay lost. Ultimates and binding vows exclude each other.", NamedTextColor.GRAY);
        if (Ults.REGISTRY.isEmpty()) { say(p, "No ultimates are available yet.", NamedTextColor.YELLOW); return; }
        for (Ults.Def d : Ults.REGISTRY.values()) {
            p.sendMessage(Component.text(d.name(), NamedTextColor.YELLOW, TextDecoration.BOLD).append(Component.text("  " + d.desc(), NamedTextColor.AQUA)));
            say(p, "  /ult take " + d.id() + " <passive> <passive>", NamedTextColor.DARK_GRAY);
        }
    }

    private void status(Player p) {
        PlayerData d = Aegis.I.pd(p);
        if (d.ult == null) { say(p, "You have no ultimate.", NamedTextColor.GRAY); return; }
        Ults.Def def = Ults.REGISTRY.get(d.ult);
        ClassType t = d.type;
        say(p, "Ultimate: " + (def == null ? d.ult : def.name()), NamedTextColor.GOLD);
        if (t != null) say(p, "Replaced: " + t.abilityName(d.ultSlot) + " | lost passives: " + String.join(", ", Ults.sacPassives(p).stream().map(id -> Vows.passiveName(t, id)).toList()), NamedTextColor.AQUA);
    }

    private void info(Player p, String id) {
        Ults.Def d = Ults.REGISTRY.get(id);
        if (d == null) { say(p, "Unknown ultimate. Use /ult list.", NamedTextColor.RED); return; }
        say(p, d.name(), NamedTextColor.GOLD);
        say(p, d.desc(), NamedTextColor.AQUA);
        say(p, "Cooldown: " + (int) Ults.cooldown(d) + "s", NamedTextColor.GRAY);
        say(p, Ults.usage(), NamedTextColor.DARK_GRAY);
    }

    private void take(Player p, String[] a) {
        if (a.length < 2) { say(p, Ults.usage(), NamedTextColor.RED); return; }
        String id = a[1].toLowerCase();
        String[] args = Arrays.copyOfRange(a, 2, a.length);
        Vows.Prep prep = Ults.prepare(p, id, args);
        if (prep.error != null) { say(p, prep.error, NamedTextColor.RED); return; }
        pending.put(p.getUniqueId(), new Pending(id, args, System.currentTimeMillis() + 30_000));
        p.sendMessage(Component.text("CHOOSE ULTIMATE: " + Ults.REGISTRY.get(id).name(), NamedTextColor.DARK_RED, TextDecoration.BOLD));
        say(p, prep.summary, NamedTextColor.YELLOW);
        say(p, "Type /ult confirm within 30 seconds, or /ult cancel.", NamedTextColor.RED);
    }

    private void confirm(Player p) {
        Pending pe = pending.remove(p.getUniqueId());
        if (pe == null || System.currentTimeMillis() > pe.expires()) { say(p, "Nothing to confirm. Use /ult take first.", NamedTextColor.RED); return; }
        Vows.Prep prep = Ults.prepare(p, pe.id(), pe.args());
        if (prep.error != null) { say(p, prep.error, NamedTextColor.RED); return; }
        Ults.apply(p, pe.id(), prep.param);
        p.showTitle(Title.title(Component.text("ULTIMATE", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text(Ults.REGISTRY.get(pe.id()).name(), NamedTextColor.GOLD), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        p.getWorld().playSound(p.getLocation(), "block.beacon.activate", 1f, 0.7f);
        say(p, prep.summary, NamedTextColor.YELLOW);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String cur = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        if (a.length >= 1 && a[0].equalsIgnoreCase("remove") && s.hasPermission("aegis.admin")) {
            if (a.length == 2) for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName());
        } else if (s instanceof Player p) {
            if (a.length == 1) { out.addAll(List.of("list", "info", "take", "confirm", "cancel", "status")); if (s.hasPermission("aegis.admin")) out.add("remove"); }
            else if (a[0].equalsIgnoreCase("info") && a.length == 2) out.addAll(Ults.REGISTRY.keySet());
            else if (a[0].equalsIgnoreCase("take")) {
                if (a.length == 2) out.addAll(Ults.REGISTRY.keySet());
                else if (a.length <= 4) out.addAll(Passives.unlockedRaw(p));
            }
        }
        out.removeIf(x -> !x.toLowerCase().startsWith(cur));
        return out;
    }
}
