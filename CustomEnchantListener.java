package dev.aegis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public final class CustomEnchantListener implements Listener {
    private final Aegis pl = Aegis.I;
    static boolean nested = false;     // true while an enchant is breaking extra blocks (stops chain reactions)

    private static final Map<UUID, Long> grappleCd = new HashMap<>();
    private static final Map<UUID, Long> chargeCd = new HashMap<>();
    private static final Map<UUID, Long> immortalCd = new HashMap<>();

    private final NamespacedKey arrowMight = new NamespacedKey(Aegis.I, "arrow_might");
    private final NamespacedKey arrowReel = new NamespacedKey(Aegis.I, "arrow_reel");
    private final NamespacedKey excavationOff = new NamespacedKey(Aegis.I, "excavation_off");

    static double cfgD(String path, double def) { return Aegis.I.getConfig().getDouble("custom-enchants." + path, def); }

    private static boolean roll(double percent) { return ThreadLocalRandom.current().nextDouble() * 100.0 < percent; }

    private static double secondsLeft(Map<UUID, Long> m, Player p) {
        Long t = m.get(p.getUniqueId());
        return t == null ? 0 : Math.max(0, (t - System.currentTimeMillis()) / 1000.0);
    }

    // ================================================================ enchanting table (the ONLY source)
    @EventHandler(ignoreCancelled = true)
    public void onTable(EnchantItemEvent e) {
        ItemStack item = e.getItem();
        if (item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK) return;
        int cost = e.getExpLevelCost();
        double chance = Math.min(cfgD("table.max-chance", 85), cfgD("table.base-chance", 10) + cfgD("table.chance-per-level", 2.0) * cost);
        double second = cfgD("table.second-roll-multiplier", 0.4);
        boolean quick = e.getEnchantsToAdd().containsKey(Enchantment.QUICK_CHARGE) || item.getEnchantmentLevel(Enchantment.QUICK_CHARGE) > 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<String> got = new ArrayList<>();
        for (int roll = 0; roll < 2; roll++) {
            Map<String, Integer> have = CE.read(item);
            if (have.size() >= CE.maxPerItem()) break;
            if (r.nextDouble() * 100 >= (roll == 0 ? chance : chance * second)) continue;
            List<CE.Def> pool = new ArrayList<>();
            for (CE.Def d : CE.DEFS) {
                if (!CE.enabled(d) || have.containsKey(d.id()) || !CE.fits(d, item.getType())) continue;
                if (d.id().equals("reel") && quick) continue;           // Reel does not work with Quick Charge
                pool.add(d);
            }
            if (pool.isEmpty()) break;
            CE.Def d = pool.get(r.nextInt(pool.size()));
            int max = CE.max(d);
            int lvl = Math.max(1, Math.min(max, 1 + (int) Math.round(r.nextDouble() * (max - 1) * Math.min(1.0, cost / 30.0))));
            if (CE.add(item, d.id(), lvl)) got.add(CE.name(d) + (max > 1 ? " " + CE.roman(lvl) : ""));
        }
        if (!got.isEmpty())
            e.getEnchanter().sendMessage(Component.text("Custom enchant: " + String.join(", ", got), NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler
    public void onGrindstone(PrepareGrindstoneEvent e) {
        ItemStack r = e.getResult();
        if (r == null || CE.read(r).isEmpty()) return;
        ItemStack c = r.clone();
        CE.strip(c);
        e.setResult(c);
    }

    // ================================================================ bows & crossbows
    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack bow = e.getBow();
        if (bow == null) return;
        Map<String, Integer> m = CE.read(bow);
        if (m.isEmpty()) return;
        Entity proj = e.getProjectile();
        boolean quick = bow.getEnchantmentLevel(Enchantment.QUICK_CHARGE) > 0;

        if (m.containsKey("ember") && !(proj instanceof Firework)) proj.setFireTicks((int) cfgD("ember.fire-ticks", 1200));
        if (m.containsKey("might") && proj instanceof AbstractArrow)
            proj.getPersistentDataContainer().set(arrowMight, PersistentDataType.INTEGER, m.get("might"));
        if (m.containsKey("reel") && !quick && proj instanceof AbstractArrow)
            proj.getPersistentDataContainer().set(arrowReel, PersistentDataType.INTEGER, m.get("reel"));

        if (m.containsKey("tri_shot") && bow.getType() == Material.BOW && proj instanceof AbstractArrow a) {
            double deg = cfgD("tri_shot.angle", 10);
            for (int side : new int[]{-1, 1}) {
                Vector v = a.getVelocity().clone().rotateAroundY(Math.toRadians(deg * side));
                Arrow extra = p.launchProjectile(Arrow.class, v);
                extra.setCritical(a.isCritical());
                extra.setFireTicks(a.getFireTicks());
                extra.setDamage(a.getDamage());
                extra.setPierceLevel(a.getPierceLevel());
                extra.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);   // no free arrows
            }
        }
    }

    /** Might = Power for crossbows (+25% x (level+1), like Power). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow a)) return;
        Integer lv = a.getPersistentDataContainer().get(arrowMight, PersistentDataType.INTEGER);
        if (lv == null) return;
        e.setDamage(e.getDamage() * (1 + cfgD("might.per-level", 0.25) * (lv + 1)));
    }

    /** Reel: pulls the target toward the shooter. */
    @EventHandler
    public void onArrowHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow a) || !(e.getHitEntity() instanceof LivingEntity t)) return;
        Integer lv = a.getPersistentDataContainer().get(arrowReel, PersistentDataType.INTEGER);
        if (lv == null || !(a.getShooter() instanceof Player p) || t.equals(p) || Abilities.trusts(p, t)) return;
        Abilities.pull(t, p.getLocation(), cfgD("reel.pull-per-level", 1.0) * lv);
        Fx.part(t.getLocation().add(0, 1, 0), org.bukkit.Particle.ENCHANTED_HIT, 20, .3, .4, .3, .1);
    }

    // ================================================================ fishing rod: Grapple
    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.REEL_IN) return;
        Player p = e.getPlayer();
        ItemStack rod = p.getInventory().getItemInMainHand().getType() == Material.FISHING_ROD ? p.getInventory().getItemInMainHand() : p.getInventory().getItemInOffHand();
        if (CE.level(rod, "grapple") <= 0) return;
        double left = secondsLeft(grappleCd, p);
        if (left > 0) { p.sendActionBar(Component.text("Grapple ready in " + String.format("%.0f", left) + "s", NamedTextColor.RED)); return; }
        Location hook = e.getHook().getLocation();
        if (!hook.getWorld().equals(p.getWorld()) || hook.distance(p.getLocation()) < 2) return;
        Abilities.pull(p, hook, cfgD("grapple.strength", 3.0));
        grappleCd.put(p.getUniqueId(), System.currentTimeMillis() + (long) (cfgD("grapple.cooldown", 30) * 1000));
        Fx.line(p.getLocation().add(0, 1, 0), hook, 0.5, l -> Fx.part(l, org.bukkit.Particle.CRIT, 1, 0, 0, 0, 0));
        p.getWorld().playSound(p.getLocation(), "entity.fishing_bobber.retrieve", 1f, 0.8f);
    }

    // ================================================================ mining: Timber, Vein Miner, Excavation
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (nested) return;
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        Map<String, Integer> m = CE.read(tool);
        if (m.isEmpty()) return;
        Block b = e.getBlock();
        List<Block> extra = new ArrayList<>();

        if (m.containsKey("timber") && Tag.LOGS.isTagged(b.getType()))
            extra.addAll(flood(b, mt -> Tag.LOGS.isTagged(mt), (int) cfgD("timber.max-blocks", 256)));

        if (m.containsKey("vein_miner") && isOre(b.getType()) && (!Aegis.I.getConfig().getBoolean("custom-enchants.vein_miner.require-sneak", false) || p.isSneaking())) {
            String fam = family(b.getType());
            extra.addAll(flood(b, mt -> isOre(mt) && family(mt).equals(fam), (int) cfgD("vein_miner.max-blocks", 64)));
        }

        if (m.containsKey("excavation") && !tool.getItemMeta().getPersistentDataContainer().has(excavationOff, PersistentDataType.BYTE))
            extra.addAll(excavationBlocks(p, b, m.get("excavation")));

        if (extra.isEmpty()) return;
        nested = true;
        try {
            for (Block x : new LinkedHashSet<>(extra)) {
                if (p.getInventory().getItemInMainHand().getType().isAir()) break;   // tool broke
                p.breakBlock(x);
            }
        } finally { nested = false; }
    }

    private static boolean isOre(Material m) { return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS; }

    private static String family(Material m) { return m.name().replace("DEEPSLATE_", ""); }

    private Set<Block> flood(Block start, Predicate<Material> same, int max) {
        Set<Block> seen = new LinkedHashSet<>();
        Deque<Block> q = new ArrayDeque<>();
        seen.add(start);
        q.add(start);
        while (!q.isEmpty() && seen.size() < max) {
            Block c = q.poll();
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                if (seen.size() >= max) break;
                Block r = c.getRelative(x, y, z);
                if (seen.contains(r) || !same.test(r.getType())) continue;
                seen.add(r);
                q.add(r);
            }
        }
        seen.remove(start);
        return seen;
    }

    /** Level 1: 1x2, 2: + shape, 3: 3x3, 4: 5x5 - in the plane of the face you are mining. */
    private List<Block> excavationBlocks(Player p, Block origin, int level) {
        BlockFace face = p.getTargetBlockFace(6);
        if (face == null) face = BlockFace.UP;
        int[] a, b;
        if (face == BlockFace.UP || face == BlockFace.DOWN) { a = new int[]{1, 0, 0}; b = new int[]{0, 0, 1}; }
        else if (face == BlockFace.NORTH || face == BlockFace.SOUTH) { a = new int[]{1, 0, 0}; b = new int[]{0, 1, 0}; }
        else { a = new int[]{0, 0, 1}; b = new int[]{0, 1, 0}; }

        List<int[]> offs = new ArrayList<>();
        if (level <= 1) {
            if (face == BlockFace.UP || face == BlockFace.DOWN) { BlockFace f = p.getFacing(); offs.add(new int[]{f.getModX(), f.getModZ()}); }
            else offs.add(new int[]{0, 1});
        } else if (level == 2) {
            offs.add(new int[]{1, 0}); offs.add(new int[]{-1, 0}); offs.add(new int[]{0, 1}); offs.add(new int[]{0, -1});
        } else {
            int r = level >= 4 ? 2 : 1;
            for (int da = -r; da <= r; da++) for (int db = -r; db <= r; db++) if (da != 0 || db != 0) offs.add(new int[]{da, db});
        }
        List<Block> out = new ArrayList<>();
        for (int[] o : offs) {
            Block t = origin.getRelative(a[0] * o[0] + b[0] * o[1], a[1] * o[0] + b[1] * o[1], a[2] * o[0] + b[2] * o[1]);
            Material tm = t.getType();
            if (tm.isAir() || tm == Material.WATER || tm == Material.LAVA || tm.getHardness() < 0) continue;
            if (!Tag.MINEABLE_PICKAXE.isTagged(tm) || t.getState(false) instanceof TileState) continue;
            out.add(t);
        }
        return out;
    }

    // ================================================================ right-click: Excavation toggle, Charge
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        org.bukkit.event.block.Action act = e.getAction();
        if (act != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && act != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        Map<String, Integer> m = CE.read(it);
        if (m.isEmpty()) return;
        Player p = e.getPlayer();

        if (m.containsKey("excavation") && p.isSneaking()) {
            boolean off = it.getItemMeta().getPersistentDataContainer().has(excavationOff, PersistentDataType.BYTE);
            it.editMeta(mm -> {
                if (off) mm.getPersistentDataContainer().remove(excavationOff);
                else mm.getPersistentDataContainer().set(excavationOff, PersistentDataType.BYTE, (byte) 1);
            });
            p.getInventory().setItemInMainHand(it);
            p.sendMessage(Component.text("Excavation " + (off ? "ON" : "OFF"), off ? NamedTextColor.GREEN : NamedTextColor.RED));
            e.setCancelled(true);
            return;
        }
        if (m.containsKey("charge")) {
            e.setCancelled(true);                                   // vanilla spear charge attack is replaced by the dash
            if (Status.on(Status.stun, p) || Status.on(Status.freeze, p)) return;
            charge(p, m.get("charge"));
        }
    }

    private void charge(Player p, int lv) {
        double left = secondsLeft(chargeCd, p);
        if (left > 0) { p.sendActionBar(Component.text("Charge ready in " + String.format("%.0f", left) + "s", NamedTextColor.RED)); return; }
        int cost = (int) cfgD("charge.hunger-cost", 10);              // 5 hunger bars
        if (p.getFoodLevel() < cost) { p.sendActionBar(Component.text("Charge needs " + cost / 2 + " hunger bars", NamedTextColor.RED)); return; }
        p.setSaturation(0f);                                          // all saturation
        p.setFoodLevel(p.getFoodLevel() - cost);
        chargeCd.put(p.getUniqueId(), System.currentTimeMillis() + (long) (cfgD("charge.cooldown", 30) * 1000));

        double dist = cfgD("charge.blocks-per-level", 5) * lv, speed = cfgD("charge.speed", 1.5);
        double dmg = cfgD("charge.damage-base", 8) + cfgD("charge.damage-per-level", 4) * lv;
        Vector dir = p.getEyeLocation().getDirection().normalize();
        p.getWorld().playSound(p.getLocation(), "item.trident.riptide_3", 1f, 1f);
        new BukkitRunnable() {
            double travelled = 0;
            final Set<UUID> hit = new HashSet<>();
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { cancel(); return; }
                RayTraceResult br = p.getWorld().rayTraceBlocks(p.getEyeLocation(), dir, speed + 0.6, FluidCollisionMode.NEVER, true);
                if (br != null || travelled >= dist) {
                    if (br == null) p.setVelocity(dir.clone().multiply(0.5));
                    cancel();
                    return;
                }
                p.setVelocity(dir.clone().multiply(speed));
                p.setFallDistance(0);
                for (Entity en : p.getNearbyEntities(1.4, 1.4, 1.4)) {
                    if (!(en instanceof LivingEntity le) || en instanceof ArmorStand || Pets.isPet(en) || hit.contains(en.getUniqueId()) || Abilities.trusts(p, en)) continue;
                    hit.add(en.getUniqueId());
                    le.damage(dmg, p);
                    Fx.burst(le.getLocation().add(0, 1, 0), org.bukkit.Particle.CRIT, 30, .5);
                }
                Fx.part(p.getLocation().add(0, 1, 0), org.bukkit.Particle.CLOUD, 6, .3, .4, .3, .02);
                Fx.part(p.getLocation().add(0, 1, 0), org.bukkit.Particle.CRIT, 4, .3, .4, .3, .1);
                travelled += speed;
            }
        }.runTaskTimer(pl, 0, 1);
    }

    // ================================================================ melee procs (called from CombatListener.onHit)
    static double melee(Player att, LivingEntity vic, boolean strong, double dmg) {
        if (!strong) return dmg;
        ItemStack w = att.getInventory().getItemInMainHand();
        Map<String, Integer> m = CE.read(w);
        if (m.isEmpty()) return dmg;
        Location mid = vic.getLocation().add(0, vic.getHeight() / 2, 0);

        Integer bleed = m.get("bleed");
        if (bleed != null && roll(cfgD("bleed.chance-per-level", 3) * bleed)) {
            Status.bleed(vic, cfgD("bleed.seconds", 5));
            Fx.dust(mid, 25, .4, 0xB71C1C, 1.5f);
        }
        if (m.containsKey("double_attack") && roll(cfgD("double_attack.chance", 1))) {
            dmg *= cfgD("double_attack.multiplier", 2.0);
            Fx.burst(mid, org.bukkit.Particle.CRIT, 40, .5);
        }
        if (m.containsKey("reshuffle") && vic instanceof Player vp && roll(cfgD("reshuffle.chance", 0.25))) {
            Passives.shuffleHotbar(vp);
            Fx.burst(mid, org.bukkit.Particle.WITCH, 25, .2);
        }
        if (m.containsKey("heavy_blow") && vic instanceof Player vp2 && CombatListener.isCrit(att) && roll(cfgD("heavy_blow.chance", 3))) {
            Status.stun(vp2, cfgD("heavy_blow.stun-seconds", 1.5));
        }
        return dmg;
    }

    /** Lifeleach: heal a % of the damage actually dealt. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeech(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || Abilities.inAbility) return;
        int lv = CE.level(p.getInventory().getItemInMainHand(), "lifeleach");
        if (lv <= 0) return;
        double heal = e.getFinalDamage() * cfgD("lifeleach.percent-per-level", 0.10) * lv;
        if (heal <= 0) return;
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(Math.min(max == null ? 20 : max.getValue(), p.getHealth() + heal));
        Fx.heal(p);
    }

    /** Executioner: chance (10% per level) for the victim's head. */
    @EventHandler
    public void onKill(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        Player k = v.getKiller();
        if (k == null || v instanceof ArmorStand) return;
        int lv = CE.level(k.getInventory().getItemInMainHand(), "executioner");
        if (lv <= 0 || !roll(cfgD("executioner.chance-per-level", 10) * lv)) return;
        e.getDrops().add(v instanceof Player vp ? Heads.playerHead(vp) : Heads.headFor(v, null));
    }

    // ================================================================ armor: Unmovable, Immortal
    static void refreshArmor(Player p) {
        int count = 0;
        for (ItemStack s : p.getInventory().getArmorContents()) if (CE.level(s, "unmovable") > 0) count++;
        AttributeInstance a = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (a == null) return;
        NamespacedKey k = new NamespacedKey(Aegis.I, "unmovable_kb");
        AttributeModifier cur = null;
        for (AttributeModifier m : a.getModifiers()) if (m.getKey().equals(k)) cur = m;
        double want = Math.min(1.0, count * cfgD("unmovable.per-piece", 0.10));
        if (cur != null && Math.abs(cur.getAmount() - want) < 1e-9) return;
        if (cur != null) a.removeModifier(cur);
        if (want > 0) a.addModifier(new AttributeModifier(k, want, AttributeModifier.Operation.ADD_NUMBER));
    }

    /** Called from CombatListener when a hit would kill. Returns true if Immortal saved the player. */
    static boolean tryImmortal(Player p) {
        ItemStack holder = null;
        for (ItemStack s : p.getInventory().getArmorContents()) if (CE.level(s, "immortal") > 0) { holder = s; break; }
        if (holder == null || secondsLeft(immortalCd, p) > 0) return false;
        int cost = (int) cfgD("immortal.durability-cost", 150);
        if (CE.durabilityLeft(holder) < cost) return false;          // the enchanted piece must be able to pay

        Gear.armorDelta(p, cost);                                    // every piece loses durability
        immortalCd.put(p.getUniqueId(), System.currentTimeMillis() + (long) (cfgD("immortal.cooldown", 1800) * 1000));
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(Math.min(max == null ? 20 : max.getValue(), cfgD("immortal.revive-health", 1.0)));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));          // totem effects
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        Fx.burst(p.getLocation().add(0, 1, 0), org.bukkit.Particle.TOTEM_OF_UNDYING, 80, .6);
        p.getWorld().playSound(p.getLocation(), "item.totem.use", 1f, 1f);
        p.sendMessage(Component.text("Immortal saved you! It recharges in "
                + (int) (cfgD("immortal.cooldown", 1800) / 60) + " minutes.", NamedTextColor.GOLD));
        return true;
    }
}
