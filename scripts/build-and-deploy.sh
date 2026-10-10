#!/usr/bin/env bash
# Bump version one step (1.13 -> 1.14), assembleDebug, copy APK to LAN SMB share.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
APK_SRC="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
SMB_HOST="192.168.2.10"
SMB_SHARE="download"
MOUNT_POINT="/Volumes/${SMB_SHARE}"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT"

current_name="$(sed -n 's/.*versionName = "\([^"]*\)"/\1/p' "$GRADLE_FILE" | head -1)"
current_code="$(sed -n 's/.*versionCode = \([0-9]*\)/\1/p' "$GRADLE_FILE" | head -1)"

if [[ -z "$current_name" || -z "$current_code" ]]; then
  echo "Could not parse version from $GRADLE_FILE" >&2
  exit 1
fi

# versionCode += 1; versionName = major.minor from code (114 -> "1.14")
new_code=$((current_code + 1))
new_name="$(python3 -c "c=int('${new_code}'); print(f'{c // 100}.{c % 100}')")"

echo "Bumping version: ${current_name} (${current_code}) -> ${new_name} (${new_code})"
sed -i '' "s/versionCode = ${current_code}/versionCode = ${new_code}/" "$GRADLE_FILE"
sed -i '' "s/versionName = \"${current_name}\"/versionName = \"${new_name}\"/" "$GRADLE_FILE"

got_name="$(sed -n 's/.*versionName = "\([^"]*\)"/\1/p' "$GRADLE_FILE" | head -1)"
got_code="$(sed -n 's/.*versionCode = \([0-9]*\)/\1/p' "$GRADLE_FILE" | head -1)"
if [[ "$got_name" != "$new_name" || "$got_code" != "$new_code" ]]; then
  echo "Failed to write version (got ${got_name}/${got_code})" >&2
  exit 1
fi

./gradlew assembleDebug

if [[ ! -f "$APK_SRC" ]]; then
  echo "APK not found at $APK_SRC" >&2
  exit 1
fi

copy_to_share() {
  local dest_dir="$1"
  local versioned="${dest_dir}/app-debug-${new_name}.apk"
  local latest="${dest_dir}/app-debug.apk"
  cp -f "$APK_SRC" "$versioned"
  cp -f "$APK_SRC" "$latest"
  echo "Copied:"
  echo "  $versioned"
  echo "  $latest"
}

if [[ -d "$MOUNT_POINT" ]]; then
  copy_to_share "$MOUNT_POINT"
  exit 0
fi

echo "Mounting smb://${SMB_HOST}/${SMB_SHARE} ..."
open "smb://${SMB_HOST}/${SMB_SHARE}" || true
for _ in $(seq 1 20); do
  if [[ -d "$MOUNT_POINT" ]]; then
    copy_to_share "$MOUNT_POINT"
    exit 0
  fi
  sleep 1
done

echo "WARNING: SMB share not mounted at ${MOUNT_POINT}." >&2
echo "APK left at: ${APK_SRC}" >&2
echo "Mount smb://${SMB_HOST}/${SMB_SHARE} and copy manually." >&2
exit 0
