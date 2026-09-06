#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
fail() {
    printf 'Whisper APK verification failed: %s\n' "$1" >&2
    exit 1
}

[[ -f "$APK" ]] || fail "APK not found at $APK"
entries="$(unzip -Z1 "$APK")"
if grep -Eq '(^assets/models/ggml-|libbrutus_whisper\.so$)' <<<"$entries"; then
    fail "embedded Whisper model or JNI library still present"
fi

printf 'Whisper APK verification passed: %s\n' "$APK"
printf 'Runtime: paired Pepper antenna (configured in the app)\n'
printf 'Embedded Whisper assets: none\n'
