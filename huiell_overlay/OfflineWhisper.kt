package com.example.llama

import android.content.Context
import android.net.Uri
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OfflineWhisper(private val context: Context) {
    private val modelDir = File(context.filesDir, "whisper").apply { mkdirs() }
    private val smallFile = File(modelDir, "ggml-small.bin")
    private val tinyFile = File(modelDir, "ggml-tiny.bin")
    private val bundledTinyAsset = "models/ggml-tiny.bin"
    private var loadedModel: WhisperModel? = null
    private var loadedPath: String? = null

    private fun bundledTinyExists(): Boolean = runCatching {
        context.assets.open(bundledTinyAsset).use { true }
    }.getOrDefault(false)

    fun hasModel(): Boolean =
        validSmall() || validTiny() || bundledTinyExists()

    fun hasSmallModel(): Boolean = validSmall()

    fun modelStatus(): String = when {
        validSmall() ->
            "Whisper Small: ${smallFile.length() / 1024 / 1024} MB • eesti • offline"
        validTiny() ->
            "Whisper Tiny: ${tinyFile.length() / 1024 / 1024} MB • ajutine • Small laeb taustal"
        bundledTinyExists() ->
            "Whisper Tiny: bundled • ajutine • Small laeb taustal"
        else ->
            "Whisper Small: mudel puudub"
    }

    suspend fun prepareSmallModel(onStatus: suspend (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (validSmall()) {
            onStatus("Whisper Small: ${smallFile.length() / 1024 / 1024} MB • eesti • offline")
            return@withContext
        }

        modelDir.mkdirs()
        val part = File(modelDir, "ggml-small.bin.part")
        var existing = if (part.isFile) part.length() else 0L
        onStatus(if (existing > 0L) "Whisper Small: jätkan allalaadimist…" else "Whisper Small: laen eesti kõnetuvastust…")

        var connection = openConnection(SMALL_URL, existing)
        var code = connection.responseCode
        if (existing > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            part.delete()
            existing = 0L
            connection = openConnection(SMALL_URL, 0L)
            code = connection.responseCode
        }
        require(code in 200..299) { "Whisper Small download HTTP $code" }

        val responseLength = connection.contentLengthLong.coerceAtLeast(0L)
        val total = if (code == HttpURLConnection.HTTP_PARTIAL) existing + responseLength else responseLength
        connection.inputStream.use { input ->
            FileOutputStream(part, existing > 0L).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var downloaded = existing
                var lastPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0L) {
                        val pct = (downloaded * 100L / total).toInt().coerceIn(0, 100)
                        if (pct != lastPercent && (pct % 2 == 0 || pct == 100)) {
                            lastPercent = pct
                            onStatus("Whisper Small: $pct% • eesti mudel")
                        }
                    }
                }
                output.fd.sync()
            }
        }
        connection.disconnect()

        require(part.length() > MIN_SMALL_BYTES) { "Whisper Small fail jäi liiga väikeseks" }
        if (smallFile.exists()) smallFile.delete()
        require(part.renameTo(smallFile)) { "Whisper Small faili salvestamine ebaõnnestus" }
        onStatus("Whisper Small: ${smallFile.length() / 1024 / 1024} MB • eesti • offline")
    }

    private suspend fun ensureLocalModel(): File = withContext(Dispatchers.IO) {
        if (validSmall()) return@withContext smallFile
        if (validTiny()) return@withContext tinyFile
        require(bundledTinyExists()) { "Whisperi mudel puudub" }
        val tmp = File(modelDir, "ggml-tiny.bin.tmp")
        context.assets.open(bundledTinyAsset).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        require(tmp.length() > MIN_TINY_BYTES) { "Bundled Whisper model is invalid" }
        if (tinyFile.exists()) tinyFile.delete()
        require(tmp.renameTo(tinyFile)) { "Whisperi mudeli salvestamine ebaõnnestus" }
        tinyFile
    }

    suspend fun importModel(uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(modelDir, "whisper-import.bin.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Whisperi mudelit ei saanud avada")
        require(tmp.length() > MIN_TINY_BYTES) { "Valitud fail ei paista olevat Whisperi mudel" }

        val target = if (tmp.length() > 250_000_000L) smallFile else tinyFile
        release()
        if (target.exists()) target.delete()
        require(tmp.renameTo(target)) { "Whisperi mudeli salvestamine ebaõnnestus" }
    }

    suspend fun transcribe(audioFile: File): String {
        val localModel = ensureLocalModel()
        if (loadedPath != localModel.absolutePath) release()
        val model = loadedModel ?: Whisper.loadModel(context, localModel.absolutePath).also {
            loadedModel = it
            loadedPath = localModel.absolutePath
        }
        val result = Whisper.transcribe(
            model,
            audioFile.absolutePath,
            WhisperConfig(
                language = "et",
                translate = false,
                threads = 4,
                printTimestamps = false,
            ),
        )
        return result.text.trim()
    }

    fun release() {
        loadedModel?.let { Whisper.releaseModel(it) }
        loadedModel = null
        loadedPath = null
    }

    private fun validSmall() = smallFile.isFile && smallFile.length() > MIN_SMALL_BYTES
    private fun validTiny() = tinyFile.isFile && tinyFile.length() > MIN_TINY_BYTES

    private fun openConnection(url: String, startAt: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Huiell-Android/3")
            if (startAt > 0L) setRequestProperty("Range", "bytes=$startAt-")
            connect()
        }

    companion object {
        private const val SMALL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin?download=true"
        private const val MIN_SMALL_BYTES = 400_000_000L
        private const val MIN_TINY_BYTES = 10_000_000L
    }
}
