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
All damage ignores armor and protection enchantments (`general.all-damage-true`, `general.true-damage-ignores-enchants` in `config.yml`). Resistance effects and absorption hearts still reduce it. Set `all-damage-true: false` to go back to only abilities ignoring armor.

## Jackpot, Fateweaver, Singularity, Spartan
Settings for all four are under `classes.jackpot`, `classes.fateweaver`, `classes.singularity` and `classes.spartan` in `config.yml`.
- **Jackpot** - 10% of item uses are free, Speed I, gambler's golden apples, hotbar shuffles, risky hits and a sword that re-rolls Sharpness 1-10 on every hit. Luck of the Draw and High Roller.
- **Fateweaver** - Shift against a wall to climb, permanent Weaving, spiders ignore you. Weave of Fate shares damage with a target; Web We Weave clears cobwebs.
- **Singularity** - arrows pull targets in, 50% knockback resistance, Resistance I, void-proof. Starfall and Blackhole.
- **Spartan** - netherite spears, Speed I/II, feather dash, air momentum, shield-breaking spears. Champion's Steed and Spartan's Charge.
