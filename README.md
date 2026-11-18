# Walkure Romanze — Kotlin + LibGDX Visual Novel Engine

A clean-room, memory-safe **Kotlin/JVM 25** engine and reverse-engineering toolkit for *Walkure Romanze*, using **LibGDX 1.14.2** and **LibKTX 1.13.1-rc1**. It supports QLiE `FilePackVer3.0` and `FilePackVer1.0` archives.

> **The game and its copyrighted assets are not included.** You need your own game installation and valid game data to play. Clone this repository inside that installation folder; do not clone over or replace the original game files.

## On this page

- [Quick start](#quick-start)
- [Build and download packages](#build-and-download-packages)
  - [Desktop packages](#desktop-packages)
  - [GitHub releases and versioning](#github-releases-and-versioning)
  - [Android APK](#android-apk)
- [Controls](#controls)
- [Engine features](#engine-features)
- [Development](#development)

## Quick start

### Requirements

- Git and internet access.
- JDK 25 for the project toolchain. The Gradle wrapper downloads Gradle 9.9.1; its configured JDK 25 daemon may also be downloaded on first run.
- Your own game installation, including `GameData/data*.pack` and a valid `GameData/walkure.keys`. The asset-restoration script can generate the key if the local game executable and DLL are available.

**Windows has not been tested.** The application, Windows launchers and scripts, packaging, and Android builds on Windows are unverified.

### Clone and restore local assets

On Linux or macOS:

```bash
cd "/path/to/Walkure Romanze"
git clone https://github.com/Forgeron-dest/WalkureRomanze-Engine.git walkure-kotlin
cd walkure-kotlin
bash scripts/restore-game-assets.sh
```

On Windows, use PowerShell:

```powershell
Set-Location 'C:\path\to\Walkure Romanze'
git clone https://github.com/Forgeron-dest/WalkureRomanze-Engine.git walkure-kotlin
Set-Location .\walkure-kotlin
.\scripts\restore-game-assets.bat
```

The scripts use local game files when available. They restore ignored Android assets, including launcher icons, and generate `GameData/walkure.keys` if needed. The custom font and startup logo are optional; `ffmpeg` is needed to extract and convert the logo from the game archive. The Windows batch script needs neither Git Bash nor WSL.

### Run the desktop game

Start at the title screen:

```bash
./gradlew :run --args="play --honorifics HON"
```

On Windows:

```powershell
.\gradlew.bat :run --args="play --honorifics HON"
```

To start from a scenario script:

```bash
./gradlew :run --args="play --script sue_001_00.s --honorifics HON"
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`.

### Export a portable key

Generate the 512-byte `GameData/walkure.keys` file used to decrypt `data0.pack` through `data9.pack`:

```bash
./gradlew :run --args="export-keys"
```

On Windows, run `.\gradlew.bat :run --args="export-keys"`. A valid key is required to play; the Windows executable and DLL are only needed when generating it from the original installation.

## Build and download packages

### Desktop packages

Build the desktop distributions locally:

```bash
./gradlew packageAll
```

On Windows, use `.\gradlew.bat packageAll`. Outputs are written to `build/dist/`:

- `walkure-romanze-desktop.jar` — cross-platform fat JAR.
- `walkure-romanze-linux` and `walkure-linux.sh` — Linux launchers.
- `walkure-windows.bat`, `jpackage-input/`, and `walkure-windows-jpackage.args` — Java-based Windows launcher, staged JAR, and configuration for creating a Windows app image.

The JAR and `.bat` launcher require Java 21+ on `PATH`. A `jpackage` app image includes its own runtime. `packageAll` does not create a native Windows app image on Linux. To create one on Windows, run `jpackage @walkure-windows-jpackage.args` from `build/dist` using a JDK. The generated launchers need to be run alongside the user's game installation. GitHub releases include a Windows app-image ZIP created on a Windows runner; launch it with `WalkureRomanze.exe`.

### GitHub releases and versioning

Merging a pull request into `main` automatically publishes a desktop release according to the source branch prefix:

| Branch prefix | Version bump |
| :--- | :--- |
| `fix/`, `chore/`, `renovate/` | Patch (`v1.2.3` → `v1.2.4`) |
| `feat/` | Minor (`v1.2.3` → `v1.3.0`) |
| `breaking/` | Major (`v1.2.3` → `v2.0.0`) |
| Any other prefix | No release |

If there is no previous stable version tag, the baseline is `v0.1.0`; the first qualifying merge increments from it. For example, the first `fix/` merge publishes `v0.1.1`, while the first `feat/` merge publishes `v0.2.0`. Version tags use a `vMAJOR.MINOR.PATCH` format. A manually published GitHub Release also builds and attaches the desktop archives; drafts do not trigger that build.

The workflow builds the merged commit on Linux and Windows, then creates the release and uploads a Linux archive and a self-contained Windows app-image ZIP. It reuses a tag already created for the same merge commit if a run is retried. If two releases collide on the same next version, the later job fails rather than overwriting another commit's release; rerun it to calculate the next version. The release tag is the user-facing version; the Gradle module versions are separate and are not changed by the workflow.

Release archives contain the engine and launchers, **not** game files. Players must provide their own game installation. The Windows app image includes its own Java runtime and executable, but is not an installer. Android packages are not currently published by the release workflow.

### Android APK

Android builds require internet access and Android SDK `platform-tools`, `platforms;android-36`, and `build-tools;36.0.0`. Gradle downloads the declared native libraries and generates the ignored `android/libs` files. The headless setup scripts can install the required SDK components without Android Studio. ADB and USB debugging are optional, for transferring data to a device.

Build locally with:

```bash
./gradlew buildApk
```

On Windows, use `.\gradlew.bat buildApk`. The APK is written to `build/dist/walkure-romanze.apk` and includes native libraries for ARM64, ARMv7, x86_64, and x86. If local `walkure.keys`, `WalkureCustomV4.ttf`, or `ricotta_logo.mp4` assets are available, the build bundles them. The app can locate game archives or let the player choose their folder. For setup details, data transfer, and touchscreen controls, see [ANDROID.md](ANDROID.md).

## Controls

| Key / Input | Action |
| :--- | :--- |
| `Enter` / `Space` / `Left Click` | Advance dialogue / Select choice / Confirm button |
| `Right Click` | Hide / Show HUD and message window |
| `A` | Toggle auto mode; waits for active voice playback |
| Hold `Ctrl` or press `S` | Toggle skip mode, respecting the Config setting |
| `F2` / `F3` | QuickSave / QuickLoad (slot 0) |
| `L` / `Mouse Wheel` | Toggle backlog; click `[VOICE]` to replay |
| `F11` | Toggle fullscreen / windowed |
| `Esc` | Return to title screen / close overlay |

## Engine features

- **Archive and crypto support (`walkure.crypto`, `walkure.compression`, `walkure.pack`):** mounts `FilePackVer3.0` (`data0.pack`–`data9.pack`) and `FilePackVer1.0` (`data10.pack`–`data15.pack`), exposing **32,635** virtual assets with HON/NOHON priority overrides. It can extract keys from the local executable/DLL and export a portable 512-byte key file. The `1PC\xFF` (`0xFF435031`) BPE decompressor has a 64 MiB safety guard.
- **Image and nested UI decoders (`walkure.image`):** `ArgbDecoder` composites RGB JPEG sprites with grayscale alpha masks and supports LibGDX `Pixmap` or `BufferedImage` output. `AbmpDecoder` parses `abmp10`/`abmp11`/`abmp12` layered UI containers, decompresses embedded scripts, and flattens nested containers such as `スクリプト\title\back.b`.
- **Game configuration (`walkure.config.SystemDatParser`):** parses `system\etc\game.dat`, `system\etc\message.dat`, `system\voice\voicedata.dat`, and album metadata under `スクリプト\albummode`.
- **Visual novel interface (`walkure.gdx.WalkureGdxGame`, `walkure.save.SaveManager`):** title screen, dialogue HUD, choices, save/load, settings, backlog, album, and a 35-track music room. Save/load provides 48 slots plus quicksave, with thumbnails, in-game date, route badge, and timestamp. System data tracks volumes, per-character voice mutes, read text, and unlocked CGs.
- **Scenario and video playback (`walkure.script.CampaignConductor`, `walkure.video.OgvVideoPlayer`):** follows common scenes and heroine routes, and streams Ogg Theora/Vorbis cutscenes with synchronized audio.

## Development

The local pre-commit hook runs Spotless and stages formatted versions of staged Kotlin files. Enable it once per clone:

```bash
git config core.hooksPath .githooks
```

If a staged Kotlin file also has unstaged edits, stage or stash those edits before committing. Git for Windows supplies the shell needed by the hook; WSL is not required.

Pull-request CI checks Spotless formatting and compiles desktop and test sources. It compiles, but does not run, the current `core` test suite because those integration tests require local game data that is not available on GitHub-hosted runners.
