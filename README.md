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
