package dev.aegis;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

public final class UtilityListener implements Listener {
    private final Aegis pl = Aegis.I;
    private boolean boosting = false;

    private boolean stunAllowed(Material m) { return pl.getConfig().getStringList("stun.allowed-items").contains(m.name()); }
    private boolean frozen(Player p) { return Status.on(Status.freeze, p); }
    private boolean stunned(Player p) { return Status.on(Status.stun, p); }

    // ------------------------------------------------------------------ lifecycle
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Status.clearMods(p);
        PlayerData d = pl.pd(p);
        d.rolling = false;
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline()) return;
            Passives.refresh(p);
            if (d.type == null && pl.getConfig().getBoolean("general.assign-class-on-first-join", true))
                Roll.start(p, pl.randomClass(null));
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Roll.finish(p);
        Abilities.cleanup(p);
        Status.clear(p);
        pl.saveData();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        PlayerData d = pl.pd(p);
        e.getDrops().removeIf(Items::isTemp);
        if (d.savedOffhand != null) { e.getDrops().add(d.savedOffhand); d.savedOffhand = null; }
        Status.dual.remove(p.getUniqueId());
        Abilities.cleanup(p);
        Status.clear(p);

        int loss = pl.getConfig().getInt("energy.loss-on-death", 1);
        if (loss > 0 && d.energy > 0) d.energy = Math.max(0, d.energy - loss);   // at 0 energy nothing is lost
        Player k = p.getKiller();
        int gain = pl.getConfig().getInt("energy.gain-on-kill", 0);
        if (k != null && gain > 0) {
            PlayerData kd = pl.pd(k);
            kd.energy = Math.min(pl.maxEnergy(), kd.energy + gain);
            Passives.refresh(k);
        }
    }

    /** Azaroth's pet dies -> cooldown. Midas: Bounty multiplier and head drops. */
    @EventHandler
    public void onMobDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (v instanceof Warden w && Pets.isPet(w)) { e.getDrops().clear(); e.setDroppedExp(0); Pets.onPetDeath(w); return; }
        Player k = v.getKiller();
        if (k == null || v instanceof Player || v instanceof ArmorStand) return;

        if (Status.on(Status.bounty, k)) {
            int mul = Math.max(1, (int) pl.getConfig().getDouble("classes.midas.a2.multiplier", 20));
            int maxStacks = Math.max(1, (int) pl.getConfig().getDouble("classes.midas.a2.max-stacks-per-kill", 256));
            List<ItemStack> orig = new ArrayList<>(e.getDrops());
            e.getDrops().clear();
            int count = 0;
            for (ItemStack s : orig) {
                int remaining = s.getAmount() * mul;
                while (remaining > 0 && count < maxStacks) {
                    ItemStack c = s.clone();
                    int n = Math.min(remaining, c.getMaxStackSize());
                    c.setAmount(n);
                    e.getDrops().add(c);
                    remaining -= n;
                    count++;
                }
            }
        }
        ConfigurationSection hn = Passives.first(k, "head-chance");
        if (hn != null && Passives.roll(hn.getDouble("head-chance", 5))) e.getDrops().add(Heads.headFor(v, hn));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> Passives.refresh(p), 2L);
    }

    // ------------------------------------------------------------------ ability input
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        PlayerData d = pl.pd(p);
        if (frozen(p) || stunned(p) || Status.on(Status.dual, p) || d.rolling) { e.setCancelled(true); return; }
        if (d.type == null || d.cmdMode) return;
        e.setCancelled(true);
        Abilities.use(p, p.isSneaking() ? 2 : 1);
    }

    // ------------------------------------------------------------------ interaction
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        if (frozen(p) || stunned(p)) {
            boolean ok = it != null && stunAllowed(it.getType());
            e.setUseInteractedBlock(Event.Result.DENY);
            e.setUseItemInHand(ok ? Event.Result.ALLOW : Event.Result.DENY);
            if (!ok) return;
        }
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (it == null) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;

        String kind = Items.kind(it);
        if (kind != null) { e.setCancelled(true); useItem(p, kind, hand); return; }

        if (Status.on(Status.enchain, p) && it.getType() == Material.WIND_CHARGE) { e.setCancelled(true); return; }
        if (Abilities.consumeOre(p, it, hand)) { e.setCancelled(true); return; }

        if (it.getType().isEdible()) {
            for (String id : Passives.unlocked(p)) {
                ConfigurationSection n = Passives.node(p, id);
                if (n != null && n.getStringList("foods").contains(it.getType().name()) && p.getFoodLevel() >= 20) p.setFoodLevel(19); // lets you eat while full
            }
            ConfigurationSection f = Passives.first(p, "eat-time-multiplier");
            if (f != null) {
                double mult = f.getDouble("eat-time-multiplier", 0.8);
                Material mat = it.getType();
                Bukkit.getScheduler().runTaskLater(pl, () -> {
                    if (!p.isHandRaised()) return;
                    int used = p.getActiveItemUsedTime(), total = used + p.getActiveItemRemainingTime();
                    long wait = Math.max(1, Math.round(total * mult) - used);
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        if (p.isHandRaised() && p.getActiveItem().getType() == mat) p.completeUsingActiveItem();
                    }, wait);
                }, 2L);
            }
        }
    }

    private void useItem(Player p, String kind, EquipmentSlot hand) {
        PlayerData d = pl.pd(p);
        if (d.rolling) return;
        ItemStack cur = p.getInventory().getItem(hand);
        if (cur == null) return;
        if (kind.equals("energy")) {
            if (d.energy >= pl.maxEnergy()) { Abilities.msg(p, "You are already at max energy."); return; }
            d.energy++;
            Passives.refresh(p);
            p.sendMessage(net.kyori.adventure.text.Component.text("Energy: " + d.energy + "/" + pl.maxEnergy(), net.kyori.adventure.text.format.NamedTextColor.AQUA));
            p.playSound(p.getLocation(), "entity.experience_orb.pickup", 1f, 1.2f);
            Fx.energy(p);
        } else {
            Roll.start(p, pl.randomClass(d.type));
        }
        cur.setAmount(cur.getAmount() - 1);
        p.getInventory().setItem(hand, cur.getAmount() <= 0 ? null : cur);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (frozen(e.getPlayer()) || stunned(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        Material m = e.getItem().getType();
        if ((frozen(p) || stunned(p)) && !stunAllowed(m)) { e.setCancelled(true); return; }
        for (String id : Passives.unlocked(p)) {
            ConfigurationSection n = Passives.node(p, id);
            if (n == null || !n.getStringList("foods").contains(m.name())) continue;
            double food = n.getDouble("extra-food", 0), sat = n.getDouble("extra-saturation", 0);
            double absMul = n.getDouble("absorption-multiplier", 1), refund = n.getDouble("refund-chance", 0);
            boolean noHunger = n.getBoolean("remove-hunger", false);
            List<String> fxs = n.getStringList("effects");
            ItemStack back = e.getItem().clone();
            back.setAmount(1);
            boolean give = refund > 0 && Passives.roll(refund);
            Bukkit.getScheduler().runTask(pl, () -> {
                if (!p.isOnline()) return;
                if (noHunger) p.removePotionEffect(org.bukkit.potion.PotionEffectType.HUNGER);
                if (food > 0) p.setFoodLevel(Math.min(20, p.getFoodLevel() + (int) Math.round(food)));
                if (sat > 0) p.setSaturation((float) Math.min(p.getFoodLevel(), p.getSaturation() + sat));
                Abilities.give(p, p, fxs, false);
                if (absMul != 1) p.setAbsorptionAmount(p.getAbsorptionAmount() * absMul);
                if (give) p.getInventory().addItem(back).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
            });
        }
    }

    // ------------------------------------------------------------------ movement
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        boolean fz = frozen(p), st = stunned(p);
        if (fz || st) {
            boolean moved = f.getX() != t.getX() || f.getZ() != t.getZ() || (fz ? f.getY() != t.getY() : t.getY() > f.getY());
            if (moved) {
                Location n = f.clone();
                n.setYaw(t.getYaw());
                n.setPitch(t.getPitch());
                e.setTo(n);
            }
            return;
        }
        if (!Abilities.duels.isEmpty()) {
            for (Abilities.Duel d : Abilities.duels.values()) {
                boolean member = p.getUniqueId().equals(d.caster()) || p.getUniqueId().equals(d.other());
                boolean in = Abilities.inside(d, t);
                if ((member && !in) || (!member && in && !Abilities.inside(d, f))) {
                    Location n = f.clone();
                    n.setYaw(t.getYaw());
                    n.setPitch(t.getPitch());
                    e.setTo(n);
                    return;
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (to == null) return;
        for (Abilities.Duel d : Abilities.duels.values()) {
            boolean member = p.getUniqueId().equals(d.caster()) || p.getUniqueId().equals(d.other());
            boolean in = Abilities.inside(d, to);
            if ((member && !in) || (!member && in)) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onJump(PlayerJumpEvent e) {
        Player p = e.getPlayer();
        if (frozen(p) || stunned(p) || Status.on(Status.enchain, p)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ stun / freeze restrictions
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (frozen(p) || stunned(p) || Items.kind(e.getItemInHand()) != null || Items.isTemp(e.getItemInHand())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (frozen(p) || stunned(p)) { e.setCancelled(true); return; }
        if (Abilities.shell.contains(b.getBlockKey())) { e.setCancelled(true); return; }

        ConfigurationSection n = Passives.first(p, "timber");
        if (n == null || !Tag.LOGS.isTagged(b.getType())) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (n.getBoolean("require-axe", true) && !tool.getType().name().endsWith("_AXE")) return;
        int max = n.getInt("max-blocks", 256);
        Set<Block> seen = new HashSet<>();
        Deque<Block> q = new ArrayDeque<>();
        q.add(b);
        seen.add(b);
        while (!q.isEmpty() && seen.size() < max) {
            Block c = q.poll();
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                Block r = c.getRelative(x, y, z);
                if (seen.size() >= max || seen.contains(r) || !Tag.LOGS.isTagged(r.getType())) continue;
                seen.add(r);
                q.add(r);
            }
        }
        seen.remove(b);
        for (Block r : seen) r.breakNaturally(tool);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropBlock(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        Material src = e.getBlockState().getType();
        ItemStack tool = p.getInventory().getItemInMainHand();

        // Hephaestus / Midas: autosmelt + multiplied ores; Midas: nether gold ore drops ingots
        ConfigurationSection sm = Passives.first(p, "autosmelt");
        ConfigurationSection ng = Passives.first(p, "nether-gold-ingot-amount");
        if (sm != null || ng != null) {
            boolean silk = tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0;
            boolean oreSource = src.name().endsWith("_ORE") || src == Material.ANCIENT_DEBRIS;
            boolean oresOnly = sm == null || sm.getBoolean("autosmelt-ores-only", true);
            int oreMul = sm == null ? 1 : Math.max(1, sm.getInt("ore-multiplier", 1));
            int debris = sm == null ? 1 : Math.max(1, sm.getInt("debris-multiplier", 2));
            for (Item i : e.getItems()) {
                ItemStack s = i.getItemStack();
                if (ng != null && src == Material.NETHER_GOLD_ORE && s.getType() == Material.GOLD_NUGGET)
                    s = new ItemStack(Material.GOLD_INGOT, Math.max(1, ng.getInt("nether-gold-ingot-amount", 1)));
                if (sm != null && !silk && (!oresOnly || oreSource)) {
                    Material r = Smelting.result(s.getType());
                    if (r != null) s = s.withType(r);
                }
                int mul = src == Material.ANCIENT_DEBRIS ? debris : (oreSource && !silk ? oreMul : 1);
                if (mul > 1) s.setAmount(Math.min(s.getMaxStackSize(), s.getAmount() * mul));
                i.setItemStack(s);
            }
        }

        // Dolus: fortune doubles ore drops
        ConfigurationSection n = Passives.first(p, "fortune-multiplier");
        if (n == null) return;
        if (tool.getEnchantmentLevel(Enchantment.FORTUNE) <= 0) return;
        if (!src.name().endsWith("_ORE")) return;
        int mul = Math.max(1, n.getInt("fortune-multiplier", 2));
        for (Item i : e.getItems()) {
            ItemStack s = i.getItemStack();
            s.setAmount(Math.min(s.getMaxStackSize(), s.getAmount() * mul));
            i.setItemStack(s);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (frozen(p) || stunned(p) || Items.isTemp(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (Items.isTemp(e.getCurrentItem()) || Items.isTemp(e.getCursor())) { e.setCancelled(true); return; }
        if (Status.on(Status.dual, p)) {
            if (e.getClick() == ClickType.SWAP_OFFHAND) { e.setCancelled(true); return; }
            if (e.getClickedInventory() instanceof PlayerInventory && e.getSlot() == 40) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        Projectile pr = e.getEntity();
        if ((frozen(p) || stunned(p)) && !(pr instanceof ThrownPotion)) { e.setCancelled(true); return; }
        if (pr.getType() != EntityType.WIND_CHARGE) return;
        if (Status.on(Status.enchain, p)) { e.setCancelled(true); return; }

        if (Passives.first(p, "wind-charge-straight") != null) {
            pr.setGravity(false);
            pr.setVelocity(p.getEyeLocation().getDirection().normalize().multiply(pr.getVelocity().length()));
        }
        ConfigurationSection r = Passives.first(p, "wind-charge-refund");
        if (r != null && p.getGameMode() != org.bukkit.GameMode.CREATIVE && Passives.roll(r.getDouble("wind-charge-refund", 50)))
            Bukkit.getScheduler().runTask(pl, () -> p.getInventory().addItem(new ItemStack(Material.WIND_CHARGE)));
    }

    /** Boreus: wind charges are 1.5x as effective - boost the knockback they just applied. */
    @EventHandler
    public void onWindHit(ProjectileHitEvent e) {
        if (e.getEntity().getType() != EntityType.WIND_CHARGE || !(e.getEntity().getShooter() instanceof Player p)) return;
        ConfigurationSection n = Passives.first(p, "wind-charge-multiplier");
        if (n == null) return;
        double mul = n.getDouble("wind-charge-multiplier", 1.5);
        Location at = e.getEntity().getLocation();
        Bukkit.getScheduler().runTask(pl, () -> {
            for (Entity en : at.getWorld().getNearbyEntities(at, 3.5, 3.5, 3.5))
                if (en instanceof LivingEntity && !(en instanceof ArmorStand)) en.setVelocity(en.getVelocity().multiply(mul));
        });
    }

    /** Boreus does not trigger sculk sensors / shriekers. */
    @EventHandler(ignoreCancelled = true)
    public void onGameEvent(GenericGameEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (Passives.first(p, "sculk-silent") != null) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ passives that hook other events
    @EventHandler(ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getNewEffect() == null) return;
        String key = e.getNewEffect().getType().getKey().getKey();
        for (String id : Passives.unlocked(p)) {
            ConfigurationSection n = Passives.node(p, id);
            if (n != null && n.getStringList("immune-effects").contains(key)) { e.setCancelled(true); return; }
        }
        // Chrono: timed buffs last longer
        ConfigurationSection b = Passives.first(p, "buff-duration-multiplier");
        if (b == null || boosting) return;
        PotionEffect ne = e.getNewEffect();
        if (e.getAction() != EntityPotionEffectEvent.Action.ADDED && e.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;
        if (ne.getType().getEffectCategory() != PotionEffectType.Category.BENEFICIAL || ne.isInfinite() || ne.getDuration() <= 0) return;
        e.setCancelled(true);
        boosting = true;
        try {
            p.addPotionEffect(new PotionEffect(ne.getType(), (int) Math.round(ne.getDuration() * b.getDouble("buff-duration-multiplier", 1.5)),
                    ne.getAmplifier(), ne.isAmbient(), ne.hasParticles(), ne.hasIcon()));
        } finally { boosting = false; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        Player p = e.getPlayer();
        Material m = e.getItem().getType();
        for (String id : Passives.unlocked(p)) {
            ConfigurationSection n = Passives.node(p, id);
            if (n == null) continue;
            if (n.getBoolean("no-shield-durability", false) && m == Material.SHIELD) { e.setCancelled(true); return; }
            if (n.contains("armor-durability-multiplier") && Gear.isArmor(m)) {
                double x = e.getDamage() * n.getDouble("armor-durability-multiplier", 0.9);
                int base = (int) x;
                if (new Random().nextDouble() < x - base) base++;
                e.setDamage(base);
            }
        }
    }
}
