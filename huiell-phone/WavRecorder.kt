package com.qwen.tts.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class WavRecorder(private val root: File) {
    private val sampleRate = 16000
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var rawFile: File? = null
    private var wavFile: File? = null

    @SuppressLint("MissingPermission")
    fun start(): File {
        if (recording.get()) return wavFile ?: error("Already recording")
        root.mkdirs()
        val stamp = System.currentTimeMillis()
        val raw = File(root, "huiell-$stamp.pcm")
        val wav = File(root, "huiell-$stamp.wav")
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            min * 2
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone init failed" }
        rawFile = raw
        wavFile = wav
        audioRecord = recorder
        recording.set(true)
        recorder.startRecording()
        worker = Thread {
            FileOutputStream(raw).use { out ->
                val buffer = ByteArray(min)
                while (recording.get()) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) out.write(buffer, 0, n)
                }
            }
        }.also { it.name = "huiell-recorder"; it.start() }
        return wav
    }

    fun stop(): File {
        val recorder = audioRecord ?: error("Recorder not running")
        recording.set(false)
        runCatching { recorder.stop() }
        worker?.join(2000)
        recorder.release()
        audioRecord = null
        worker = null
        val raw = rawFile ?: error("Raw recording missing")
        val wav = wavFile ?: error("WAV recording missing")
        writeWav(raw, wav)
        raw.delete()
        rawFile = null
        return wav
    }

    fun cancel() {
        recording.set(false)
        runCatching { audioRecord?.stop() }
        worker?.join(1000)
        runCatching { audioRecord?.release() }
        audioRecord = null
        worker = null
        rawFile?.delete()
        rawFile = null
    }

    private fun writeWav(raw: File, wav: File) {
        val dataSize = raw.length()
        FileOutputStream(wav).use { out ->
            fun le16(v: Int) { out.write(byteArrayOf((v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte())) }
            fun le32(v: Long) {
                out.write(byteArrayOf(
                    (v and 0xff).toByte(),
                    ((v ushr 8) and 0xff).toByte(),
                    ((v ushr 16) and 0xff).toByte(),
                    ((v ushr 24) and 0xff).toByte()
                ))
            }
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            le32(36 + dataSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            le32(16)
            le16(1)
            le16(1)
            le32(sampleRate.toLong())
            le32((sampleRate * 2).toLong())
            le16(2)
            le16(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            le32(dataSize)
            FileInputStream(raw).use { it.copyTo(out) }
        }
    }
}
