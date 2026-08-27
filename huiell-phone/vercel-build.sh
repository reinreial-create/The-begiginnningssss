#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
CACHE="$ROOT/.huiell-build"
SDK="$CACHE/android-sdk"
JDK="$CACHE/jdk17"
SRC="$CACHE/qwen3-tts-android"
mkdir -p "$CACHE" "$ROOT/public"

export PATH="$JDK/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export GRADLE_USER_HOME="$CACHE/gradle-home"

if [ ! -x "$JDK/bin/java" ]; then
  echo "==> Downloading Temurin JDK 17"
  rm -rf "$JDK" "$CACHE/jdk.tar.gz" "$CACHE/jdk-unpack"
  mkdir -p "$CACHE/jdk-unpack"
  curl -fL --retry 4 --retry-delay 3 \
    'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' \
    -o "$CACHE/jdk.tar.gz"
  tar -xzf "$CACHE/jdk.tar.gz" -C "$CACHE/jdk-unpack"
  JDIR="$(find "$CACHE/jdk-unpack" -mindepth 1 -maxdepth 1 -type d | head -1)"
  mv "$JDIR" "$JDK"
fi

export PATH="$JDK/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
java -version

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Downloading Android command-line tools"
  rm -rf "$SDK" "$CACHE/cmdline.zip" "$CACHE/cmdline-unpack"
  mkdir -p "$SDK/cmdline-tools" "$CACHE/cmdline-unpack"
  curl -fL --retry 4 --retry-delay 3 \
    'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip' \
    -o "$CACHE/cmdline.zip"
  unzip -q "$CACHE/cmdline.zip" -d "$CACHE/cmdline-unpack"
  mv "$CACHE/cmdline-unpack/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

export PATH="$JDK/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager --install \
  'platforms;android-36' \
  'build-tools;36.0.0' \
  'ndk;27.2.12479018' \
  'cmake;3.22.1'

rm -rf "$SRC"
echo "==> Cloning Qwen3 TTS Android base"
git clone --filter=blob:none https://github.com/Danmoreng/qwen3-tts-android.git "$SRC"
cd "$SRC"
git checkout 35738304b31c425cc08fb5405372fedfd6531b3b
git submodule sync --recursive
git submodule update --init --recursive --depth=1

cd "$ROOT"
rm -rf build-src
ln -s "$SRC" build-src
python3 huiell-phone/patch.py

cd "$SRC"
chmod +x ./gradlew
echo "==> Building Huiell APK"
./gradlew :app:assembleRelease --no-daemon --stacktrace

APK="$SRC/app/build/outputs/apk/release/app-release.apk"
test -f "$APK"
cp "$APK" "$ROOT/public/Huiell-S22-v0.4.apk"
SHA="$(sha256sum "$ROOT/public/Huiell-S22-v0.4.apk" | awk '{print $1}')"
SIZE="$(du -h "$ROOT/public/Huiell-S22-v0.4.apk" | awk '{print $1}')"
cat > "$ROOT/public/index.html" <<EOF
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Huiell S22 build</title></head><body style="font-family:sans-serif;background:#111;color:#eee;padding:32px"><h1>Huiell S22 v0.4</h1><p>APK size: $SIZE</p><p>SHA-256: <code>$SHA</code></p><p><a style="font-size:24px;color:#9cc2ff" href="/Huiell-S22-v0.4.apk">Download APK</a></p><p>Offline Qwen + Estonian Whisper + Serena TTS build.</p></body></html>
EOF

echo "==> APK ready: $SIZE $SHA"
