#!/usr/bin/env bash
set -euo pipefail
checkout=$(realpath "${1:?CloudStream checkout is required}")
results="$checkout/android-checks"
android_api="${KADROGRAD_ANDROID_API:-35}"
case "$android_api" in 30|33|35|36) ;; *) echo 'Unsupported Android API'; exit 1 ;; esac
check_suite="${KADROGRAD_CHECK_SUITE:-all}"
case "$check_suite" in all|ui|home|transfer) ;; *) echo 'Unsupported check suite'; exit 1 ;; esac
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
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb logcat -c
adb logcat -v threadtime '*:W' 'TestRunner:I' 'KadrogradDownloadTest:I' 'KadrogradUhdTest:I' > "$results/logcat.txt" 2>&1 &
logcat_pid=$!
status=0
verify_offline_restart() {
  adb shell am force-stop ru.kadrograd.cloudstream
  adb shell cmd connectivity airplane-mode enable
  adb shell svc wifi disable
  adb shell svc data disable
  timeout 120 adb shell am instrument -w -r -e class com.lagradost.cloudstream3.OfflineDownloadRestartTest ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/offline-restart.txt" || status=1
  adb shell cmd connectivity airplane-mode disable
  adb shell svc wifi enable
  adb shell svc data enable
  grep -q 'OK (1 test)' "$results/offline-restart.txt" || status=1
}
verify_crash_recovery() {
  # This deliberately crashes only the remote CI emulator process.
  timeout 45 adb shell am instrument -w -r -e class 'com.lagradost.cloudstream3.DownloadCrashRecoveryTest#recordQueueAndTriggerNativeCrash' ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/intentional-crash.txt" || true
  timeout 90 adb shell am instrument -w -r -e class 'com.lagradost.cloudstream3.DownloadCrashRecoveryTest#queueCanBeCancelledAfterNativeCrashWithoutRestartingIt' ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/crash-recovery.txt" || status=1
  grep -q 'OK (1 test)' "$results/crash-recovery.txt" || status=1
}
verify_live_transfer() {
  timeout 1020 python3 - <<'PY' | tee "$results/live-transfer.txt" || status=1
import os, shlex, subprocess, sys
args = ['am', 'instrument', '-w', '-r', '-e', 'movieQuery', os.environ['KADROGRAD_MOVIE_QUERY'],
        '-e', 'movieQuality', os.environ.get('KADROGRAD_MOVIE_QUALITY') or '1080',
        '-e', 'class', 'com.lagradost.cloudstream3.LiveDownloadTest',
        'ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner']
sys.exit(subprocess.run(['adb', 'shell', shlex.join(args)], timeout=1010).returncode)
PY
  grep -q 'OK (1 test)' "$results/live-transfer.txt" || status=1
}
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
if [ "$check_suite" = transfer ]; then
  test -n "$KADROGRAD_MOVIE_QUERY"
  verify_live_transfer
  exit "$status"
fi
if [ "$check_suite" != all ]; then
  if [ "$check_suite" = ui ]; then
    classes='com.lagradost.cloudstream3.AdaptiveDownloadTest,com.lagradost.cloudstream3.KadrogradHomeTest#sourcePickerListsAllBundledSourcesAndKeepsLaterFilterChanges'
    expected='OK (12 tests)'
  else
    classes='com.lagradost.cloudstream3.KadrogradHomeTest#allBundledSourcesHaveAVisibleHeroAndPlayButton'
    expected='OK (1 test)'
  fi
  timeout 180 adb shell am instrument -w -r -e keepOfflineFixture true -e class "$classes" ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/selected-instrumentation.txt" || status=1
  grep -q "$expected" "$results/selected-instrumentation.txt" || status=1
  if [ "$check_suite" = ui ] && [ "$status" = 0 ]; then verify_offline_restart; fi
  if [ "$check_suite" = ui ] && [ "$status" = 0 ]; then verify_crash_recovery; fi
  if [ "$check_suite" = ui ] && [ "$status" = 0 ] && [ -n "${KADROGRAD_MOVIE_QUERY:-}" ]; then verify_live_transfer; fi
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
timeout 300 adb shell am instrument -w -r -e keepOfflineFixture true -e class com.lagradost.cloudstream3.AdaptiveDownloadTest ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/instrumentation.txt" || status=1
grep -q 'OK (11 tests)' "$results/instrumentation.txt" || status=1
if grep -q 'FAILURES!!!' "$results/instrumentation.txt"; then status=1; fi
if [ "$status" = 0 ]; then verify_offline_restart; fi
if [ "$status" = 0 ]; then verify_crash_recovery; fi
adb shell am instrument -w -r -e class com.lagradost.cloudstream3.KadrogradHomeTest ru.kadrograd.cloudstream.test/androidx.test.runner.AndroidJUnitRunner | tee "$results/home-instrumentation.txt" || status=1
if ! adb pull /sdcard/Android/data/ru.kadrograd.cloudstream/files/android-checks "$results/"; then
  if adb root && timeout 20 adb wait-for-device; then
    adb pull /sdcard/Android/data/ru.kadrograd.cloudstream/files/android-checks "$results/" || true
  fi
fi
grep -q 'OK (2 tests)' "$results/home-instrumentation.txt" || status=1
if grep -q 'FAILURES!!!' "$results/home-instrumentation.txt"; then status=1; fi
exit "$status"
