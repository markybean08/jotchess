# JotChess

Pure native Kotlin chess auto-recorder for Android. Overhead CameraX + OpenCV board tracking, chesslib move validation, PGN export. No audio. No ML models.

## CLI setup (once)

```bash
brew install openjdk@17 android-commandlinetools

export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
```

`local.properties` already points at `sdk.dir=/Users/markybean/Library/Android/sdk`.

## Build

Every build **bumps the version one step** (`1.13`→`1.14`, `versionCode` +1) and copies the APK to the LAN share when reachable.

```bash
# Preferred: bump + assemble + copy to smb://192.168.2.10/download/
./scripts/build-and-deploy.sh

# Or manually after bumping app/build.gradle.kts:
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Network deploy (when `smb://192.168.2.10/download/` is mounted, usually at `/Volumes/download`):

- `app-debug-<versionName>.apk` — versioned copy (preferred)
- `app-debug.apk` — latest pointer (overwritten)

Agent/CI instruction: see [`.cursor/rules/build-and-deploy.mdc`](.cursor/rules/build-and-deploy.mdc).

## Install & run

```bash
adb devices
./gradlew installDebug
adb shell am start -n com.jotchess.recorder/.MainActivity
```

## Test

```bash
./gradlew testDebugUnitTest
```

## Usage

1. Mount the phone over the board (boom / floor stand).
2. Tap the 4 board corners, press **Rotate** until a1 is correct, then **Confirm**.
3. Play. Legal moves commit after the board settles (~0.5s still). Knocked pieces that are righted within 3s are ignored. Illegal layouts highlight in red without writing PGN.
4. **Copy PGN** / **Reset Game** / **Undo** / **Recalibrate** as needed.
