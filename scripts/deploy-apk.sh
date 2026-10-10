#!/usr/bin/env bash
# Build the debug APK and overwrite it on the LAN SMB share.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
SMB_HOST="192.168.2.10"
SMB_SHARE="download"
SMB_FILE="app-debug.apk"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT"
echo "==> Building assembleDebug…"
./gradlew assembleDebug --no-daemon

if [[ ! -f "$APK" ]]; then
  echo "ERROR: APK not found at $APK" >&2
  exit 1
fi

if ! command -v smbclient >/dev/null 2>&1; then
  echo "ERROR: smbclient not found. Install with: brew install samba" >&2
  exit 1
fi

echo "==> Uploading to smb://$SMB_HOST/$SMB_SHARE/$SMB_FILE (guest)…"
# -N = no password (guest). Fail clearly if the share requires credentials.
if ! smbclient "//${SMB_HOST}/${SMB_SHARE}" -N -c "put \"${APK}\" ${SMB_FILE}"; then
  echo "ERROR: SMB upload failed (guest). If the share needs a password, run manually:" >&2
  echo "  smbclient //${SMB_HOST}/${SMB_SHARE} -U USER -c 'put \"${APK}\" ${SMB_FILE}'" >&2
  exit 1
fi

echo "==> Done: smb://${SMB_HOST}/${SMB_SHARE}/${SMB_FILE}"
ls -lh "$APK"
