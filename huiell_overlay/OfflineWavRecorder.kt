package com.example.llama

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class OfflineWavRecorder(private val context: Context) {
    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var currentFile: File? = null

    val isRecording: Boolean get() = recording.get()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(scope: CoroutineScope): File {
        check(hasPermission()) { "Microphone permission missing" }
        check(!recording.get()) { "Already recording" }

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        require(minBuffer > 0) { "AudioRecord buffer unavailable: $minBuffer" }
        val bufferSize = maxOf(minBuffer * 2, 4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }

        val out = File(context.cacheDir, "huiell-voice-${System.currentTimeMillis()}.wav")
        currentFile = out
        audioRecord = recorder
        recording.set(true)

        recordJob = scope.launch(Dispatchers.IO) {
            RandomAccessFile(out, "rw").use { wav ->
                writeWavHeader(wav, 0)
                val buffer = ByteArray(bufferSize)
                var pcmBytes = 0L
                recorder.startRecording()
                try {
                    while (recording.get()) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            wav.write(buffer, 0, read)
                            pcmBytes += read
                        }
                    }
                } finally {
                    try {
                        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    } catch (_: Exception) {}
                    writeWavHeader(wav, pcmBytes)
                }
            }
        }
        return out
    }

    suspend fun stop(): File? {
        if (!recording.getAndSet(false)) return currentFile
        try { audioRecord?.stop() } catch (_: Exception) {}
        recordJob?.join()
        audioRecord?.release()
        audioRecord = null
        recordJob = null
        return currentFile
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        val file = stop()
        file?.delete()
        currentFile = null
    }

    fun release() {
        recording.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun writeWavHeader(file: RandomAccessFile, pcmBytes: Long) {
        val byteRate = sampleRate * 2
        val riffSize = pcmBytes + 36
        file.seek(0)
        file.writeBytes("RIFF")
        writeLeInt(file, riffSize.toInt())
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        writeLeInt(file, 16)
        writeLeShort(file, 1)
        writeLeShort(file, 1)
        writeLeInt(file, sampleRate)
        writeLeInt(file, byteRate)
        writeLeShort(file, 2)
        writeLeShort(file, 16)
        file.writeBytes("data")
        writeLeInt(file, pcmBytes.toInt())
    }

    private fun writeLeInt(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff)
        file.write((value ushr 8) and 0xff)
        file.write((value ushr 16) and 0xff)
        file.write((value ushr 24) and 0xff)
    }

    private fun writeLeShort(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff)
        file.write((value ushr 8) and 0xff)
    }
}
