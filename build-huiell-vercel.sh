#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
OUT="$ROOT/public"
SDK="$ROOT/.android-sdk"
WORK="$ROOT/.huiell-build"
mkdir -p "$OUT" "$SDK" "$WORK"

say() { printf '\n===== %s =====\n' "$*"; }

say "Build environment"
uname -a
command -v curl
command -v unzip
command -v git

# Android Gradle Plugin needs a JDK. Vercel images often already contain one;
# if not, install a local Temurin 17 without root privileges.
if ! command -v java >/dev/null 2>&1; then
  say "Installing local JDK 17"
  curl -L --fail --retry 3 \
    'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk' \
    -o "$WORK/jdk.tar.gz"
  mkdir -p "$WORK/jdk"
  tar -xzf "$WORK/jdk.tar.gz" -C "$WORK/jdk" --strip-components=1
  export JAVA_HOME="$WORK/jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java -version

say "Installing Android command line tools"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -L --fail --retry 3 \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    -o "$WORK/cmdline-tools.zip"
  rm -rf "$SDK/cmdline-tools"
  mkdir -p "$SDK/cmdline-tools/latest"
  unzip -q "$WORK/cmdline-tools.zip" -d "$WORK/cmdline"
  cp -a "$WORK/cmdline/cmdline-tools/." "$SDK/cmdline-tools/latest/"
fi

say "Installing Android SDK / NDK components"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager \
  'platforms;android-36' \
  'build-tools;36.0.0' \
  'ndk;29.0.13113456' \
  'cmake;3.22.1'

say "Fetching llama.cpp Android app"
rm -rf "$WORK/llama.cpp"
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$WORK/llama.cpp"
APPROOT="$WORK/llama.cpp/examples/llama.android"

say "Applying Huiell offline voice UI and code"
cp "$ROOT/huiell_overlay/MainActivity.kt" "$APPROOT/app/src/main/java/com/example/llama/MainActivity.kt"
cp "$ROOT/huiell_overlay/OfflineWhisper.kt" "$APPROOT/app/src/main/java/com/example/llama/OfflineWhisper.kt"
cp "$ROOT/huiell_overlay/OfflineWavRecorder.kt" "$APPROOT/app/src/main/java/com/example/llama/OfflineWavRecorder.kt"
cp "$ROOT/huiell_overlay/activity_main.xml" "$APPROOT/app/src/main/res/layout/activity_main.xml"

cat > "$APPROOT/app/src/main/res/drawable/outline_mic_24.xml" <<'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@android:color/white"
        android:pathData="M12,14a3,3 0,0 0,3 -3V5a3,3 0,0 0,-6 0v6a3,3 0,0 0,3 3zM17.3,11a5.3,5.3 0,0 1,-10.6 0H5a7,7 0,0 0,6 6.92V21H8v2h8v-2h-3v-3.08A7,7 0,0 0,19 11z"/>
</vector>
EOF

cat > "$APPROOT/app/src/main/res/drawable/outline_stop_24.xml" <<'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@android:color/white" android:pathData="M6,6h12v12H6z"/>
</vector>
EOF

python3 - <<'PY'
from pathlib import Path
import re
root = Path('.huiell-build/llama.cpp/examples/llama.android')

gradle = root / 'app/build.gradle.kts'
text = gradle.read_text()
if 'dev.ffmpegkit-maintained:whisper-android' not in text:
    text = text.replace('dependencies {\n', 'dependencies {\n    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")\n', 1)
# Faster debug build and avoid shrinking JNI-facing classes.
text = text.replace('debug {\n            isMinifyEnabled = true\n            isShrinkResources = true',
                    'debug {\n            isMinifyEnabled = false\n            isShrinkResources = false')
gradle.write_text(text)

manifest = root / 'app/src/main/AndroidManifest.xml'
text = manifest.read_text()
if 'android.permission.RECORD_AUDIO' not in text:
    pos = text.find('>') + 1
    text = text[:pos] + '\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />' + text[pos:]
manifest.write_text(text)

strings = root / 'app/src/main/res/values/strings.xml'
text = strings.read_text()
text = re.sub(r'<string name="app_name">.*?</string>', '<string name="app_name">Huiell</string>', text)
strings.write_text(text)
PY

say "Bundling multilingual Whisper Tiny model"
mkdir -p "$APPROOT/app/src/main/assets/models"
curl -L --fail --retry 3 --retry-delay 4 \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin \
  -o "$APPROOT/app/src/main/assets/models/ggml-tiny.bin"
ls -lh "$APPROOT/app/src/main/assets/models/ggml-tiny.bin"

say "Building installable debug APK"
cd "$APPROOT"
chmod +x gradlew
./gradlew --no-daemon :app:assembleDebug --stacktrace

APK="$APPROOT/app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
cp "$APK" "$OUT/Huiell-Offline-Voice-S22.apk"

SIZE=$(du -h "$OUT/Huiell-Offline-Voice-S22.apk" | awk '{print $1}')
SHA=$(sha256sum "$OUT/Huiell-Offline-Voice-S22.apk" | awk '{print $1}')
cat > "$OUT/index.html" <<EOF
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Huiell APK</title></head><body style="font-family:sans-serif;max-width:760px;margin:40px auto;padding:20px"><h1>Huiell Offline Voice</h1><p>Galaxy S22 arm64 build. Qwen GGUF stays on-device. Whisper Tiny multilingual is bundled for offline Estonian/English speech recognition.</p><p><a style="font-size:1.25rem" href="/Huiell-Offline-Voice-S22.apk">Download Huiell-Offline-Voice-S22.apk</a></p><p>APK size: $SIZE</p><p>SHA-256: <code>$SHA</code></p></body></html>
EOF

say "APK ready"
ls -lh "$OUT/Huiell-Offline-Voice-S22.apk"
echo "SHA256=$SHA"
