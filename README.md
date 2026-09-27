# Neon Drift

A lightweight native Android arcade game built with **Kotlin + Jetpack Compose Canvas**.

## Game

- Drag anywhere to steer the neon ship.
- Avoid the red gates.
- Collect green energy orbs for bonus points.
- The game accelerates and obstacle spacing tightens over time.
- Best score is stored locally on the phone.
- Pause, resume and restart are built in.

No game engine and no WebView are used. The gameplay loop and collision rules are Kotlin code, and
Compose Canvas renders the game directly.

## Build

```bash
gradle assembleRelease testDebugUnitTest lintDebug
```

The APK is produced under `app/build/outputs/apk/release/`.

## GitHub Actions

Every push and pull request runs build + unit tests + Android lint on GitHub-hosted Ubuntu. Pushes to `main` also replace a
rolling `latest-build` GitHub Release containing `NeonDrift.apk`, so the newest build can be installed
straight onto an Android phone.

## Project structure

```text
app/src/main/java/com/ma7moud/neondrift/
  game/      Pure game state, spawning, difficulty and collision rules
  ui/        Compose Canvas renderer, HUD, menus and touch controls
  ui/theme/  Neon Material theme
```
