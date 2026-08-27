package com.qwen.tts.android

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.qwen.tts.studio.engine.QwenEngine
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amazingapps.llama.android.core.AiChat
import net.amazingapps.llama.android.core.InferenceEngine
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class HuiellActivity : ComponentActivity() {
    private lateinit var llm: InferenceEngine
    private lateinit var recorder: WavRecorder
    private var tts: QwenEngine? = null
    private val prefs by lazy { getSharedPreferences("huiell", MODE_PRIVATE) }

    private var llmStatus by mutableStateOf("Qwen: vali GGUF või laadi alla")
    private var whisperStatus by mutableStateOf("Whisper: vali mudel või laadi Small")
    private var ttsStatus by mutableStateOf("Serena: mudel pole veel telefonis")
    private var reply by mutableStateOf("Huiell on valmis, kui Qwen on laetud.")
    private var busy by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var downloadProgress by mutableStateOf("")
    private var llmPath: String? = null
    private var whisperPath: String? = null

    private val llmPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri, "llm") { path -> loadLlm(path) }
    }
    private val whisperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri, "whisper") { path ->
            whisperPath = path
            prefs.edit().putString("whisper_path", path).apply()
            whisperStatus = "Whisper: ${File(path).name} • offline"
        }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) beginRecording() else reply = "Mikrofoni luba on kõnetuvastuseks vajalik."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        llm = AiChat.getInferenceEngine(this)
        recorder = WavRecorder(File(filesDir, "recordings"))
        llmPath = prefs.getString("llm_path", null)?.takeIf { File(it).isFile }
        whisperPath = prefs.getString("whisper_path", null)?.takeIf { File(it).isFile }
        whisperPath?.let { whisperStatus = "Whisper: ${File(it).name} • offline" }
        llmPath?.let { loadLlm(it) }
        val ttsDir = File(filesDir, "models/tts")
        if (serenaFilesReady(ttsDir)) ttsStatus = "Serena: mudel olemas • offline"

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HuiellScreen()
            }
        }
    }

    @Composable
    private fun HuiellScreen() {
        var input by remember { mutableStateOf("") }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Huiell", fontWeight = FontWeight.Bold)
                            Text("offline-first • S22", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(14.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(llmStatus, style = MaterialTheme.typography.bodySmall)
                Text("Mälu: ainult selles telefonis", style = MaterialTheme.typography.bodySmall)
                Text(whisperStatus, style = MaterialTheme.typography.bodySmall)
                Text(ttsStatus, style = MaterialTheme.typography.bodySmall)
                if (downloadProgress.isNotBlank()) Text(downloadProgress, style = MaterialTheme.typography.labelSmall)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { llmPicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(4.dp)); Text("Qwen")
                    }
                    OutlinedButton(onClick = { whisperPicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy) {
                        Text("Whisper")
                    }
                    OutlinedButton(onClick = { downloadCoreModels() }, enabled = !busy) {
                        Text("Laadi mudelid")
                    }
                }

                Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        text = reply,
                        modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Kirjuta Huiellile…") },
                    enabled = !busy,
                    minLines = 1,
                    maxLines = 4
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { toggleRecording() },
                        enabled = !busy || recording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Mic, null); Spacer(Modifier.width(5.dp)); Text(if (recording) "Stopp" else "Räägi")
                    }
                    Button(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                input = ""
                                sendMessage(text)
                            }
                        },
                        enabled = !busy && input.isNotBlank() && llmPath != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, null); Spacer(Modifier.width(5.dp)); Text("Saada")
                    }
                }
            }
        }
    }

    private fun importModel(uri: Uri, kind: String, done: (String) -> Unit) {
        lifecycleScope.launch {
            busy = true
            try {
                val name = queryName(uri) ?: if (kind == "llm") "qwen.gguf" else "whisper.bin"
                val dir = File(filesDir, "models/$kind").apply { mkdirs() }
                val dest = File(dir, name)
                downloadProgress = "Kopeerin $name telefoni…"
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output, 1024 * 1024) }
                    } ?: error("Faili ei saanud avada")
                }
                done(dest.absolutePath)
            } catch (e: Exception) {
                reply = "Mudeli import ebaõnnestus: ${e.message}"
            } finally {
                downloadProgress = ""
                busy = false
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun loadLlm(path: String) {
        lifecycleScope.launch {
            busy = true
            llmStatus = "Qwen: laen ${File(path).name}…"
            try {
                withContext(Dispatchers.IO) {
                    llm.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady }
                    llm.loadModel(path)
                    llm.setSystemPrompt(
                        "You are Huiell, a private local assistant on the owner's phone. " +
                            "Reply directly and use Estonian when the user speaks Estonian. " +
                            "Never expose chain-of-thought, hidden reasoning, analysis, or <think> tags. " +
                            "Give only the useful final answer."
                    )
                }
                llmPath = path
                prefs.edit().putString("llm_path", path).apply()
                llmStatus = "Huiell • offline • ${File(path).name}"
                reply = "Qwen on kohalikult laetud. <think> tekst on Huielli väljundist filtreeritud."
            } catch (e: Exception) {
                llmStatus = "Qwen viga"
                reply = "Qweni laadimine ebaõnnestus: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun sendMessage(text: String) {
        if (llmPath == null) return
        lifecycleScope.launch {
            busy = true
            reply = "…"
            try {
                val raw = StringBuilder()
                withContext(Dispatchers.Default) {
                    llm.sendUserPrompt("$text\n/no_think", 640).collect { raw.append(it) }
                }
                val clean = cleanThink(raw.toString()).ifBlank { "Ma ei saanud puhast vastust. Proovi uuesti." }
                reply = clean
                saveMemory(text, clean)
                if (serenaFilesReady(File(filesDir, "models/tts"))) speakSerena(clean)
            } catch (e: Exception) {
                reply = "Huielli vastuse viga: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun cleanThink(raw: String): String {
        var s = raw.replace(Regex("(?is)<think>.*?</think>"), "")
        s = s.replace(Regex("(?is)^\\s*<think>.*$"), "")
        s = s.replace("<think>", "", ignoreCase = true).replace("</think>", "", ignoreCase = true)
        return s.trim()
    }

    private fun saveMemory(user: String, assistant: String) {
        runCatching {
            val dir = File(filesDir, "memory").apply { mkdirs() }
            val row = JSONObject()
                .put("time", System.currentTimeMillis())
                .put("user", user)
                .put("assistant", assistant)
            File(dir, "chat.jsonl").appendText(row.toString() + "\n", Charsets.UTF_8)
        }
    }

    private fun toggleRecording() {
        if (recording) {
            lifecycleScope.launch {
                val wav = runCatching { recorder.stop() }.getOrElse {
                    recording = false
                    reply = "Salvestuse viga: ${it.message}"
                    return@launch
                }
                recording = false
                transcribe(wav)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                beginRecording()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun beginRecording() {
        runCatching { recorder.start() }
            .onSuccess { recording = true; reply = "Kuulan eesti keelt… vajuta Stopp, kui valmis." }
            .onFailure { reply = "Mikrofoni viga: ${it.message}" }
    }

    private fun transcribe(wav: File) {
        val modelPath = whisperPath
        if (modelPath == null) {
            reply = "Vali või laadi enne Whisper Small mudel."
            return
        }
        lifecycleScope.launch {
            busy = true
            whisperStatus = "Whisper: kuulan faili…"
            try {
                val text = withContext(Dispatchers.IO) {
                    val model = Whisper.loadModel(this@HuiellActivity, modelPath)
                    try {
                        Whisper.transcribe(model, wav.absolutePath, WhisperConfig(language = "et")).text.trim()
                    } finally {
                        Whisper.releaseModel(model)
                    }
                }
                whisperStatus = "Whisper: ${File(modelPath).name} • et • offline"
                if (text.isBlank()) reply = "Whisper ei saanud kõnest teksti." else {
                    reply = "Sina: $text\n\nHuiell mõtleb…"
                    sendMessage(text)
                }
            } catch (e: Exception) {
                whisperStatus = "Whisper viga"
                reply = "Kõnetuvastuse viga: ${e.message}"
            } finally {
                if (!busy) busy = false
            }
        }
    }

    private fun downloadCoreModels() {
        lifecycleScope.launch {
            busy = true
            try {
                val llmDir = File(filesDir, "models/llm").apply { mkdirs() }
                val llmFile = File(llmDir, "Qwen3-1.7B-Q4_K_M.gguf")
                if (!llmFile.isFile) downloadResumable(
                    "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true",
                    llmFile,
                    "Qwen 1.7B"
                )
                val wDir = File(filesDir, "models/whisper").apply { mkdirs() }
                val wFile = File(wDir, "ggml-small.bin")
                if (!wFile.isFile) downloadResumable(
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin?download=true",
                    wFile,
                    "Whisper Small"
                )
                whisperPath = wFile.absolutePath
                prefs.edit().putString("whisper_path", wFile.absolutePath).apply()
                whisperStatus = "Whisper: ggml-small.bin • et • offline"

                val tDir = File(filesDir, "models/tts").apply { mkdirs() }
                val talker = File(tDir, SERENA_TALKER)
                val tokenizer = File(tDir, SERENA_TOKENIZER)
                if (!talker.isFile) downloadResumable(
                    "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/$SERENA_TALKER?download=true",
                    talker,
                    "Serena talker"
                )
                if (!tokenizer.isFile) downloadResumable(
                    "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/$SERENA_TOKENIZER?download=true",
                    tokenizer,
                    "Serena tokenizer"
                )
                ttsStatus = "Serena: mudel olemas • offline"
                downloadProgress = "Mudelid valmis. Laen Qweni…"
                loadLlm(llmFile.absolutePath)
            } catch (e: Exception) {
                reply = "Mudelite laadimine katkes: ${e.message}. Vajuta uuesti — .part fail jätkab sealt."
            } finally {
                downloadProgress = ""
                busy = false
            }
        }
    }

    private suspend fun downloadResumable(url: String, dest: File, label: String) = withContext(Dispatchers.IO) {
        val part = File(dest.absolutePath + ".part")
        var existing = if (part.isFile) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        if (existing > 0L) conn.setRequestProperty("Range", "bytes=$existing-")
        conn.connect()
        val append = existing > 0L && conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!append) existing = 0L
        val remaining = conn.contentLengthLong.coerceAtLeast(0L)
        val total = if (remaining > 0L) existing + remaining else 0L
        conn.inputStream.use { input ->
            FileOutputStream(part, append).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var done = existing
                var n: Int
                var lastShown = -1
                while (input.read(buffer).also { n = it } > 0) {
                    output.write(buffer, 0, n)
                    done += n
                    val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                    if (pct != lastShown) {
                        lastShown = pct
                        withContext(Dispatchers.Main) {
                            downloadProgress = "$label: $pct% (${"%.1f".format(Locale.US, done / 1024.0 / 1024.0)} MB)"
                        }
                    }
                }
            }
        }
        conn.disconnect()
        if (dest.exists()) dest.delete()
        check(part.renameTo(dest)) { "Ei saanud $label faili lõpuni salvestada" }
    }

    private fun serenaFilesReady(dir: File): Boolean =
        File(dir, SERENA_TALKER).isFile && File(dir, SERENA_TOKENIZER).isFile

    private suspend fun speakSerena(text: String) {
        withContext(Dispatchers.IO) {
            val dir = File(filesDir, "models/tts")
            val engine = tts ?: QwenEngine().also {
                it.setBackendPreference(QwenEngine.BACKEND_CPU)
                it.setCpuThreads(4)
                check(it.loadModels(dir.absolutePath, SERENA_TALKER)) { it.getLastError() ?: "TTS model load failed" }
                tts = it
            }
            val speakers = engine.getAvailableSpeakers()
            val serena = speakers.firstOrNull { it.equals("serena", true) } ?: "serena"
            val result = engine.synthesize(
                text = text.take(900),
                params = QwenEngine.NativeParams(speaker = serena, maxAudioTokens = 512)
            )
            if (!result.success || result.audio == null) error(result.errorMsg ?: "Serena synthesis failed")
            playFloatPcm(result.audio, result.sampleRate)
            withContext(Dispatchers.Main) { ttsStatus = "Serena: räägib kohalikult • offline" }
        }
    }

    private fun playFloatPcm(audio: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(audio.size) { i ->
            (audio[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
    }

    override fun onDestroy() {
        recorder.cancel()
        runCatching { llm.destroy() }
        runCatching { tts?.close() }
        super.onDestroy()
    }

    companion object {
        private const val SERENA_TALKER = "qwen-talker-1.7b-customvoice-Q4_K_M.gguf"
        private const val SERENA_TOKENIZER = "qwen-tokenizer-12hz-Q4_K_M.gguf"
    }
}
