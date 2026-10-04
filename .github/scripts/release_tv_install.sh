#!/usr/bin/env bash
# qmix#334: clean hosted TV proof of the exact downloaded final signed APK.
set -euo pipefail
apk=$1
inventory=$2
receipt=$3
: "${RUNNER_TEMP:?}"
: "${ANDROID_HOME:?}"
export ANDROID_USER_HOME="$RUNNER_TEMP/release-tv-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ADB_VENDOR_KEYS="$ANDROID_USER_HOME"
mkdir -p "$ANDROID_AVD_HOME"
adb keygen "$ANDROID_USER_HOME/adbkey"
chmod 600 "$ANDROID_USER_HOME/adbkey" "$ANDROID_USER_HOME/adbkey.pub"
adb start-server
printf 'no\n' | avdmanager create avd --force --name qmixrelease \
  --package "system-images;android-36;android-tv;x86" --device tv_1080p \
  > "$RUNNER_TEMP/release-tv-emulator.log" 2>&1
"$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fxq qmixrelease
"$ANDROID_HOME/emulator/emulator" -port 5554 -avd qmixrelease -cores 2 \
  -no-window -gpu swiftshader_indirect -wipe-data -no-snapshot -noaudio \
  -no-boot-anim -no-metrics -camera-back none \
  >> "$RUNNER_TEMP/release-tv-emulator.log" 2>&1 &
emulator_pid=$!
cleanup() {
  timeout 15 adb -s emulator-5554 emu kill || true
  kill -KILL "$emulator_pid" 2>/dev/null || true
  wait "$emulator_pid" || true
}
trap cleanup EXIT
# shellcheck disable=SC2016
 timeout 600 bash -c '
  until [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null)" == "1" ]] \
    && adb -s emulator-5554 shell service check input 2>/dev/null | grep -q found; do
    sleep 2
  done
'
# Fresh AVD: no debug/test package or upgrade substitution.
if adb -s emulator-5554 shell pm path com.qmix.tv | grep -q '^package:'; then
  printf 'Clean fixture TV unexpectedly contains QMix\n' >&2
  exit 1
fi
adb -s emulator-5554 install "$apk"
installed=$(adb -s emulator-5554 shell pm path com.qmix.tv | tr -d '\r')
[[ "$installed" == package:* && "$installed" != *$'\n'* ]]
adb -s emulator-5554 pull "${installed#package:}" "$RUNNER_TEMP/installed-release.apk"
# Bind installed package bytes/certificate/version to final distribution inventory.
python3 - "$apk" "$inventory" "$RUNNER_TEMP/installed-release.apk" <<'PY'
import pathlib, sys
sys.path.insert(0, '.github/scripts')
import release_payload as p
import release_signing as s
apk, inventory, installed = map(pathlib.Path, sys.argv[1:])
value = p.read_json(inventory)
p.require(p.sha256(apk) == p.sha256(installed), 'installed APK bytes differ')
s.verify_apk(installed, value['identity'], s.sdk_tools(), value['certificateSha256'])
PY
adb -s emulator-5554 shell dumpsys package com.qmix.tv > "$RUNNER_TEMP/installed-package.txt"
timeout 60 adb -s emulator-5554 shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p com.qmix.tv \
  > "$RUNNER_TEMP/leanback-resolution.txt" 2>&1
# --brief also prints priority/match metadata. Accept one exact resolved component.
mapfile -t activities < <(tr -d '\r' < "$RUNNER_TEMP/leanback-resolution.txt" | grep '/')
if [[ ${#activities[@]} -ne 1 ]]; then
  printf 'Leanback resolution did not return one component\n' >&2
  exit 1
fi
activity=${activities[0]}
[[ "$activity" == com.qmix.tv/.MainActivity || "$activity" == com.qmix.tv/com.qmix.tv.MainActivity ]]
# Implicit starts require DEFAULT; use the verified TV launcher without changing its filter.
timeout 60 adb -s emulator-5554 shell am start -W -a android.intent.action.MAIN \
  -c android.intent.category.LEANBACK_LAUNCHER -n "$activity" > "$RUNNER_TEMP/leanback-launch.txt" 2>&1
grep -q '^Status: ok' "$RUNNER_TEMP/leanback-launch.txt"
[[ -n "$(timeout 60 adb -s emulator-5554 shell pidof com.qmix.tv | tr -d '\r')" ]]
timeout 60 adb -s emulator-5554 shell dumpsys activity activities > "$RUNNER_TEMP/leanback-activity.txt"
grep -E 'mResumedActivity|topResumedActivity' "$RUNNER_TEMP/leanback-activity.txt" | grep -q 'com.qmix.tv/.MainActivity'
python3 - "$apk" "$inventory" "$receipt" <<'PY'
import pathlib, sys
sys.path.insert(0, '.github/scripts')
import release_payload as p
apk, inventory, receipt = map(pathlib.Path, sys.argv[1:])
value = p.read_json(inventory)
p.write_json(receipt, {'identity': value['identity'], 'apkSha256': p.sha256(apk),
                     'certificateSha256': value['certificateSha256'],
                     'verification': 'fresh hosted TV install + pulled installed bytes + Leanback foreground launch',
                     'production': False})
PY
