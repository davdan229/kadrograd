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
export ANDROID_USER_HOME="$RUNNER_TEMP/kadrograd-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | avdmanager create avd --force --name kadrograd-checks --path "$ANDROID_AVD_HOME/kadrograd-checks.avd" --package 'system-images;android-30;default;x86_64' > "$results/avd.txt"
test -f "$ANDROID_AVD_HOME/kadrograd-checks.avd/config.ini"
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-30\n' "$ANDROID_AVD_HOME/kadrograd-checks.avd" > "$ANDROID_AVD_HOME/kadrograd-checks.ini"
"$ANDROID_HOME/emulator/emulator" -list-avds | tee "$results/avds.txt" | grep -qx kadrograd-checks
"$ANDROID_HOME/emulator/emulator" -avd kadrograd-checks -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on -memory 2048 -cores 2 > "$results/emulator.txt" 2>&1 &
emulator_pid=$!
for attempt in $(seq 1 60); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then cat "$results/emulator.txt"; exit 1; fi
  if adb get-state >/dev/null 2>&1; then break; fi
  sleep 2
done
timeout 120 adb wait-for-device
for attempt in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  sleep 2
done
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
adb shell input keyevent 82
status=0
test_apk="$checkout/app/build/outputs/apk/androidTest/stable/release/app-stable-release-androidTest.apk"
check_torrent() {
  adb shell am instrument -w -r -e class com.lagradost.cloudstream3.TorrentPlaybackTest ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/torrent-instrumentation.txt" || status=1
  grep -q 'OK (1 test)' "$results/torrent-instrumentation.txt" || status=1
  if grep -q 'FAILURES!!!' "$results/torrent-instrumentation.txt"; then status=1; fi
}
if [ "${KADROGRAD_TORRENT_ONLY:-false}" = true ]; then
  adb install -r -g "$checkout/app/build/outputs/apk/stable/release/app-stable-release.apk"
  adb install -r "$test_apk"
  check_torrent
  exit "$status"
fi
if [ -n "${KADROGRAD_UPGRADE_FROM:-}" ]; then
  curl --fail --location --retry 2 --max-time 90 "$KADROGRAD_UPGRADE_FROM" --output "$RUNNER_TEMP/kadrograd-previous.apk"
  printf '%s  %s\n' "$KADROGRAD_UPGRADE_SHA256" "$RUNNER_TEMP/kadrograd-previous.apk" | sha256sum --check
  adb install -r -g "$RUNNER_TEMP/kadrograd-previous.apk"
  adb install -r "$test_apk"
  adb shell am instrument -w -r -e class 'com.lagradost.cloudstream3.UpgradeStateTest#markBeforeUpgrade' ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/upgrade-before.txt" || status=1
  grep -q 'OK (1 test)' "$results/upgrade-before.txt" || status=1
fi
adb install -r -g "$checkout/app/build/outputs/apk/stable/release/app-stable-release.apk"
adb shell appops set ru.kadrograd.cloudstream MANAGE_EXTERNAL_STORAGE allow
adb install -r "$test_apk"
if [ -n "${KADROGRAD_UPGRADE_FROM:-}" ]; then
  adb shell am instrument -w -r -e class 'com.lagradost.cloudstream3.UpgradeStateTest#assertAfterUpgrade' ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/upgrade-after.txt" || status=1
  grep -q 'OK (1 test)' "$results/upgrade-after.txt" || status=1
fi
adb shell am instrument -w -r -e class com.lagradost.cloudstream3.AdaptiveDownloadTest ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/instrumentation.txt" || status=1
grep -q 'OK (6 tests)' "$results/instrumentation.txt" || status=1
if grep -q 'FAILURES!!!' "$results/instrumentation.txt"; then status=1; fi
adb shell am instrument -w -r -e class com.lagradost.cloudstream3.KadrogradHomeTest ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/home-instrumentation.txt" || status=1
if ! adb pull /sdcard/Android/data/ru.kadrograd.cloudstream/files/android-checks "$results/"; then
  if adb root && timeout 20 adb wait-for-device; then
    adb pull /sdcard/Android/data/ru.kadrograd.cloudstream/files/android-checks "$results/" || true
  fi
fi
grep -q 'OK (1 test)' "$results/home-instrumentation.txt" || status=1
if grep -q 'FAILURES!!!' "$results/home-instrumentation.txt"; then status=1; fi
check_torrent
exit "$status"
