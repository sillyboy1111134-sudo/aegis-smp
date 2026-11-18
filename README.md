# Aegis SMP

Paper plugin (1.21.4+): slot-machine classes, energy 0-5, passives, abilities, trust.

**Flat layout:** all files are in the repo root (phone-friendly). The `.java` files use `package dev.aegis;`
but live in the root - Maven is configured for that in `pom.xml`.

## Build
GitHub Actions builds it automatically (Actions tab -> latest run -> `AegisSMP-plugin` artifact = the jar).
Needs the workflow file `.github/workflows/build.yml` (text is in `build.yml.txt`).

## Install
1. Put the jar in `plugins/`.
2. Use `AegisSMP-ResourcePack.zip` as the server resource pack. No pack? Set `hud.mode: text` and `items.use-item-model: false` in `config.yml`.

Everything is configurable in `config.yml`.

## Custom enchants
Obtainable from enchanting tables. Admins (op / `aegis.admin`): `/enchant [player] <id> [level]` (or `/cenchant`, `/ce`, `/aegis enchant`) applies one to the held item and ignores the per-item limit. `/enchant` falls back to vanilla for normal enchants.

## Rogue
Passives (unlock order): Backstab (20% double damage from behind), Hotbar Shuffle (1%), Speed II, permanent invisibility, Shadow Blink (5% teleport behind the target on hit), Venom Touch (5% Poison III for 3s).
- **Stealth** - true invisibility (hidden from everyone, armor included) + Speed III for 10s; ends early when you are hit.
- **Shadow Step** - teleport up to 30 blocks where you look; a silent decoy of you runs the other way (no particles).

## Yggdrasil
Passives (unlock order): permanent Regeneration I, double golden-apple saturation, Regeneration II by day, every healing potion acts like Instant Health III, half a heart healed per hit, +1 heart per kill (max 2 extra, lost on death).
- **Vitality Boost** - double max hearts for 20s and heal 10 hearts instantly. For 10s afterwards golden apples only give Regeneration I and half saturation. Cooldown 60s.
- **World Tree Root** - roots deal 2 hearts and freeze the target in place for 4s. Cooldown 30s.
All numbers are in `config.yml` under `classes.rogue` / `classes.yggdrasil`.

## True damage
All ability damage is true damage: it ignores armor and protection enchantments (`general.abilities-ignore-armor`, `general.true-damage-ignores-enchants` in `config.yml`). Resistance effects and absorption hearts still reduce it. Set `general.all-damage-true: true` to make every hit true damage.

## Jackpot, Fateweaver, Singularity, Spartan
Settings for all four are under `classes.jackpot`, `classes.fateweaver`, `classes.singularity` and `classes.spartan` in `config.yml`.
- **Jackpot** - 10% of item uses are free, Speed I, gambler's golden apples, hotbar shuffles, risky hits and a sword that re-rolls Sharpness 1-10 on every hit. Luck of the Draw and High Roller.
- **Fateweaver** - Shift against a wall to climb, permanent Weaving, spiders ignore you. Weave of Fate shares damage with a target; Web We Weave clears cobwebs.
- **Singularity** - arrows pull targets in, 50% knockback resistance, Resistance I, void-proof. Starfall and Blackhole.
- **Spartan** - netherite spears, Speed I/II, feather dash, air momentum, shield-breaking spears. Champion's Steed and Spartan's Charge.

## Mega Mace (legendary weapon)
Only one can ever be crafted (heavy core in the top middle, a netherite block on each side of it, a nether star under it and a breeze rod under that). Vanilla mace crafting is removed. The crafter's name is shown as a title to everyone.
Shift + right-click switches Breach II / Density II, F dashes 10 blocks, 3 airborne hits shred 80 durability off every armor piece, breaks shields like an axe, sword attack speed, always Wind Burst I, hits are capped at 5 hearts.
Admin: `/aegis megamace give [player]`, `/aegis megamace reset`. Numbers are under `megamace` in `config.yml`.

## Binding Vows (/vow)
Permanent sacrifices for permanent gains. `/vow list`, `/vow info <vow>`, `/vow take <vow> ...` then `/vow confirm`, `/vow status`.
A vow is binding: it can never be taken back, and abilities/passives a vow touched are locked in (a buffed ability or passive can't be sacrificed later). Vows stay when you reroll your class: they keep applying to the same ability and passive slots of your new class, and vows can still never push damage past 5 hearts.
- **Cart** (2 hearts): your TNT minecarts can explode, nobody else's can; blast capped at 5 hearts.
- **Exchange** (1 ability): double the other ability's damage or duration (never above 5 hearts of damage). Agni's Fireball charges in half the time instead.
- **Focus** (2 passives): one ability gets -40% cooldown and +50% duration.
- **Kinship** (1 passive): another passive becomes 50% stronger.
- **Blood** (2 hearts): all cooldowns -25%.
- **Phoenix** (1 ability): survive a killing blow with 3 hearts, every 15 minutes.
Limits are under `vows` in `config.yml`.
