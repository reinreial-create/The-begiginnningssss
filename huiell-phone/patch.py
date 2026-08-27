from pathlib import Path
import shutil

root = Path("build-src")
overlay = Path("huiell-phone")

java_dir = root / "app/src/main/java/com/qwen/tts/android"
java_dir.mkdir(parents=True, exist_ok=True)
shutil.copy2(overlay / "HuiellActivity.kt", java_dir / "HuiellActivity.kt")
shutil.copy2(overlay / "WavRecorder.kt", java_dir / "WavRecorder.kt")

settings = root / "settings.gradle.kts"
s = settings.read_text(encoding="utf-8")
if 'maven { url = uri("https://jitpack.io") }' not in s:
    s = s.replace("        mavenCentral()\n    }\n}\n\nrootProject.name", "        mavenCentral()\n        maven { url = uri(\"https://jitpack.io\") }\n    }\n}\n\nrootProject.name")
settings.write_text(s, encoding="utf-8")

build = root / "app/build.gradle.kts"
s = build.read_text(encoding="utf-8")
s = s.replace('applicationId = "com.qwen.tts.android"', 'applicationId = "ai.huiell.phone"')
s = s.replace('versionName = "0.1.0"', 'versionName = "0.4.0-huiell"')
needle = "dependencies {\n"
extra = (
    'dependencies {\n'
    '    implementation("com.github.1opp0-org:llama.android:v0.0.3")\n'
    '    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")\n'
)
if "llama.android:v0.0.3" not in s:
    s = s.replace(needle, extra, 1)
build.write_text(s, encoding="utf-8")

manifest = root / "app/src/main/AndroidManifest.xml"
s = manifest.read_text(encoding="utf-8")
s = s.replace('<application\n        android:allowBackup="true"', '<application\n        android:allowBackup="true"\n        android:extractNativeLibs="true"')
s = s.replace('android:name=".MainActivity"', 'android:name=".HuiellActivity"')
manifest.write_text(s, encoding="utf-8")

strings = root / "app/src/main/res/values/strings.xml"
if strings.exists():
    s = strings.read_text(encoding="utf-8")
    s = s.replace("Qwen3 TTS", "Huiell").replace("Qwen TTS", "Huiell")
    strings.write_text(s, encoding="utf-8")

print("Huiell overlay applied")
