package com.example.llama

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.qwen.tts.studio.engine.QwenEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class SerenaTts(private val context: Context) {
    private val modelDir = File(context.filesDir, "serena-qwen3-tts").apply { mkdirs() }
    private val talkerFile = File(modelDir, TALKER_NAME)
    private val tokenizerFile = File(modelDir, TOKENIZER_NAME)

    @Volatile
    private var activeTrack: AudioTrack? = null

    fun hasModels(): Boolean =
        talkerFile.isFile && talkerFile.length() > MIN_TALKER_BYTES &&
            tokenizerFile.isFile && tokenizerFile.length() > MIN_TOKENIZER_BYTES

    fun modelStatus(): String = if (hasModels()) {
        "Serena: Qwen3-TTS 1.7B CustomVoice Q4_K_M • offline"
    } else {
        "Serena: mudelid laadimata • ~1.45 GB"
    }

    suspend fun ensureModels(onStatus: suspend (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        modelDir.mkdirs()
        if (!tokenizerFile.isFile || tokenizerFile.length() <= MIN_TOKENIZER_BYTES) {
            onStatus("Serena 1/2: tokenizeri allalaadimine…")
            downloadResumable(TOKENIZER_URL, tokenizerFile) { done, total ->
                val pct = if (total > 0) (done * 100L / total).coerceIn(0L, 100L) else -1L
                if (pct >= 0 && pct % 5L == 0L) {
                    // Deliberately quiet; MainActivity shows coarse status only.
                }
            }
        }
        require(tokenizerFile.length() > MIN_TOKENIZER_BYTES) { "Serena tokenizeri fail on vigane" }

        if (!talkerFile.isFile || talkerFile.length() <= MIN_TALKER_BYTES) {
            onStatus("Serena 2/2: 1.7B häälemudeli allalaadimine…")
            downloadResumable(TALKER_URL, talkerFile) { _, _ -> }
        }
        require(talkerFile.length() > MIN_TALKER_BYTES) { "Serena häälemudeli fail on vigane" }
        onStatus("Serena valmis • offline")
    }

    suspend fun speak(text: String, onStatus: suspend (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isEmpty()) return@withContext

        ensureModels(onStatus)
        onStatus("Serena laadib häälemudelit…")

        QwenEngine().use { engine ->
            require(engine.setBackendPreference(QwenEngine.BACKEND_CPU)) { "Serena CPU backend ei käivitunud" }
            engine.setCpuThreads(preferredThreads())
            require(engine.loadModels(modelDir.absolutePath, TALKER_NAME)) {
                engine.getLastError() ?: "Serena mudeli laadimine ebaõnnestus"
            }

            val speakers = engine.getAvailableSpeakers()
            if (speakers.isNotEmpty()) {
                require(speakers.any { it.equals("Serena", ignoreCase = true) }) {
                    "Serena speaker puudub mudelis"
                }
            }

            onStatus("Serena räägib…")
            val result = engine.synthesize(
                text = clean,
                params = QwenEngine.NativeParams(
                    languageId = LANGUAGE_ENGLISH,
                    speaker = "Serena",
                    maxAudioTokens = maxAudioTokens(clean),
                ),
            )

            require(result.success && result.audio != null) {
                result.errorMsg ?: "Serena kõnesüntees ebaõnnestus"
            }
            playBlocking(result.audio, result.sampleRate.takeIf { it > 0 } ?: 24_000)
        }
        onStatus("Serena valmis • offline")
    }

    fun stop() {
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
    }

    private suspend fun playBlocking(samples: FloatArray, sampleRate: Int) {
        stop()
        val bytes = max(samples.size * 4, AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ))
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        activeTrack = track
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()
        val durationMs = (samples.size.toDouble() / sampleRate.toDouble() * 1000.0).toLong()
        delay(durationMs.coerceAtLeast(200L) + 120L)
        runCatching { track.stop() }
        runCatching { track.release() }
        if (activeTrack === track) activeTrack = null
    }

    private fun preferredThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 6
            cores >= 6 -> 5
            cores >= 4 -> 4
            else -> cores.coerceAtLeast(1)
        }
    }

    private fun maxAudioTokens(text: String): Int =
        (text.length * 3).coerceIn(160, 1024)

    private fun downloadResumable(
        url: String,
        target: File,
        progress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.isFile) part.length() else 0L

        var connection = openConnection(url, existing)
        var code = connection.responseCode
        if (existing > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            part.delete()
            existing = 0L
            connection = openConnection(url, 0L)
            code = connection.responseCode
        }
        require(code in 200..299) { "Serena mudeli allalaadimine ebaõnnestus: HTTP $code" }

        val responseLength = connection.contentLengthLong.coerceAtLeast(0L)
        val total = if (code == HttpURLConnection.HTTP_PARTIAL) existing + responseLength else responseLength
        connection.inputStream.use { input ->
            FileOutputStream(part, existing > 0L).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var downloaded = existing
                var lastReport = -1L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val marker = downloaded / (32L * 1024L * 1024L)
                    if (marker != lastReport) {
                        lastReport = marker
                        progress(downloaded, total)
                    }
                }
                output.fd.sync()
            }
        }
        connection.disconnect()

        if (target.exists()) target.delete()
        require(part.renameTo(target)) { "Serena mudelifaili salvestamine ebaõnnestus" }
    }

    private fun openConnection(url: String, startAt: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Huiell-Android/2")
            if (startAt > 0L) setRequestProperty("Range", "bytes=$startAt-")
            connect()
        }

    companion object {
        private const val LANGUAGE_ENGLISH = 2050
        private const val TALKER_NAME = "qwen-talker-1.7b-customvoice-Q4_K_M.gguf"
        private const val TOKENIZER_NAME = "qwen-tokenizer-12hz-Q4_K_M.gguf"
        private const val BASE_URL = "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main"
        private const val TALKER_URL = "$BASE_URL/$TALKER_NAME?download=true"
        private const val TOKENIZER_URL = "$BASE_URL/$TOKENIZER_NAME?download=true"
        private const val MIN_TALKER_BYTES = 900_000_000L
        private const val MIN_TOKENIZER_BYTES = 200_000_000L
    }
}
