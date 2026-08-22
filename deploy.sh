#!/usr/bin/env bash
# Build the debug APK and install it on a connected Android phone.
#
# USB:       enable USB debugging, plug in, then ./deploy.sh
# Wireless:  ./deploy.sh 192.168.1.42        (phone's adb IP, wireless debugging on)
#
# Mirrors the "get it on a physical phone" step from the samtur dev-setup, but for a
# plain Gradle/adb app instead of Expo.
set -euo pipefail

cd "$(dirname "$0")"

if [ "${1:-}" != "" ]; then
    TARGET="$1"
    [[ "$TARGET" == *:* ]] || TARGET="$TARGET:5555"
    echo "Connecting to $TARGET ..."
    adb connect "$TARGET"
    ADB=(adb -s "$TARGET")
else
    ADB=(adb)
fi

echo "Building debug APK ..."
./gradlew --no-daemon :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
echo "Installing $APK ..."
"${ADB[@]}" install -r "$APK"

echo "Done. Look for \"Send to BYD\" in the app drawer and the share sheet."
