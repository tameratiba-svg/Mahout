#!/bin/sh
# Build an APK, install it on the attached device and launch MainActivity.
# Device: MOB8N_SERIAL if set, else the single device listed by `adb devices`; several (or none) -> exits with the list.
# MOB8N_VARIANT=debug (default) | profile. Both are signed with the debug keystore, so `install -r` swaps them and keeps data.
# run-as com.mob8n only works on the debug build (profile is not debuggable): switch back with MOB8N_VARIANT=debug ./install.sh.
set -e
cd "$(dirname "$0")"
ADB_BIN="/Users/ankur/Library/Android/sdk/platform-tools/adb"
SERIAL="${MOB8N_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  DEVICES="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  COUNT="$(printf '%s' "$DEVICES" | grep -c . || true)"
  if [ "$COUNT" != "1" ]; then
    echo "install.sh: expected exactly one attached device, found $COUNT. Set MOB8N_SERIAL to one of:" >&2
    "$ADB_BIN" devices -l | awk 'NR > 1 && NF' >&2
    exit 1
  fi
  SERIAL="$DEVICES"
fi
ADB="$ADB_BIN -s $SERIAL"
echo "install.sh: device $SERIAL"
VARIANT="${MOB8N_VARIANT:-debug}"
case "$VARIANT" in
  debug) TASK=assembleDebug ;;
  profile) TASK=assembleProfile ;;
  *) echo "MOB8N_VARIANT must be debug or profile (got '$VARIANT')" >&2; exit 2 ;;
esac
./build.sh "$TASK"
$ADB install -r "app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
$ADB shell am start -n com.mob8n/.MainActivity
