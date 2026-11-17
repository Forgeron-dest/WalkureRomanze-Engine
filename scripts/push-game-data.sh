#!/usr/bin/env bash
# ==============================================================================
# Helper Script to push Walkure Romanze GameData (*.pack) files to Android via ADB
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Find adb
ADB_BIN="adb"
if ! command -v adb >/dev/null 2>&1; then
    if [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
        ADB_BIN="$HOME/Android/Sdk/platform-tools/adb"
    elif [ -x "$HOME/.android-sdk/platform-tools/adb" ]; then
        ADB_BIN="$HOME/.android-sdk/platform-tools/adb"
    else
        echo "Error: adb command not found. Please ensure platform-tools are installed."
        exit 1
    fi
fi

echo "Checking connected Android devices via $ADB_BIN..."
DEVICES=$("$ADB_BIN" devices | grep -v "List" | grep -v "^$" | cut -f1)

if [ -z "$DEVICES" ]; then
    echo "No Android device found via ADB!"
    echo "Please connect your phone via USB with USB Debugging enabled, or copy files via MTP."
    exit 1
fi

DEVICE_ID=$(echo "$DEVICES" | head -n1)
echo "Found connected device: $DEVICE_ID"

# Locate GameData directory
GAMEDATA_DIR=""
for CANDIDATE in "$PROJECT_ROOT/../GameData" "$PROJECT_ROOT/GameData" "$PROJECT_ROOT/../../GameData"; do
    if [ -d "$CANDIDATE" ] && ls "$CANDIDATE"/data*.pack >/dev/null 2>&1; then
        GAMEDATA_DIR="$CANDIDATE"
        break
    fi
done

if [ -z "$GAMEDATA_DIR" ]; then
    echo "Could not find directory containing data*.pack files!"
    exit 1
fi

echo "Source GameData: $GAMEDATA_DIR"
REMOTE_DIR="/sdcard/Android/data/org.walkure.android/files/GameData"

echo "Creating remote directory: $REMOTE_DIR..."
"$ADB_BIN" -s "$DEVICE_ID" shell "mkdir -p '$REMOTE_DIR'"

echo "Pushing data*.pack files (this may take a few minutes for ~3.9 GB)..."
for PACK in "$GAMEDATA_DIR"/data*.pack; do
    if [ -f "$PACK" ]; then
        echo "Pushing $(basename "$PACK")..."
        "$ADB_BIN" -s "$DEVICE_ID" push "$PACK" "$REMOTE_DIR/"
    fi
done

# Push walkure.keys if present
if [ -f "$GAMEDATA_DIR/walkure.keys" ]; then
    echo "Pushing walkure.keys..."
    "$ADB_BIN" -s "$DEVICE_ID" push "$GAMEDATA_DIR/walkure.keys" "$REMOTE_DIR/../walkure.keys"
fi

# Locate movie directory
MOVIE_DIR=""
for CANDIDATE_MOVIE in "$GAMEDATA_DIR/movie" "$GAMEDATA_DIR/../movie" "$GAMEDATA_DIR/../GameData/movie"; do
    if [ -d "$CANDIDATE_MOVIE" ]; then
        MOVIE_DIR="$CANDIDATE_MOVIE"
        break
    fi
done

if [ -n "$MOVIE_DIR" ]; then
    echo "Found movie cutscenes in $MOVIE_DIR"
    "$ADB_BIN" -s "$DEVICE_ID" shell "mkdir -p '$REMOTE_DIR/movie'"

    # If ffmpeg is available, ensure hardware-accelerated .mp4 versions exist for Android
    if command -v ffmpeg >/dev/null 2>&1; then
        echo "Checking/transcoding .ogv cutscenes to hardware-accelerated .mp4 for Android..."
        for OGV in "$MOVIE_DIR"/*.ogv; do
            if [ -f "$OGV" ]; then
                MP4="${OGV%.ogv}.mp4"
                if [ ! -f "$MP4" ] || [ ! -s "$MP4" ]; then
                    echo "Transcoding $(basename "$OGV") -> $(basename "$MP4")..."
                    ffmpeg -loglevel error -y -i "$OGV" -map 0:v:0 -map 0:a:0? -c:v libx264 -preset fast -crf 22 -c:a aac -b:a 192k -movflags +faststart "$MP4" || true
                fi
            fi
        done
    fi

    echo "Pushing movie cutscenes..."
    for MOVIE in "$MOVIE_DIR"/*.mp4 "$MOVIE_DIR"/*.ogv; do
        if [ -f "$MOVIE" ]; then
            echo "Pushing $(basename "$MOVIE")..."
            "$ADB_BIN" -s "$DEVICE_ID" push "$MOVIE" "$REMOTE_DIR/movie/"
        fi
    done
fi

echo "============================================================"
echo " GameData transfer complete!"
echo " You can now install and launch Walkure Romanze on your device."
echo "============================================================"
