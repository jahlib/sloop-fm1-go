#!/usr/bin/env bash
# Build the Sloop Go Android APK (see android/README.md).
#
# Usage:
#   ./build-android.sh           # debug APK (default)
#   ./build-android.sh release   # release APK (debug-signed)
#
# Environment (optional):
#   ANDROID_SDK_ROOT / ANDROID_HOME   path to Android SDK
#   JAVA_HOME                         JDK 17+ (Gradle uses it automatically if set)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$ROOT/android"
PROPS="$ANDROID_DIR/local.properties"

find_sdk() {
    if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
        echo "$ANDROID_SDK_ROOT"
        return
    fi
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
        echo "$ANDROID_HOME"
        return
    fi
    for candidate in "$HOME/android-sdk" "$HOME/Android/Sdk"; do
        if [ -d "$candidate" ]; then
            echo "$candidate"
            return
        fi
    done
    return 1
}

ensure_local_properties() {
    if [ -f "$PROPS" ]; then
        return
    fi
    local sdk
    if ! sdk="$(find_sdk)"; then
        echo "build-android.sh: Android SDK not found." >&2
        echo "  Set ANDROID_SDK_ROOT or create $PROPS with:" >&2
        echo "    sdk.dir=/path/to/android-sdk" >&2
        echo "  See android/README.md for sdkmanager setup." >&2
        exit 1
    fi
    printf 'sdk.dir=%s\n' "$sdk" >"$PROPS"
    echo "build-android.sh: wrote $PROPS (sdk.dir=$sdk)"
}

variant="${1:-debug}"
case "$variant" in
    debug | "")
        gradle_task="assembleDebug"
        apk_rel="app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        gradle_task="assembleRelease"
        apk_rel="app/build/outputs/apk/release/app-release.apk"
        ;;
    *)
        echo "build-android.sh: unknown variant '$variant' (use: debug | release)" >&2
        exit 1
        ;;
esac

ensure_local_properties

if ! command -v java >/dev/null 2>&1; then
    echo "build-android.sh: java not found (need JDK 17+)" >&2
    exit 1
fi

cd "$ANDROID_DIR"
chmod +x ./gradlew 2>/dev/null || true
./gradlew "$gradle_task"

APK="$ANDROID_DIR/$apk_rel"
if [ ! -f "$APK" ]; then
    echo "build-android.sh: expected APK missing: $APK" >&2
    exit 1
fi

echo ""
echo "OK: $APK"
echo "Install: adb install -r \"$APK\""
