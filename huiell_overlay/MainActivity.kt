package com.example.llama

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var ggufTv: TextView
    private lateinit var whisperStatusTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton
    private lateinit var micFab: FloatingActionButton

    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    private lateinit var voiceRecorder: OfflineWavRecorder
    private lateinit var whisper: OfflineWhisper
    private lateinit var serenaTts: SerenaTts
    private var pendingVoiceStart = false
    private var isVoiceBusy = false

    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback {
            if (voiceRecorder.isRecording) stopVoiceAndSend() else Log.w(TAG, "Back ignored")
        }

        ggufTv = findViewById(R.id.gguf)
        whisperStatusTv = findViewById(R.id.whisper_status)
        messagesRv = findViewById(R.id.messages)
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        micFab = findViewById(R.id.mic_fab)

        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter

        voiceRecorder = OfflineWavRecorder(this)
        whisper = OfflineWhisper(this)
        serenaTts = SerenaTts(this)
        refreshVoiceStatus()

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
        }

        userActionFab.setOnClickListener {
            if (isModelReady) handleUserInput() else getLlmModel.launch(arrayOf("*/*"))
        }

        micFab.setOnClickListener {
            when {
                isVoiceBusy -> Unit
                voiceRecorder.isRecording -> stopVoiceAndSend()
                !whisper.hasModel() -> getWhisperModel.launch(arrayOf("*/*"))
                else -> ensureMicPermissionAndStart()
            }
        }
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingVoiceStart) startVoiceRecording()
        else if (!granted) toast("Mikrofoni luba on offline-kõnetuvastuseks vajalik")
        pendingVoiceStart = false
    }

    private val getWhisperModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            var startAfterImport = false
            setVoiceBusy(true, "Whisperi mudeli kopeerimine…")
            try {
                whisper.importModel(uri)
                refreshVoiceStatus()
                toast("Whisper valmis. Kõnetuvastus töötab nüüd offline.")
                startAfterImport = true
            } catch (e: Exception) {
                Log.e(TAG, "Whisper model import failed", e)
                toast("Whisperi mudeli import ebaõnnestus: ${e.message}")
            } finally {
                setVoiceBusy(false)
                if (startAfterImport) ensureMicPermissionAndStart()
            }
        }
    }

    private val getLlmModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected LLM uri: $uri")
        uri?.let { handleSelectedModel(it) }
    }

    private fun ensureMicPermissionAndStart() {
        if (voiceRecorder.hasPermission()) {
            startVoiceRecording()
        } else {
            pendingVoiceStart = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecording() {
        if (isVoiceBusy || voiceRecorder.isRecording) return
        try {
            serenaTts.stop()
            voiceRecorder.start(lifecycleScope)
            micFab.setImageResource(R.drawable.outline_stop_24)
            userInputEt.hint = "Kuulan… vajuta mikrofoni uuesti, kui lõpetad"
            toast("Kuulan offline…")
        } catch (e: Exception) {
            Log.e(TAG, "Voice start failed", e)
            toast("Mikrofon ei käivitunud: ${e.message}")
        }
    }

    private fun stopVoiceAndSend() {
        if (!voiceRecorder.isRecording || isVoiceBusy) return
        lifecycleScope.launch {
            setVoiceBusy(true, "Whisper transkribeerib telefonis…")
            micFab.setImageResource(R.drawable.outline_mic_24)
            val audio = try {
                voiceRecorder.stop()
            } catch (e: Exception) {
                null
            }
            if (audio == null || audio.length() <= 44) {
                setVoiceBusy(false)
                toast("Heli ei salvestunud")
                return@launch
            }

            try {
                val text = whisper.transcribe(audio)
                audio.delete()
                if (text.isBlank()) {
                    toast("Whisper ei tuvastanud kõnet")
                } else {
                    userInputEt.setText(text)
                    if (isModelReady) {
                        setVoiceBusy(false)
                        handleUserInput()
                        return@launch
                    } else {
                        toast("Kõne tuvastatud. Vali nüüd Qweni GGUF mudel.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Whisper transcription failed", e)
                toast("Offline kõnetuvastus ebaõnnestus: ${e.message}")
            } finally {
                setVoiceBusy(false)
            }
        }
    }

    private fun setVoiceBusy(busy: Boolean, hint: String? = null) {
        isVoiceBusy = busy
        micFab.isEnabled = !busy
        userActionFab.isEnabled = !busy
        if (hint != null) userInputEt.hint = hint
        if (!busy && !voiceRecorder.isRecording) {
            userInputEt.hint = if (isModelReady) "Kirjuta või räägi eesti/inglise keeles" else "Vali esmalt Qweni GGUF mudel"
        }
    }

    private fun refreshVoiceStatus(extra: String? = null) {
        whisperStatusTv.text = listOfNotNull(
            whisper.modelStatus(),
            extra ?: serenaTts.modelStatus(),
        ).joinToString("\n")
    }

    private fun handleSelectedModel(uri: Uri) {
        userActionFab.isEnabled = false
        userInputEt.hint = "GGUF analüüsimine…"
        ggufTv.text = "GGUF analüüsimine…\n$uri"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val metadata = contentResolver.openInputStream(uri)?.use {
                    GgufMetadataReader.create().readStructuredMetadata(it)
                } ?: error("GGUF faili ei saanud avada")

                withContext(Dispatchers.Main) {
                    ggufTv.text = "Huiell • offline • ${metadata.filename()}$FILE_EXTENSION_GGUF\nMälu: ainult selles telefonis"
                }

                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                val modelFile = contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                } ?: error("GGUF faili ei saanud kopeerida")

                loadModel(modelName, modelFile)
                withContext(Dispatchers.Main) {
                    isModelReady = true
                    userInputEt.hint = "Kirjuta või räägi eesti/inglise keeles"
                    userInputEt.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                    micFab.isEnabled = true
                    refreshVoiceStatus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model loading failed", e)
                withContext(Dispatchers.Main) {
                    userActionFab.isEnabled = true
                    toast("Qweni mudeli laadimine ebaõnnestus: ${e.message}")
                }
            }
        }
    }

    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                if (!file.exists()) {
                    withContext(Dispatchers.Main) { userInputEt.hint = "Qweni mudeli kopeerimine…" }
                    FileOutputStream(file).use { input.copyTo(it) }
                }
            }
        }

    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName")
            withContext(Dispatchers.Main) { userInputEt.hint = "Qweni mudeli laadimine…" }
            engine.loadModel(modelFile.path)
            engine.setSystemPrompt(HUIELL_SYSTEM_PROMPT)
        }

    private fun handleUserInput() {
        if (!isModelReady || isVoiceBusy) return
        val userMsg = userInputEt.text.toString().trim()
        if (userMsg.isEmpty()) {
            toast("Sõnum on tühi")
            return
        }

        serenaTts.stop()
        userInputEt.text = null
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        micFab.isEnabled = false

        messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
        lastAssistantMsg.clear()
        messages.add(Message(UUID.randomUUID().toString(), "", false))
        messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        messagesRv.scrollToPosition(messages.size - 1)

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            engine.sendUserPrompt(userMsg, predictLength = 260)
                .onCompletion { cause ->
                    withContext(Dispatchers.Main) {
                        userInputEt.isEnabled = true
                        userActionFab.isEnabled = true
                        micFab.isEnabled = true
                    }
                    if (cause == null) {
                        val spoken = visibleAssistantText(lastAssistantMsg.toString())
                        if (spoken.isNotBlank()) speakAssistant(spoken)
                    }
                }
                .collect { token ->
                    withContext(Dispatchers.Main) {
                        val last = messages.lastIndex
                        check(last >= 0 && !messages[last].isUser)
                        val visible = visibleAssistantText(lastAssistantMsg.append(token).toString())
                        messages[last] = messages[last].copy(content = visible)
                        messageAdapter.notifyItemChanged(last)
                        messagesRv.scrollToPosition(last)
                    }
                }
        }
    }

    private suspend fun speakAssistant(text: String) {
        try {
            serenaTts.speak(text) { status ->
                withContext(Dispatchers.Main) { refreshVoiceStatus(status) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serena TTS failed", e)
            withContext(Dispatchers.Main) {
                refreshVoiceStatus()
                toast("Serena hääl ei käivitunud: ${e.message}")
            }
        }
    }

    private fun visibleAssistantText(raw: String): String {
        var text = raw.replace(Regex("(?is)<think>.*?</think>"), "")
        val openThink = text.indexOf("<think", ignoreCase = true)
        if (openThink >= 0) text = text.substring(0, openThink)
        return text
            .replace(Regex("(?i)</?think[^>]*>"), "")
            .trimStart()
    }

    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) it.delete()
            if (!it.exists()) it.mkdir()
        }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStop() {
        generationJob?.cancel()
        serenaTts.stop()
        super.onStop()
    }

    override fun onDestroy() {
        voiceRecorder.release()
        whisper.release()
        serenaTts.stop()
        if (::engine.isInitialized) engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"

        private val HUIELL_SYSTEM_PROMPT = """
            You are Huiell, Rein's private on-device assistant.
            Follow Rein's direct instructions and answer only what he asks.
            Do not output chain-of-thought, hidden reasoning, <think> blocks, brainstorming, unrelated suggestions, or lectures.
            Do not bring up wheels, drivers, BIOS, PC tuning, safety notes, or past topics unless Rein asks for that exact topic.
            Do not argue. If something is impossible, say the blocker in one short sentence and do the closest useful step.
            Reply naturally in the same language as Rein, especially Estonian or English.
            Keep answers short and action-focused unless Rein asks for detail.
            You are local/offline unless a real connected tool was used; never claim internet use inside the phone app.
        """.trimIndent()
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> basic.name?.let { name -> basic.sizeLabel?.let { "$name-$it" } ?: name }
    architecture?.architecture != null -> architecture?.architecture?.let { arch -> basic.uuid?.let { "$arch-$it" } ?: "$arch-${System.currentTimeMillis()}" }
    else -> "model-${System.currentTimeMillis().toHexString()}"
}
