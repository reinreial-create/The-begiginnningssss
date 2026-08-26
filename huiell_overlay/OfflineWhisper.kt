package com.example.llama

import android.content.Context
import android.net.Uri
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class OfflineWhisper(private val context: Context) {
    private val modelDir = File(context.filesDir, "whisper").apply { mkdirs() }
    private val modelFile = File(modelDir, "ggml-base.bin")
    private val bundledAsset = "models/ggml-base.bin"
    private var loadedModel: WhisperModel? = null

    private fun bundledModelExists(): Boolean = runCatching {
        context.assets.open(bundledAsset).use { true }
    }.getOrDefault(false)

    fun hasModel(): Boolean =
        (modelFile.isFile && modelFile.length() > 10_000_000L) || bundledModelExists()

    fun modelStatus(): String = when {
        modelFile.isFile && modelFile.length() > 10_000_000L ->
            "Whisper: ${modelFile.name} • ${(modelFile.length() / 1024 / 1024)} MB • offline"
        bundledModelExists() ->
            "Whisper: ggml-base.bin • bundled • offline"
        else ->
            "Whisper: mudel puudub • mikrofoni vajutades vali ggml-base.bin"
    }

    private suspend fun ensureLocalModel(): File = withContext(Dispatchers.IO) {
        if (modelFile.isFile && modelFile.length() > 10_000_000L) return@withContext modelFile
        require(bundledModelExists()) { "Whisperi mudel puudub" }
        val tmp = File(modelDir, "ggml-base.bin.tmp")
        context.assets.open(bundledAsset).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        require(tmp.length() > 10_000_000L) { "Bundled Whisper model is invalid" }
        if (modelFile.exists()) modelFile.delete()
        require(tmp.renameTo(modelFile)) { "Whisperi mudeli salvestamine ebaõnnestus" }
        modelFile
    }

    suspend fun importModel(uri: Uri) = withContext(Dispatchers.IO) {
        release()
        val tmp = File(modelDir, "ggml-base.bin.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Whisperi mudelit ei saanud avada")
        require(tmp.length() > 10_000_000L) { "Valitud fail ei paista olevat Whisperi mudel" }
        if (modelFile.exists()) modelFile.delete()
        require(tmp.renameTo(modelFile)) { "Whisperi mudeli salvestamine ebaõnnestus" }
    }

    suspend fun transcribe(audioFile: File): String {
        val localModel = ensureLocalModel()
        val model = loadedModel ?: Whisper.loadModel(context, localModel.absolutePath).also {
            loadedModel = it
        }
        val result = Whisper.transcribe(
            model,
            audioFile.absolutePath,
            WhisperConfig(
                language = "auto",
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
    }
}
