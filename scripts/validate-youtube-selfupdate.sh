#!/usr/bin/env bash

set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

APK="${1:?APK path is required}"
EXPECTED_CERT_SHA256="b1901f3565b6afefe8e4ee773c17de36a24016a8e6d9b648f5f0cedde42f84e9"
EXPECTED_PACKAGE="app.morphe.android.youtube"
EXPECTED_FEED="https://api.github.com/repos/heelee912/morphe-youtube-auto-update/releases/latest"

AAPT2=$(find "$ANDROID_HOME/build-tools" -type f -name aapt2 | sort -V | tail -n1)
APKSIGNER=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)
APKANALYZER=$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer | sort -V | tail -n1)
test -x "$AAPT2"
test -x "$APKSIGNER"
test -x "$APKANALYZER"

BADGING=$("$AAPT2" dump badging "$APK")
grep -Fq "package: name='$EXPECTED_PACKAGE'" <<< "$BADGING"
grep -Fq "native-code: 'arm64-v8a'" <<< "$BADGING"

CERTS=$("$APKSIGNER" verify --verbose --print-certs "$APK")
grep -Fqi "certificate SHA-256 digest: $EXPECTED_CERT_SHA256" <<< "$CERTS"

MANIFEST=$("$APKANALYZER" manifest print "$APK")
for required in \
  'android.permission.REQUEST_INSTALL_PACKAGES' \
  'android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION' \
  'android.permission.RECEIVE_BOOT_COMPLETED' \
  'app.morphe.extension.shared.updater.SelfUpdateJobService' \
  'app.morphe.extension.shared.updater.SelfUpdateReceiver' \
  'app.morphe.extension.shared.updater.SelfUpdateInstallReceiver' \
  "$EXPECTED_FEED"; do
  grep -Fq "$required" <<< "$MANIFEST"
done

DEX_STRINGS="$RUNNER_TEMP/morphe-selfupdate-dex-strings.txt"
unzip -p "$APK" 'classes*.dex' | strings > "$DEX_STRINGS"
for class_name in \
  'app/morphe/extension/shared/updater/SelfUpdater' \
  'app/morphe/extension/shared/updater/SelfUpdateJobService' \
  'app/morphe/extension/shared/updater/SelfUpdateReceiver' \
  'app/morphe/extension/shared/updater/SelfUpdateInstallReceiver'; do
  grep -Fq "$class_name" "$DEX_STRINGS"
done

APK_SIZE=$(stat -c '%s' "$APK")
test "$APK_SIZE" -gt 0
test "$APK_SIZE" -le $((250 * 1024 * 1024))
sha256sum "$APK"
echo "$BADGING" | head -n3
echo "$CERTS" | grep -E 'certificate DN|certificate SHA-256 digest'
