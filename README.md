# Walkure Romanze — Pure Kotlin + LibGDX/KTX Visual Novel Engine

A clean-room, memory-safe **Pure Kotlin (JVM 21)** + **LibGDX (`1.13.1`) / LibKTX (`1.13.1-rc1`)** reverse-engineering toolkit and cross-platform visual novel engine for *Walkure Romanze* (`QLiE / FilePackVer3.0` & `FilePackVer1.0`).

---

## First-Time Setup for a Local Game Copy

The game archives and related assets are not included in this repository. Clone the repository as a subfolder **inside your own Walkure Romanze game installation folder** (do not clone over or replace the game files):

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

The script uses the local `GameData`, game executable/DLL, and `WalkureCustomV4.ttf` when available. It writes ignored files under `android/src/main/assets/` and restores the launcher icon from Mio's local game portrait; it does not add game assets to Git. If `walkure.keys` does not already exist, it generates it from the local executable and DLL. The game archives and a valid key are required to play; the custom font and logo are optional. Installing `ffmpeg` lets the script extract and convert the startup logo video from the game archive.

The Windows batch script performs the same local restoration; Git Bash and WSL are not required.

---

## Complete Feature Matrix

1. **QLiE Cryptography & Archive VFS (`walkure.crypto`, `walkure.compression`, `walkure.pack`)**
   - Full `FilePackVer3.0` (`data0.pack`..`data9.pack`) and `FilePackVer1.0` (`data10.pack`..`data15.pack`) virtual file system mounting **32,635** virtual assets with priority override and honorifics mode (`HON` / `NOHON`).
   - Automatic extraction of `DLL/key.fkey` and Borland Delphi `TForm1.IconKeyImage` (`TIcon` payload from `WALKURE_ROMANZE.exe`), plus `export-keys` command to generate a portable 512-byte `GameData/walkure.keys` file so non-Windows targets (Android `.apk`, Linux, macOS) never require the Windows `.exe` or `.dll`.
   - `1PC\xFF` (`0xFF435031`) Byte-Pair Encoding (BPE) decompressor with a 64 MiB safety guard.

2. **Proprietary Image & Nested UI Decoders (`walkure.image`)**
   - `ArgbDecoder`: Decodes `ARGBSaveData1` (`.argb`) sprites directly into LibGDX `Pixmap` (Android & Desktop compatible) or `BufferedImage`, compositing the RGB JPEG and grayscale Alpha PNG mask.
   - `AbmpDecoder`: Parses `abmp10`/`abmp11`/`abmp12` (`.b`) multi-layer UI containers, decompresses `1PC\xFF`-encoded `abdata` scripts, and recursively flattens nested `abmp12` sub-containers (`allFlattenedImages()`) such as `スクリプト\title\back.b`.

3. **All 4 `system\**\*.dat` Configs & Album Metadata (`walkure.config.SystemDatParser`)**
   - Parses `system\etc\game.dat` (`1024×768` viewport, `103×77` thumbnails, volume multipliers), `system\etc\message.dat` (system confirmation prompts), `system\voice\voicedata.dat` (all 15 `act[0..14]` per-character voice channels), and `スクリプト\albummode\list_*.txt` (CG Gallery groups for Celia, Mio, Noel, Lisa, and Others).

4. **Title Screen, In-Game HUD, Save/Load, Options & Album/Music Room (`walkure.gdx.WalkureGdxGame`, `walkure.save.SaveManager`)**
   - **Title Screen**: Renders `スクリプト\title\back.b` layers with `音楽\op_inst.ogg` BGM and interactive buttons (`NEW GAME`, `CONTINUE`, `ALBUM & MUSIC`, `CONFIG`, `EXIT`).
   - **Save / Load Screen**: 4 pages × 12 slots (`slot_01`..`slot_48`) + QuickSave (`slot_00`) with `103×77` live OpenGL framebuffer PNG thumbnails, in-game `^savedate` (`MM/DD`), `^saveroute` heroine badge, and real-world timestamp.
   - **System Data (`SaveData/kotlin_sysdata.properties`)**: Tracks BGM/Voice/SE volumes, 15 individual character voice mutes (`act[0..14]`), read-text (`kidoku`) line tracking (`scriptPath#line`), and unlocked CGs.
   - **Album & 35-Track Music Room**: Browse unlocked CGs and variants across 5 category tabs (`Celia`, `Mio`, `Noel`, `Lisa`, `Etc`) and play all 35 `音楽\bgm01.ogg`..`bgm34.ogg` + `op_inst.ogg` tracks on demand.

5. **Master Campaign Conductor & Video Playback (`walkure.script.CampaignConductor`, `walkure.video.OgvVideoPlayer`)**
   - Parses `シナリオ\route.s`, `シナリオ\route美桜.s`, `routeスィーリア.s`, `routeノエル.s`, `routeリサ.s`, and `シナリオ\select.s` (`mioflag++`, `suiflag++`, `noeflag++`, `risflag++`) to chain all common scenes, opening/ending videos (`VR_OP.ogv`, `VR_ED_*.ogv`), and heroine route branches automatically.
   - Streams `.ogv` (Ogg Theora + Vorbis) movies into a dynamic OpenGL `Texture` synchronized with OpenAL audio, skippable via `Left Click`, `Enter`, or `Esc`.

---

## Controls (LibGDX Desktop & Android)

