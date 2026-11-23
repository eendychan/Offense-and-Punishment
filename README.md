# Offense and Punishment

A **server-side Fabric mod** for Minecraft **26.3**.

## What it does

- Killing another player permanently removes 2 points from the killer's maximum health.
- The maximum health cannot be reduced below 2 HP.
- Hitting another player resets the recovery timer, even if the victim survives. Shield-blocked hits also count.
- After 3 real-world hours without hitting another player, 2 maximum HP are restored.
- Each additional recovery step requires another uninterrupted 3 real-world hours.
- Recovery time continues while the player is offline and while the server is stopped.
- Player data is saved in `offense_and_punishment.json` in the world folder.

## HUD limitation

This is a server-only mod. A server cannot draw a custom overlay directly above the health bar on an unmodified client. The recovery countdown is therefore shown in the action bar (the text area above the hotbar), once per second, only while the player has reduced maximum health.

## Build

The GitHub Actions workflow builds the mod automatically. It uses Java 25 and Gradle 9.6.0.

The output JAR is uploaded as an Actions artifact named `offense-and-punishment`.

## Install

Put the built JAR into the server's `mods` folder. The server must use Fabric Loader for Minecraft 26.3 and have Fabric API installed.

No client-side installation is required.
