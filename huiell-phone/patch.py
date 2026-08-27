from pathlib import Path
import shutil

root = Path("build-src")
overlay = Path("huiell-phone")

java_dir = root / "app/src/main/java/com/qwen/tts/android"
java_dir.mkdir(parents=True, exist_ok=True)
for source in ("HuiellActivity.kt", "HuiellMemory.kt", "CloudBackup.kt"):
    shutil.copy2(overlay / source, java_dir / source)

settings = root / "settings.gradle.kts"
text = settings.read_text(encoding="utf-8")
if 'maven { url = uri("https://jitpack.io") }' not in text:
    marker = "        mavenCentral()\n    }\n}\n\nrootProject.name"
    replacement = (
        "        mavenCentral()\n"
        '        maven { url = uri("https://jitpack.io") }\n'
        "    }\n}\n\nrootProject.name"
    )
    if marker not in text:
        raise RuntimeError("settings.gradle.kts repository marker not found")
    text = text.replace(marker, replacement, 1)
settings.write_text(text, encoding="utf-8")

build = root / "app/build.gradle.kts"
text = build.read_text(encoding="utf-8")
text = text.replace('applicationId = "com.qwen.tts.android"', 'applicationId = "ai.huiell.phone"', 1)
text = text.replace("versionCode = 1", "versionCode = 10", 1)
text = text.replace('versionName = "0.1.0"', 'versionName = "1.0.0"', 1)

dependency_marker = "dependencies {\n"
dependencies = (
    "dependencies {\n"
    '    implementation("com.github.1opp0-org:llama.android:v0.0.3")\n'
    '    implementation("androidx.documentfile:documentfile:1.1.0")\n'
)
if "llama.android:v0.0.3" not in text:
    if dependency_marker not in text:
        raise RuntimeError("Gradle dependencies block not found")
    text = text.replace(dependency_marker, dependencies, 1)

# Both native engines currently ship common ggml libraries. Android already
# prioritizes the app module's copies; make that deterministic for this APK.
packaging = """

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "lib/arm64-v8a/libggml.so",
                "lib/arm64-v8a/libggml-base.so",
                "lib/arm64-v8a/libggml-cpu.so"
            )
        }
    }
"""
if "useLegacyPackaging = true" not in text:
    marker = "\n    buildFeatures {\n"
    if marker not in text:
        raise RuntimeError("Gradle android block marker not found")
    text = text.replace(marker, packaging + marker, 1)
build.write_text(text, encoding="utf-8")

manifest = root / "app/src/main/AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
if "android.permission.ACCESS_NETWORK_STATE" not in text:
    text = text.replace(
        '    <uses-permission android:name="android.permission.RECORD_AUDIO" />',
        '    <uses-permission android:name="android.permission.RECORD_AUDIO" />\n'
        '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
        1,
    )
text = text.replace(
    '        android:allowBackup="true"',
    '        android:allowBackup="true"\n'
    '        android:extractNativeLibs="true"\n'
    '        android:usesCleartextTraffic="true"',
    1,
)
text = text.replace('android:name=".MainActivity"', 'android:name=".HuiellActivity"', 1)
manifest.write_text(text, encoding="utf-8")

strings = root / "app/src/main/res/values/strings.xml"
if strings.exists():
    text = strings.read_text(encoding="utf-8")
    text = text.replace("Qwen3 TTS", "Huiell").replace("Qwen TTS", "Huiell")
    strings.write_text(text, encoding="utf-8")

print("Huiell v1 phone overlay applied: local Qwen, Serena, web, memory and Drive backup")
