# Aegis SMP

Paper plugin (1.21.4+): slot-machine classes, energy 0-5, passives, abilities, trust.

**Flat layout:** all files are in the repo root (phone-friendly). The `.java` files use `package dev.aegis;`
but live in the root - Maven is configured for that in `pom.xml`.

## Build
GitHub Actions builds it automatically (Actions tab -> latest run -> `AegisSMP-plugin` artifact = the jar).
Requires the workflow file `.github/workflows/build.yml` (copy the text from `build.yml.txt`).

## Install
1. Put the jar in `plugins/`.
2. Use `AegisSMP-ResourcePack.zip` as the server resource pack (HUD icons + marble/reroller art).
   No pack? Set `hud.mode: text` and `items.use-item-model: false` in `config.yml`.

## Basics
F = ability 1, Shift+F = ability 2, `/ability mode` = command mode, `/myclass`, `/trust add|remove|list <player>`.
Admin: `/aegis give <player> <marble|reroller> [n]`, `/aegis setclass`, `/aegis setenergy`, `/aegis reload`.
Everything is configurable in `config.yml`. Optional crafting recipes are under `recipes:`.
