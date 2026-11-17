#!/usr/bin/env bash
# ==============================================================================
# Minimal Headless Android SDK Bootstrapper for Walkure Romanze
# Installs only the essential command-line tools (~140MB) required to compile
# the Android .apk without downloading Android Studio or the heavy IDE (~3GB).
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_SDK_DIR="${ANDROID_HOME:-$HOME/.android-sdk}"

echo "============================================================"
echo " Walkure Romanze — Minimal Headless Android SDK Setup"
echo "============================================================"
echo "Target SDK Directory: $TARGET_SDK_DIR"

# Check if an existing SDK is already configured in local.properties
LOCAL_PROPS="$PROJECT_ROOT/local.properties"
if [ -f "$LOCAL_PROPS" ] && grep -q "sdk.dir" "$LOCAL_PROPS"; then
    EXISTING_SDK=$(grep "sdk.dir" "$LOCAL_PROPS" | cut -d'=' -f2 | tr -d '\r')
    if [ -d "$EXISTING_SDK" ]; then
        TARGET_SDK_DIR="$EXISTING_SDK"
        if [ -d "$TARGET_SDK_DIR/platforms/android-36" ] && [ -d "$TARGET_SDK_DIR/build-tools/36.0.0" ]; then
            echo "Found required Android SDK packages in local.properties: $TARGET_SDK_DIR"
            echo "You are ready to build! Run:"
            echo "  ./gradlew buildApk"
            exit 0
        fi
    fi
fi

# Detect Operating System
OS="$(uname -s)"
case "$OS" in
    Linux*)
        TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        ;;
    Darwin*)
        TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
        ;;
    *)
        echo "Unsupported OS: $OS. Please install the Android command-line tools manually."
        exit 1
        ;;
esac

mkdir -p "$TARGET_SDK_DIR/cmdline-tools"
TEMP_ZIP="$TARGET_SDK_DIR/cmdline-tools-temp.zip"

if [ ! -x "$TARGET_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "Downloading official Google command-line tools (~140 MB)..."
    if command -v curl >/dev/null 2>&1; then
        curl -fSL "$TOOLS_URL" -o "$TEMP_ZIP"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$TOOLS_URL" -O "$TEMP_ZIP"
    else
        echo "Error: neither curl nor wget found. Please install curl."
        exit 1
    fi

    echo "Extracting tools..."
    rm -rf "$TARGET_SDK_DIR/cmdline-tools/latest"
    unzip -q -o "$TEMP_ZIP" -d "$TARGET_SDK_DIR/cmdline-tools"
    mv "$TARGET_SDK_DIR/cmdline-tools/cmdline-tools" "$TARGET_SDK_DIR/cmdline-tools/latest"
    rm -f "$TEMP_ZIP"
fi

SDKMANAGER="$TARGET_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
chmod +x "$SDKMANAGER"

echo "Accepting Android SDK licenses..."
yes | "$SDKMANAGER" --sdk_root="$TARGET_SDK_DIR" --licenses >/dev/null 2>&1 || true

echo "Installing required platform-tools, platform 36 & build-tools..."
"$SDKMANAGER" --sdk_root="$TARGET_SDK_DIR" "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# Update local.properties in project root
echo "Writing sdk.dir to $LOCAL_PROPS..."
echo "sdk.dir=$TARGET_SDK_DIR" > "$LOCAL_PROPS"

echo ""
echo "============================================================"
echo " Android SDK successfully installed and configured!"
echo " Location: $TARGET_SDK_DIR"
echo ""
echo " You can now compile the APK by simply running:"
echo "   ./gradlew buildApk"
echo " Output will be in: build/dist/walkure-romanze.apk"
echo "============================================================"
