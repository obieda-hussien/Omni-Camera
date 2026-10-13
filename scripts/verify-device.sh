#!/usr/bin/env bash
set -euo pipefail
# Keep all commands in one shell: emulator-runner otherwise executes script lines separately.
trap 'adb pull /sdcard/Pictures/Omni-Camera-UI ui-screenshots || true; adb logcat -d -s AndroidRuntime:E > ui-screenshots-runtime.txt' EXIT
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2
for name in 01-camera-portrait 02-camera-developed 03-photo-review 04-camera-landscape; do
  adb shell test -s "/sdcard/Pictures/Omni-Camera-UI/$name.png"
done
