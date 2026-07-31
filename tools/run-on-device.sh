#!/bin/bash
#
# Installs AirTV on a device and streams the logs you want during a real AirPlay test.
#
#   tools/run-on-device.sh                 # use the already-connected adb device
#   tools/run-on-device.sh 192.168.8.42    # connect over the network first (Google TV)
#
# Leave this running, then start Screen Mirroring on the iPhone and watch the handshake.
#
set -eo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
APK="${APK:-$REPO/dist/AirTV.apk}"

if [ -n "$1" ]; then
    TARGET="$1"
    [[ "$TARGET" == *:* ]] || TARGET="$TARGET:5555"
    echo ">>> connecting to $TARGET (accept the prompt on the TV if it appears)"
    "$ADB" connect "$TARGET"
fi

"$ADB" wait-for-device
DEVICE=$("$ADB" shell getprop ro.product.model | tr -d '\r')
ANDROID_VER=$("$ADB" shell getprop ro.build.version.release | tr -d '\r')
echo ">>> device: $DEVICE (Android $ANDROID_VER)"

[ -f "$APK" ] || { echo "APK not found at $APK — run ./gradlew assembleRelease"; exit 1; }
echo ">>> installing $(basename "$APK")"
"$ADB" install -r "$APK"

echo ">>> launching AirTV"
"$ADB" shell am start -a android.intent.action.MAIN \
    -c android.intent.category.LEANBACK_LAUNCHER \
    -n com.airtv.receiver/.ui.MainActivity >/dev/null

sleep 2
echo
echo "=================================================================="
echo " Now on the iPhone/iPad: swipe down from the top-right corner"
echo " -> Screen Mirroring -> pick \"$DEVICE\""
echo
echo " Watching logs (Ctrl-C to stop). What to look for:"
echo "   'listening on port N'      server is up and advertised"
echo "   'client request: name=...' your iPhone was seen"
echo "   'video codec: H.264'       the stream was negotiated"
echo "   'decoder started'          frames are being decoded"
echo "=================================================================="
echo

"$ADB" logcat -c
exec "$ADB" logcat -v time \
    AirPlayNative:V AirPlayLib:V AirPlayReceiver:V VideoPipeline:V AudioPipeline:V \
    AndroidRuntime:E MediaCodec:E "*:S"
