#!/usr/bin/env bash
set -euo pipefail

TASK_ROOT="$PWD"
TASK_CACHE="$TASK_ROOT/.huiell-build"
ANDROID_SDK="$TASK_CACHE/android-sdk"
TASK_JDK="$TASK_CACHE/jdk17"
ANDROID_SRC="$TASK_CACHE/qwen3-tts-android"
mkdir -p "$TASK_CACHE" "$TASK_ROOT/public"

export JAVA_HOME="$TASK_JDK"
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
export GRADLE_USER_HOME="$TASK_CACHE/gradle-home"
export PATH="$TASK_JDK/bin:$ANDROID_SDK/cmdline-tools/latest/bin:$ANDROID_SDK/platform-tools:$PATH"

if [ ! -x "$TASK_JDK/bin/java" ]; then
  echo "==> Installing Temurin JDK 17"
  rm -rf "$TASK_JDK" "$TASK_CACHE/jdk-unpack"
  mkdir -p "$TASK_CACHE/jdk-unpack"
  curl -fL --retry 4 --retry-delay 3 \
    'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' \
    -o "$TASK_CACHE/jdk.tar.gz"
  tar -xzf "$TASK_CACHE/jdk.tar.gz" -C "$TASK_CACHE/jdk-unpack"
  JDK_SOURCE="$(find "$TASK_CACHE/jdk-unpack" -mindepth 1 -maxdepth 1 -type d | head -1)"
  mv "$JDK_SOURCE" "$TASK_JDK"
fi

export PATH="$TASK_JDK/bin:$ANDROID_SDK/cmdline-tools/latest/bin:$ANDROID_SDK/platform-tools:$PATH"
java -version

if [ ! -x "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Installing Android command-line tools"
  rm -rf "$ANDROID_SDK" "$TASK_CACHE/cmdline-unpack"
  mkdir -p "$ANDROID_SDK/cmdline-tools" "$TASK_CACHE/cmdline-unpack"
  curl -fL --retry 4 --retry-delay 3 \
    'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip' \
    -o "$TASK_CACHE/cmdline.zip"
  unzip -q "$TASK_CACHE/cmdline.zip" -d "$TASK_CACHE/cmdline-unpack"
  mv "$TASK_CACHE/cmdline-unpack/cmdline-tools" "$ANDROID_SDK/cmdline-tools/latest"
fi

export PATH="$TASK_JDK/bin:$ANDROID_SDK/cmdline-tools/latest/bin:$ANDROID_SDK/platform-tools:$PATH"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager --install \
  'platforms;android-36' \
  'build-tools;36.0.0' \
  'ndk;28.2.13676358' \
  'cmake;3.22.1'

rm -rf "$ANDROID_SRC"
echo "==> Fetching pinned Qwen3-TTS Android source"
git clone --filter=blob:none https://github.com/Danmoreng/qwen3-tts-android.git "$ANDROID_SRC"
cd "$ANDROID_SRC"
git checkout 35738304b31c425cc08fb5405372fedfd6531b3b
git submodule sync --recursive
git submodule update --init --recursive --depth=1

cd "$TASK_ROOT"
rm -rf build-src
ln -s "$ANDROID_SRC" build-src
python3 huiell-phone/patch.py

cd "$ANDROID_SRC"
chmod +x ./gradlew
echo "==> Compiling Huiell v1 arm64 APK"
./gradlew :app:assembleRelease --no-daemon --stacktrace --max-workers=2

APK_SOURCE="$ANDROID_SRC/app/build/outputs/apk/release/app-release.apk"
APK_PUBLIC="$TASK_ROOT/public/Huiell-S22-v1.apk"
test -f "$APK_SOURCE"
cp "$APK_SOURCE" "$APK_PUBLIC"
APK_SHA="$(sha256sum "$APK_PUBLIC" | awk '{print $1}')"
APK_SIZE="$(du -h "$APK_PUBLIC" | awk '{print $1}')"

cat > "$TASK_ROOT/public/index.html" <<EOF
<!doctype html>
<html lang="et">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Huiell S22 v1</title></head>
<body style="font-family:system-ui,sans-serif;background:#101114;color:#f2f2f2;padding:28px;line-height:1.5">
  <h1>Huiell S22 v1</h1>
  <p>Kohalik Qwen core, Serena 0.6B, eesti kõnesisestus, nõusolekupõhine veebimälu ja Google Drive'i varundus.</p>
  <p>APK: $APK_SIZE</p>
  <p><a style="display:inline-block;padding:14px 20px;background:#a9c7ff;color:#07152d;border-radius:12px;text-decoration:none;font-weight:700" href="/Huiell-S22-v1.apk">Laadi APK telefoni</a></p>
  <p style="overflow-wrap:anywhere">SHA-256: <code>$APK_SHA</code></p>
  <p>Vercel on ainult selle faili kompileerija. Installitud Huiell ei kasuta Vercelit.</p>
</body>
</html>
EOF

echo "==> APK ready: $APK_SIZE $APK_SHA"
