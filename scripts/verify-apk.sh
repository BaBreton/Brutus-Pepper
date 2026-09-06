#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
SDK_ROOT="${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
AAPT="${AAPT:-$SDK_ROOT/build-tools/35.0.0/aapt}"

fail() {
    printf 'APK verification failed: %s\n' "$1" >&2
    exit 1
}

[[ -f "$APK" ]] || fail "APK not found at $APK"
[[ -x "$AAPT" ]] || fail "aapt not found at $AAPT"

badging="$($AAPT dump badging "$APK")"
permissions="$($AAPT dump permissions "$APK")"
manifest="$($AAPT dump xmltree "$APK" AndroidManifest.xml)"

grep -Fq "package: name='com.brutus.pepper'" <<<"$badging" || fail "unexpected package name"
grep -Fq "sdkVersion:'23'" <<<"$badging" || fail "minimum SDK is not 23"
grep -Fq "targetSdkVersion:'35'" <<<"$badging" || fail "target SDK is not 35"

permission_count="$(grep -c '^uses-permission:' <<<"$permissions")"
[[ "$permission_count" -eq 3 ]] || fail "expected exactly 3 permissions, found $permission_count"
grep -Fq "uses-permission: name='com.aldebaran.permission.ROBOT'" <<<"$permissions" || fail "robot permission missing"
grep -Fq "uses-permission: name='android.permission.INTERNET'" <<<"$permissions" || fail "internet permission missing"
grep -Fq "uses-permission: name='android.permission.RECORD_AUDIO'" <<<"$permissions" || fail "microphone permission missing"

grep -Fq 'com.brutus.pepper.MainActivity' <<<"$manifest" || fail "main activity missing"
grep -Fq 'android.intent.category.LAUNCHER' <<<"$manifest" || fail "launcher category missing"

for forbidden in \
    'E: receiver' \
    'E: service' \
    'E: provider' \
    'android.intent.action.BOOT_COMPLETED' \
    'android.intent.category.HOME' \
    'android.app.device_admin' \
    'android.accessibilityservice' \
    'android.permission.REQUEST_INSTALL_PACKAGES' \
    'android.permission.RECEIVE_BOOT_COMPLETED'; do
    if grep -Fq "$forbidden" <<<"$manifest"; then
        fail "forbidden manifest entry: $forbidden"
    fi
done

# Provider credentials stay on the paired antenna, never in the tablet settings.

printf 'APK verification passed: %s\n' "$APK"
printf 'Package: com.brutus.pepper\n'
printf 'Permissions: INTERNET, RECORD_AUDIO, com.aldebaran.permission.ROBOT\n'
printf 'Components: one launcher activity; no receiver, service, provider, boot or home intent\n'