| Key / Input | Action |
| :--- | :--- |
| `Enter` / `Space` / `Left Click` | Advance dialogue / Select choice / Confirm button |
| `Right Click` | Hide / Show HUD & Message Window (View full CG) |
| `A` | Toggle **Auto Mode** (waits for active character voice clip to finish) |
| `Ctrl` (hold) or `S` | Toggle **Skip Mode** (respects *Read-Only* vs *All* setting in Config) |
| `F2` / `F3` | QuickSave (`Slot 0`) / QuickLoad (`Slot 0`) |
| `L` / `Mouse Wheel` | Toggle **Backlog Overlay** (click any entry with `[VOICE]` to replay audio) |
| `F11` | Toggle Fullscreen / Windowed (`1024×768` fit viewport) |
| `Esc` | Return to Title Screen / Close overlay |

---

## Building, Running & Multi-Platform Packaging

### Build dependencies

- **Git** is needed to clone the repository.
- **JDK 21** is required for the project toolchain. The Gradle wrapper downloads Gradle 9.6.1; its configured **JDK 25 daemon may be downloaded on first run**. The SDK setup scripts do not install Java.
- **Internet access** is needed for Gradle, dependencies, the configured daemon JDK, and any Android SDK packages not already installed.
- **Android builds only:** Android SDK `platform-tools`, `platforms;android-36`, and `build-tools;36.0.0`. The headless setup scripts install these and Gradle generates the ignored `android/libs` native libraries during the build.
- **To play the game:** use your own game installation, including `GameData/data*.pack` and a valid `GameData/walkure.keys`. Asset restoration can generate the key when the local executable and DLL are available. The custom font and startup logo are optional.
- **Optional:** `ffmpeg` for extracting/converting the startup logo and transcoding movies; ADB (included in Android `platform-tools`) and USB debugging for the one-click phone transfer.

The standalone desktop JAR and `walkure-windows.bat` require a Java 21+ runtime on `PATH`; the `jpackage` app image bundles its own runtime.

**Windows has not been tested.** The application, Windows desktop packaging, Android builds, and Windows batch scripts are unverified on Windows; the commands below are provided as guidance.

### Developer formatting hook

The build checks Kotlin formatting with Spotless. To enable the local pre-commit hook in a clone, run once:
```bash
git config core.hooksPath .githooks
```
The hook runs `spotlessApply` for Kotlin changes being committed and stages the formatted versions. If a staged Kotlin file also has unstaged edits, stage or stash those edits before committing. Git for Windows includes the shell needed to run the hook; WSL is not required.

### 1. Run the Game (Starts at Title Screen)
```bash
./gradlew :run --args="play --honorifics HON"
```
On Windows, run from the cloned repository:
```powershell
.\gradlew.bat :run --args="play --honorifics HON"
```
To jump directly into a specific scenario script (e.g. `sue_001_00.s`):
```bash
./gradlew :run --args="play --script sue_001_00.s --honorifics HON"
```
On Windows:
```powershell
.\gradlew.bat :run --args="play --script sue_001_00.s --honorifics HON"
```

### 2. Export Portable 512-Byte Key File (`GameData/walkure.keys`)
Generates `GameData/walkure.keys` (`512 bytes`) so builds can decrypt `data0.pack`..`data9.pack` without requiring `WALKURE_ROMANZE.exe` or `DLL/key.fkey` at runtime:
```bash
./gradlew :run --args="export-keys"
```
On Windows, use:
```powershell
.\gradlew.bat :run --args="export-keys"
```
When the repository is cloned inside the game folder, `bash scripts/restore-game-assets.sh` (Linux/macOS) or `.\scripts\restore-game-assets.bat` (Windows) generates the key file and restores ignored Android assets for the local build.

### 3. Build Multi-Platform Distribution Bundles (`build/dist/`)
```bash
./gradlew packageAll
```
On Windows, run:
```powershell
.\gradlew.bat packageAll
```
Produces in `walkure-kotlin/build/dist/`:
- `walkure-romanze-linux`: Self-executing Linux binary (`./walkure-romanze-linux`)
- `walkure-romanze-desktop.jar`: Cross-platform standalone fat JAR
- `walkure-linux.sh`: POSIX shell launcher
- `walkure-windows.bat` & `walkure-windows-jpackage.args`: Windows launcher and `jpackage` config for building a native Windows `.exe` (`jpackage @walkure-windows-jpackage.args`)

For the native Windows app image, run `jpackage @walkure-windows-jpackage.args` from `build/dist` in Command Prompt on Windows with a JDK installed. In PowerShell, use:
```powershell
Set-Location .\build\dist
jpackage '@walkure-windows-jpackage.args'
```
`packageAll` creates the shared JAR and launchers but does not create a Windows app image when run on Linux. Run the generated launcher from `build/dist` or from the cloned repository so it can find the neighboring game installation.

### 4. Build Android `.apk` (`build/dist/walkure-romanze.apk`)
```bash
./gradlew buildApk
```
On Windows, run:
```powershell
.\gradlew.bat buildApk
```
Produces `build/dist/walkure-romanze.apk` (7.3 MB) with:
- Native `.so` libraries for ARM64, ARMv7, x86_64, and x86
- Gradle downloads dependencies and generates the ignored Android native libraries automatically; no manual `android/libs` setup is needed
- Automatically bundles local `walkure.keys` and `WalkureCustomV4.ttf` when available
- Dynamic storage auto-detection and folder picker (SAF) for your `data*.pack` files.
- Local Android builds with an existing SDK or the minimal headless setup scripts (`scripts/setup-android-sdk.sh` / `.bat`), without downloading Android Studio.
- For complete setup instructions and touchscreen controls, see [ANDROID.md](ANDROID.md).

