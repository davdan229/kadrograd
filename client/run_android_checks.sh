#!/usr/bin/env bash
set -euo pipefail
checkout=$(realpath "${1:?CloudStream checkout is required}")
results="$checkout/android-checks"
android_api="${KADROGRAD_ANDROID_API:-35}"
case "$android_api" in 30|35|36) ;; *) echo 'Unsupported Android API'; exit 1 ;; esac
check_suite="${KADROGRAD_CHECK_SUITE:-all}"
case "$check_suite" in all|ui|home) ;; *) echo 'Unsupported check suite'; exit 1 ;; esac
mkdir -p "$results"
logcat_pid=''
cleanup() {
  if [ -n "$logcat_pid" ]; then kill "$logcat_pid" 2>/dev/null || true; fi
  timeout 10 adb logcat -d -v threadtime -t 4000 >> "$results/logcat.txt" 2>&1 || true
  timeout 10 adb emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT
sudo chmod 666 /dev/kvm
sdkmanager 'emulator' "system-images;android-$android_api;default;x86_64" > "$results/sdk.txt"
export ANDROID_USER_HOME="$RUNNER_TEMP/kadrograd-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | avdmanager create avd --force --name kadrograd-checks --path "$ANDROID_AVD_HOME/kadrograd-checks.avd" --package "system-images;android-$android_api;default;x86_64" > "$results/avd.txt"
test -f "$ANDROID_AVD_HOME/kadrograd-checks.avd/config.ini"
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-%s\n' "$ANDROID_AVD_HOME/kadrograd-checks.avd" "$android_api" > "$ANDROID_AVD_HOME/kadrograd-checks.ini"
"$ANDROID_HOME/emulator/emulator" -list-avds | tee "$results/avds.txt" | grep -qx kadrograd-checks
"$ANDROID_HOME/emulator/emulator" -avd kadrograd-checks -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan -accel on -memory 2048 -cores 2 > "$results/emulator.txt" 2>&1 &
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
adb logcat -c
adb logcat -v threadtime '*:W' 'TestRunner:I' 'KadrogradDownloadTest:I' > "$results/logcat.txt" 2>&1 &
logcat_pid=$!
status=0
test_apk="$checkout/app/build/outputs/apk/androidTest/stable/release/app-stable-release-androidTest.apk"
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
adb shell dumpsys deviceidle whitelist +ru.kadrograd.cloudstream
adb install -r "$test_apk"
if [ -n "${KADROGRAD_UPGRADE_FROM:-}" ]; then
  adb shell am instrument -w -r -e class 'com.lagradost.cloudstream3.UpgradeStateTest#assertAfterUpgrade' ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/upgrade-after.txt" || status=1
  grep -q 'OK (1 test)' "$results/upgrade-after.txt" || status=1
fi
if [ "$check_suite" != all ]; then
  if [ "$check_suite" = ui ]; then
    classes='com.lagradost.cloudstream3.AdaptiveDownloadTest,com.lagradost.cloudstream3.KadrogradHomeTest#sourcePickerListsAllBundledSourcesAndKeepsLaterFilterChanges'
    expected='OK (9 tests)'
  else
    classes='com.lagradost.cloudstream3.KadrogradHomeTest#allBundledSourcesHaveAVisibleHeroAndPlayButton'
    expected='OK (1 test)'
  fi
  timeout 300 adb shell am instrument -w -r -e class "$classes" ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/selected-instrumentation.txt" || status=1
  grep -q "$expected" "$results/selected-instrumentation.txt" || status=1
  if [ "$status" != 0 ]; then
    timeout 10 adb shell dumpsys activity lastanr > "$results/last-anr.txt" 2>&1 || true
    timeout 10 adb shell dumpsys activity activities > "$results/activities.txt" 2>&1 || true
    timeout 10 adb shell 'kill -3 $(pidof ru.kadrograd.cloudstream)' || true
    if adb root && timeout 20 adb wait-for-device; then
      timeout 15 adb pull /data/anr "$results/anr" || true
    fi
  fi
  if ! timeout 15 adb pull /sdcard/Android/data/ru.kadrograd.cloudstream/files/android-checks "$results/"; then
    if adb root && timeout 20 adb wait-for-device; then
      timeout 15 adb pull /sdcard/Android/data/ru.kadrograd.cloudstream/files/android-checks "$results/" || true
    fi
  fi
  exit "$status"
fi
adb shell am instrument -w -r -e class com.lagradost.cloudstream3.AdaptiveDownloadTest ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/instrumentation.txt" || status=1
grep -q 'OK (8 tests)' "$results/instrumentation.txt" || status=1
if grep -q 'FAILURES!!!' "$results/instrumentation.txt"; then status=1; fi
adb shell am instrument -w -r -e class com.lagradost.cloudstream3.KadrogradHomeTest ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/home-instrumentation.txt" || status=1
if ! adb pull /sdcard/Android/data/ru.kadrograd.cloudstream/files/android-checks "$results/"; then
  if adb root && timeout 20 adb wait-for-device; then
    adb pull /sdcard/Android/data/ru.kadrograd.cloudstream/files/android-checks "$results/" || true
  fi
fi
grep -q 'OK (2 tests)' "$results/home-instrumentation.txt" || status=1
if grep -q 'FAILURES!!!' "$results/home-instrumentation.txt"; then status=1; fi
exit "$status"
