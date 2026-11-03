#!/usr/bin/env bash
set -euo pipefail
checkout=$(realpath "${1:?CloudStream checkout is required}")
results="$checkout/android-checks"
mkdir -p "$results"
cleanup() {
  adb logcat -d > "$results/logcat.txt" 2>&1 || true
  adb emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT
sudo chmod 666 /dev/kvm
sdkmanager 'emulator' 'system-images;android-30;default;x86_64' > "$results/sdk.txt"
echo no | avdmanager create avd --force --name kadrograd-checks --package 'system-images;android-30;default;x86_64' > "$results/avd.txt"
"$ANDROID_HOME/emulator/emulator" -avd kadrograd-checks -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on -memory 2048 -cores 2 > "$results/emulator.txt" 2>&1 &
adb wait-for-device
for attempt in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  sleep 2
done
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
adb shell input keyevent 82
adb install -r "$checkout/app/build/outputs/apk/stable/debug/app-stable-debug.apk"
adb install -r "$checkout/app/build/outputs/apk/androidTest/stable/debug/app-stable-debug-androidTest.apk"
adb shell am instrument -w -r -e class com.lagradost.cloudstream3.AdaptiveDownloadTest ru.kadrograd.cloudstream.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/instrumentation.txt"
grep -q 'OK (3 tests)' "$results/instrumentation.txt"
! grep -q 'FAILURES!!!' "$results/instrumentation.txt"
