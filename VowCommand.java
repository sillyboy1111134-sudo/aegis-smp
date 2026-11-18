package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
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

/** /vow list | info <vow> | take <vow> [args] | confirm | cancel | status */
final class VowCommand implements CommandExecutor, TabCompleter {
    private record Pending(String id, String[] args, long expires) { }

    private final Map<UUID, Pending> pending = new HashMap<>();

    private static void say(Player p, String s, NamedTextColor c) { p.sendMessage(Component.text(s, c)); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        if (!Aegis.I.getConfig().getBoolean("vows.enabled", true)) { say(p, "Binding vows are disabled.", NamedTextColor.RED); return true; }
        String sub = a.length == 0 ? "list" : a[0].toLowerCase();
        switch (sub) {
            case "list" -> list(p);
            case "status" -> status(p);
            case "info" -> {
                if (a.length < 2) say(p, "/vow info <vow>", NamedTextColor.RED);
                else info(p, a[1].toLowerCase());
            }
            case "take" -> take(p, a);
            case "confirm" -> confirm(p);
            case "cancel" -> { pending.remove(p.getUniqueId()); say(p, "Cancelled.", NamedTextColor.GRAY); }
            default -> say(p, "/vow <list|info|take|confirm|cancel|status>", NamedTextColor.RED);
        }
        return true;
    }

    private void list(Player p) {
        say(p, "=== Binding Vows (permanent, cannot be undone) ===", NamedTextColor.GOLD);
        for (String id : Vows.IDS) {
            p.sendMessage(Component.text(Vows.name(id), NamedTextColor.YELLOW, TextDecoration.BOLD)
                    .append(Component.text("  costs " + Vows.cost(id) + " -> " + Vows.gain(id), NamedTextColor.AQUA)));
            say(p, "  " + Vows.usage(id), NamedTextColor.DARK_GRAY);
        }
        say(p, "Binding: a vow can never be taken back, and anything a vow touches is locked in. They stay when you reroll your class.", NamedTextColor.GRAY);
    }

    private void status(Player p) {
        PlayerData d = Aegis.I.pd(p);
        if (d.vows.isEmpty()) { say(p, "You have not taken any binding vows.", NamedTextColor.GRAY); return; }
        say(p, "Your binding vows (" + d.vows.size() + "/" + (int) Vows.cfg("max-vows", 2) + "):", NamedTextColor.GOLD);
        for (Map.Entry<String, String> e : d.vows.entrySet()) say(p, "- " + Vows.describe(p, e.getKey(), e.getValue()), NamedTextColor.AQUA);
    }

    private void info(Player p, String id) {
        if (!Arrays.asList(Vows.IDS).contains(id)) { say(p, "Unknown vow. Use /vow list.", NamedTextColor.RED); return; }
        say(p, Vows.name(id), NamedTextColor.GOLD);
        say(p, "Cost: " + Vows.cost(id), NamedTextColor.RED);
        say(p, "Gain: " + Vows.gain(id), NamedTextColor.GREEN);
        say(p, Vows.usage(id), NamedTextColor.GRAY);
    }

    private void take(Player p, String[] a) {
        if (a.length < 2) { say(p, "/vow take <vow> ...   (see /vow list)", NamedTextColor.RED); return; }
        String id = a[1].toLowerCase();
        String[] args = Arrays.copyOfRange(a, 2, a.length);
        Vows.Prep prep = Vows.prepare(p, id, args);
        if (prep.error != null) { say(p, prep.error, NamedTextColor.RED); return; }
        pending.put(p.getUniqueId(), new Pending(id, args, System.currentTimeMillis() + 30_000));
        p.sendMessage(Component.text("BINDING VOW: " + Vows.name(id), NamedTextColor.DARK_RED, TextDecoration.BOLD));
        say(p, prep.summary, NamedTextColor.YELLOW);
        say(p, "This can NEVER be undone. Type /vow confirm within 30 seconds, or /vow cancel.", NamedTextColor.RED);
    }

    private void confirm(Player p) {
        Pending pe = pending.remove(p.getUniqueId());
        if (pe == null || System.currentTimeMillis() > pe.expires()) { say(p, "Nothing to confirm. Use /vow take first.", NamedTextColor.RED); return; }
        Vows.Prep prep = Vows.prepare(p, pe.id(), pe.args());   // check again, things may have changed
        if (prep.error != null) { say(p, prep.error, NamedTextColor.RED); return; }
        Vows.apply(p, pe.id(), prep.param);
        p.showTitle(Title.title(Component.text("VOW SWORN", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text(Vows.name(pe.id()), NamedTextColor.GOLD), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        p.getWorld().playSound(p.getLocation(), "block.beacon.deactivate", 1f, 0.6f);
        say(p, prep.summary, NamedTextColor.YELLOW);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!(s instanceof Player p)) return out;
        String cur = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        if (a.length == 1) out.addAll(List.of("list", "info", "take", "confirm", "cancel", "status"));
        else if (a[0].equalsIgnoreCase("info") && a.length == 2) out.addAll(Arrays.asList(Vows.IDS));
        else if (a[0].equalsIgnoreCase("take")) {
            if (a.length == 2) out.addAll(Arrays.asList(Vows.IDS));
            else {
                String id = a[1].toLowerCase();
                int pos = a.length - 2;   // 1-based position of the argument being typed
                switch (id) {
                    case "trade" -> { if (pos == 1) out.addAll(List.of("1", "2")); else if (pos == 2) out.addAll(List.of("damage", "duration", "charge")); }
                    case "focus" -> { if (pos <= 2) out.addAll(Passives.unlockedRaw(p)); else if (pos == 3) out.addAll(List.of("1", "2")); }
                    case "link" -> { if (pos <= 2) out.addAll(Passives.unlockedRaw(p)); }
                    case "phoenix" -> { if (pos == 1) out.addAll(List.of("1", "2")); }
                    default -> { }
                }
            }
        }
        out.removeIf(x -> !x.toLowerCase().startsWith(cur));
        return out;
    }
}
