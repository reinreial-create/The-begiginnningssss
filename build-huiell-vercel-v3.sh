#!/usr/bin/env bash
set -euo pipefail
ROOT="$PWD"; OUT="$ROOT/public"; SDK="$ROOT/.android-sdk"; WORK="$ROOT/.huiell-build"
mkdir -p "$OUT" "$SDK" "$WORK"
say(){ printf '\n===== %s =====\n' "$*"; }

say "Installing JDK 17"
curl -L --fail --retry 3 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk' -o "$WORK/jdk.tar.gz"
rm -rf "$WORK/jdk"; mkdir -p "$WORK/jdk"; tar -xzf "$WORK/jdk.tar.gz" -C "$WORK/jdk" --strip-components=1
export JAVA_HOME="$WORK/jdk"; export PATH="$JAVA_HOME/bin:$PATH"; java -version

say "Installing Android SDK"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"; export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
curl -L --fail --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o "$WORK/cmdline-tools.zip"
rm -rf "$SDK/cmdline-tools" "$WORK/cmdline"; mkdir -p "$SDK/cmdline-tools/latest" "$WORK/cmdline"
unzip -q "$WORK/cmdline-tools.zip" -d "$WORK/cmdline"; cp -a "$WORK/cmdline/cmdline-tools/." "$SDK/cmdline-tools/latest/"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;29.0.13113456' 'cmake;3.31.6'

say "Fetching llama.cpp"
rm -rf "$WORK/llama.cpp"; git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$WORK/llama.cpp"
APPROOT="$WORK/llama.cpp/examples/llama.android"

say "Fetching Qwen3-TTS native engine"
rm -rf "$APPROOT/external"; mkdir -p "$APPROOT/external"
git clone --depth 1 --recurse-submodules https://github.com/Danmoreng/qwen3-tts.cpp.git "$APPROOT/external/qwen3-tts.cpp"
git -C "$APPROOT/external/qwen3-tts.cpp" submodule update --init --recursive --depth 1
mkdir -p "$APPROOT/app/src/main/cpp" "$APPROOT/app/src/main/java/com/qwen/tts/studio/engine"
curl -L --fail --retry 3 https://raw.githubusercontent.com/Danmoreng/qwen3-tts-android/main/app/src/main/cpp/CMakeLists.txt -o "$APPROOT/app/src/main/cpp/CMakeLists.txt"

say "Applying Huiell code"
cp "$ROOT/huiell_overlay/MainActivity.kt" "$APPROOT/app/src/main/java/com/example/llama/MainActivity.kt"
cp "$ROOT/huiell_overlay/OfflineWhisper.kt" "$APPROOT/app/src/main/java/com/example/llama/OfflineWhisper.kt"
cp "$ROOT/huiell_overlay/OfflineWavRecorder.kt" "$APPROOT/app/src/main/java/com/example/llama/OfflineWavRecorder.kt"
cp "$ROOT/huiell_overlay/SerenaTts.kt" "$APPROOT/app/src/main/java/com/example/llama/SerenaTts.kt"
cp "$ROOT/huiell_overlay/QwenEngine.kt" "$APPROOT/app/src/main/java/com/qwen/tts/studio/engine/QwenEngine.kt"
cp "$ROOT/huiell_overlay/activity_main.xml" "$APPROOT/app/src/main/res/layout/activity_main.xml"
cat > "$APPROOT/app/src/main/res/drawable/outline_mic_24.xml" <<'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="@android:color/white" android:pathData="M12,14a3,3 0,0 0,3 -3V5a3,3 0,0 0,-6 0v6a3,3 0,0 0,3 3zM17.3,11a5.3,5.3 0,0 1,-10.6 0H5a7,7 0,0 0,6 6.92V21H8v2h8v-2h-3v-3.08A7,7 0,0 0,19 11z"/></vector>
EOF
cat > "$APPROOT/app/src/main/res/drawable/outline_stop_24.xml" <<'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="@android:color/white" android:pathData="M6,6h12v12H6z"/></vector>
EOF

cat > "$APPROOT/app/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:extractNativeLibs="true"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AiChatSample">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

python3 - <<'PY'
from pathlib import Path
import re
root=Path('.huiell-build/llama.cpp/examples/llama.android')
p=root/'app/build.gradle.kts'
s=p.read_text()
if 'dev.ffmpegkit-maintained:whisper-android' not in s:
    s=s.replace('dependencies {\n','dependencies {\n    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")\n',1)
s=s.replace('debug {\n            isMinifyEnabled = true\n            isShrinkResources = true','debug {\n            isMinifyEnabled = false\n            isShrinkResources = false')
s=s.replace('release {\n            isMinifyEnabled = true\n            isShrinkResources = true','release {\n            isMinifyEnabled = false\n            isShrinkResources = false')
if 'qwen3_tts_jni' not in s:
    s=s.replace('''        vectorDrawables {
            useSupportLibrary = true
        }''','''        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                targets += listOf("qwen3_tts_jni")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DQWEN3_ANDROID_OPENMP=OFF",
                    "-DQWEN3_ANDROID_VULKAN=OFF",
                    "-DQWEN3_ANDROID_OPENCL=OFF",
                )
            }
        }''')
    s=s.replace('''    compileOptions {''','''    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {''',1)
p.write_text(s)

p=root/'app/src/main/res/values/strings.xml'
s=p.read_text(); s=re.sub(r'<string name="app_name">.*?</string>','<string name="app_name">Huiell</string>',s); p.write_text(s)

pro=root/'app/proguard-rules.pro'
pro.write_text(pro.read_text() + '\n-keep class com.arm.aichat.** { *; }\n-keep class com.qwen.tts.** { *; }\n-keepclasseswithmembernames class * { native <methods>; }\n-keep class kotlin.Metadata { *; }\n')
PY

say "Bundling multilingual Whisper Tiny"
mkdir -p "$APPROOT/app/src/main/assets/models"
curl -L --fail --retry 3 --retry-delay 4 https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin -o "$APPROOT/app/src/main/assets/models/ggml-tiny.bin"
ls -lh "$APPROOT/app/src/main/assets/models/ggml-tiny.bin"

say "Building APK"
cd "$APPROOT"; chmod +x gradlew; ./gradlew --no-daemon :app:assembleDebug --stacktrace
APK="$APPROOT/app/build/outputs/apk/debug/app-debug.apk"; test -s "$APK"; cp "$APK" "$OUT/Huiell-Offline-Voice-S22-Serena.apk"
SIZE=$(du -h "$OUT/Huiell-Offline-Voice-S22-Serena.apk"|awk '{print $1}'); SHA=$(sha256sum "$OUT/Huiell-Offline-Voice-S22-Serena.apk"|awk '{print $1}')
cat > "$OUT/index.html" <<EOF
<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Huiell Serena APK</title><body style="font-family:sans-serif;max-width:760px;margin:40px auto;padding:20px"><h1>Huiell Offline Voice + Serena</h1><p>Galaxy S22 arm64 build. Qwen chat runs locally. Whisper Tiny is bundled. Serena Qwen3-TTS female voice downloads its GGUF models on first speech, then runs locally/offline.</p><p><a href="/Huiell-Offline-Voice-S22-Serena.apk" style="font-size:1.3rem">Download Huiell Serena APK</a></p><p>Size: $SIZE</p><p>SHA-256: <code>$SHA</code></p></body>
EOF
ls -lh "$OUT/Huiell-Offline-Voice-S22-Serena.apk"; echo "SHA256=$SHA"
