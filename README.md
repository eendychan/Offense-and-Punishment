# Offense and Punishment

A **server-side Fabric mod** for Minecraft **26.3**.

## What it does

- Killing another player removes the configured amount of maximum health from the killer.
- Maximum health cannot be reduced below 2 HP.
- Hitting another player resets the recovery timer, even if the victim survives. Shield-blocked hits also count.
- After the configured uninterrupted real-world time without hitting another player, the configured amount of missing maximum health is restored.
- Recovery time continues while the player is offline and while the server is stopped.
- Player penalties are saved in `offense_and_punishment.json` in the world folder.
- Settings are saved in `offense_and_punishment_config.json` in the world folder.

## Default settings

- Recovery interval: **30 minutes** (1800 seconds).
- Health penalty/recovery amount: **2 HP**.

## Commands

Both commands require operator permission level 2 (OP).

- `/punishment_time <seconds>` — sets the uninterrupted time required for one recovery step. Minimum: 1 second.
- `/punishment_hp <amount>` — sets how much maximum health is removed per kill and restored per recovery step. Minimum: 1 HP.

Changing `punishment_hp` does not rewrite health already missing from players. It changes the amount applied by future kills and future recovery steps. The last recovery step is capped so that no more health is restored than is actually missing.

## HUD limitation

This is a server-only mod. A server cannot draw a custom overlay directly above the health bar on an unmodified client. The recovery countdown is therefore shown in the action bar (the text area above the hotbar), only while the player has reduced maximum health.

## Build

GitHub Actions builds the mod automatically using Java 25 and Gradle 9.8.0. The output JAR is uploaded as an Actions artifact named `offense-and-punishment`.

## Install

Put the built JAR into the server's `mods` folder. The server must use Fabric Loader for Minecraft 26.3 and have Fabric API installed.

No client-side installation is required.
