#!/usr/bin/env bash

set -eo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${MORPHE_KEYSTORE_B64:?MORPHE_KEYSTORE_B64 is required}"

ASSET_NAME="${ASSET_NAME:-youtube-arm64-v8a-morphe-selfupdate.apk}"
BUILDER_COMMIT="404ae1a154ed042f24ba349ae2ef897579b3b8eb"
BUILDER_DIR="$RUNNER_TEMP/fiorenmas-builder"

rm -rf "$BUILDER_DIR"
git clone --filter=blob:none https://github.com/FiorenMas/Revanced-And-Revanced-Extended-Non-Root.git "$BUILDER_DIR"
git -C "$BUILDER_DIR" checkout "$BUILDER_COMMIT"

printf '%s' "$MORPHE_KEYSTORE_B64" | base64 --decode > "$BUILDER_DIR/src/morphe.keystore"
chmod 600 "$BUILDER_DIR/src/morphe.keystore"

PATCH_BUNDLE=$(find "$GITHUB_WORKSPACE/patches/build/libs" -maxdepth 1 -name 'patches-*.mpp' -print -quit)
test -n "$PATCH_BUNDLE"
cp "$PATCH_BUNDLE" "$BUILDER_DIR/selfupdate-patches.mpp"

cd "$BUILDER_DIR"
source ./src/build/utils.sh
dl_gh "morphe-desktop" "MorpheApp" "latest"
get_patches_key "youtube-morphe"
get_apk "com.google.android.youtube" "youtube" "apk"

i=0
split_arch "youtube" "morphe"

OUTPUT_APK="$BUILDER_DIR/release/youtube-arm64-v8a-morphe.apk"
test -s "$OUTPUT_APK"
mv "$OUTPUT_APK" "$GITHUB_WORKSPACE/$ASSET_NAME"

PATCH_VERSION=$(sed -n 's/^version *= *//p' "$GITHUB_WORKSPACE/gradle.properties" | tail -n1)
{
  echo "youtube_version=${version:-unknown}"
  echo "patch_version=${PATCH_VERSION:-unknown}"
  echo "builder_commit=$BUILDER_COMMIT"
} >> "$GITHUB_OUTPUT"
