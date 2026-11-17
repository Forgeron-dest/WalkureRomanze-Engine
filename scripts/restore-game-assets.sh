#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/android/src/main/assets"

GAME_DIR="${WALKURE_GAME_DIR:-$PROJECT_ROOT/..}"
OPTIONAL=false

while (($#)); do
    case "$1" in
        --game-dir)
            if (($# < 2)); then
                echo "Error: --game-dir requires a path." >&2
                exit 2
            fi
            GAME_DIR="$2"
            shift 2
            ;;
        --optional)
            OPTIONAL=true
            shift
            ;;
        -h|--help)
            echo "Usage: bash scripts/restore-game-assets.sh [--game-dir PATH] [--optional]"
            echo "Restores ignored Android assets from a local Walkure Romanze installation."
            exit 0
            ;;
        *)
            echo "Error: unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

if [[ ! -d "$GAME_DIR/GameData" ]]; then
    if [[ "$OPTIONAL" == true ]]; then
        echo "No local GameData folder found; skipping owner-provided assets."
        exit 0
    fi
    echo "Error: GameData folder not found under '$GAME_DIR'. Clone this repository into your game folder or pass --game-dir." >&2
    exit 1
fi

GAME_DIR="$(cd "$GAME_DIR" && pwd)"
shopt -s nullglob
PACKS=("$GAME_DIR"/GameData/data*.pack)
if ((${#PACKS[@]} == 0)); then
    if [[ "$OPTIONAL" == true ]]; then
        echo "No data*.pack files found in '$GAME_DIR/GameData'; skipping owner-provided assets."
        exit 0
    fi
    echo "Error: no data*.pack files found in '$GAME_DIR/GameData'." >&2
    exit 1
fi

mkdir -p "$ASSETS_DIR"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

KEY_FILE="$GAME_DIR/GameData/walkure.keys"
KEY_SIZE=0
if [[ -f "$KEY_FILE" ]]; then
    KEY_SIZE="$(wc -c < "$KEY_FILE")"
fi

if [[ "$KEY_SIZE" -ne 512 ]]; then
    if [[ -f "$GAME_DIR/DLL/key.fkey" && -f "$GAME_DIR/WALKURE_ROMANZE.exe" ]]; then
        echo "Generating GameData/walkure.keys from the local game installation..."
        (
            cd "$GAME_DIR"
            bash "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" run --args=export-keys
        )
        KEY_FILE="$GAME_DIR/GameData/walkure.keys"
        KEY_SIZE=0
        if [[ -f "$KEY_FILE" ]]; then
            KEY_SIZE="$(wc -c < "$KEY_FILE")"
        fi
    fi
fi

if [[ "$KEY_SIZE" -eq 512 ]]; then
    cp "$KEY_FILE" "$ASSETS_DIR/walkure.keys"
    echo "Restored portable decryption keys."
elif [[ "$OPTIONAL" == true ]]; then
    echo "Warning: portable keys unavailable; Android users will need to provide them separately."
else
    echo "Warning: portable keys unavailable. Supply GameData/walkure.keys or the original DLL/key.fkey and WALKURE_ROMANZE.exe." >&2
fi

FONT_FILE=""
for candidate in "$GAME_DIR/WalkureCustomV4.ttf" "$GAME_DIR/GameData/WalkureCustomV4.ttf"; do
    if [[ -s "$candidate" ]]; then
        FONT_FILE="$candidate"
        break
    fi
done

if [[ -n "$FONT_FILE" ]]; then
    cp "$FONT_FILE" "$ASSETS_DIR/WalkureCustomV4.ttf"
    echo "Restored the custom font."
else
    echo "Warning: WalkureCustomV4.ttf not found; the APK will use its fallback font."
fi

if [[ "$KEY_SIZE" -eq 512 ]]; then
    ICON_TEMP_DIR="$TEMP_DIR/icons"
    echo "Extracting Mio's launcher portrait from the local game archive..."
    (
        cd "$GAME_DIR"
        bash "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" :run \
            --args="extract chara_美桜.png $ICON_TEMP_DIR"
    )
    ICON_SOURCE="$(find "$ICON_TEMP_DIR" -type f -path '*/saveload/chara_美桜.png' -print -quit)"
    if [[ -n "$ICON_SOURCE" ]]; then
        bash "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" \
            "-PlauncherIconSource=$ICON_SOURCE" restoreLauncherIcons
    else
        echo "Warning: Mio's save/load portrait was not found; keeping the generic launcher icon."
    fi
else
    echo "Warning: launcher portrait not restored because portable game keys are unavailable."
fi

LOGO_FILE=""
for candidate in "$GAME_DIR/ricotta_logo.mp4" "$GAME_DIR/GameData/movie/ricotta_logo.mp4"; do
    if [[ -s "$candidate" ]]; then
        LOGO_FILE="$candidate"
        break
    fi
done

if [[ -n "$LOGO_FILE" ]]; then
    cp "$LOGO_FILE" "$ASSETS_DIR/ricotta_logo.mp4"
    echo "Restored the startup logo video."
elif [[ ! -s "$ASSETS_DIR/ricotta_logo.mp4" ]] && command -v ffmpeg >/dev/null 2>&1; then
    LOGO_TEMP_DIR="$TEMP_DIR/logo"
    echo "Extracting and converting the startup logo from the local game archive..."
    (
        cd "$GAME_DIR"
        bash "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" run \
            --args="extract ricotta_logo.ogv $LOGO_TEMP_DIR"
    )
    OGV_FILE="$(find "$LOGO_TEMP_DIR" -type f -name ricotta_logo.ogv -print -quit)"
    if [[ -n "$OGV_FILE" ]]; then
        ffmpeg -loglevel error -y -i "$OGV_FILE" -map 0:v:0 -map '0:a:0?' \
            -c:v libx264 -preset fast -crf 22 -c:a aac -b:a 192k \
            -movflags +faststart "$ASSETS_DIR/ricotta_logo.mp4"
        echo "Restored the startup logo video."
    else
        echo "Warning: ricotta_logo.ogv was not found in the game archives."
    fi
elif [[ ! -s "$ASSETS_DIR/ricotta_logo.mp4" ]]; then
    echo "Warning: startup logo not restored; install ffmpeg to extract it from the game archive."
fi
