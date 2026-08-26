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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var serenaStatus: String = "Serena: käivitub…"
    private var pendingVoiceStart = false
    private var isVoiceBusy = false

    private var isModelReady = false
    private var activeLlmModelFile: File? = null
    private var activeLlmModelName: String? = null
    private val messages = mutableListOf<Message>()
    private val rawAssistantMsg = StringBuilder()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

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
        serenaStatus = serenaTts.modelStatus()
        refreshVoiceStatus()

        onBackPressedDispatcher.addCallback {
            if (voiceRecorder.isRecording) stopVoiceAndSend() else Log.w(TAG, "Back ignored")
        }

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
        }

        // The owner explicitly chose Serena. Download her models once; after that she is offline.
        lifecycleScope.launch {
            try {
                serenaTts.ensureModels { status -> updateSerenaStatus(status) }
            } catch (e: Exception) {
                Log.e(TAG, "Serena model preparation failed", e)
                updateSerenaStatus("Serena: allalaadimine ebaõnnestus • proovin rääkimisel uuesti")
            }
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
            var imported = false
            setVoiceBusy(true, "Whisperi mudeli kopeerimine…")
            try {
                whisper.importModel(uri)
                imported = true
                refreshVoiceStatus()
                toast("Whisper valmis. Kõnetuvastus töötab nüüd offline.")
            } catch (e: Exception) {
                Log.e(TAG, "Whisper model import failed", e)
                toast("Whisperi mudeli import ebaõnnestus: ${e.message}")
            } finally {
                setVoiceBusy(false)
            }
            if (imported) ensureMicPermissionAndStart()
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
            voiceRecorder.start(lifecycleScope)
            micFab.setImageResource(R.drawable.outline_stop_24)
            userInputEt.hint = "Kuulan… vajuta mikrofoni uuesti, kui lõpetad"
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
            userInputEt.hint = if (isModelReady) "Kirjuta või räägi" else "Vali Qweni GGUF mudel"
        }
    }

    private fun refreshVoiceStatus() {
        whisperStatusTv.text = "${whisper.modelStatus()}\n$serenaStatus"
    }

    private suspend fun updateSerenaStatus(status: String) {
        withContext(Dispatchers.Main) {
            serenaStatus = status
            refreshVoiceStatus()
        }
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
                activeLlmModelName = modelName
                activeLlmModelFile = modelFile

                withContext(Dispatchers.Main) {
                    isModelReady = true
                    userInputEt.hint = "Kirjuta või räägi"
                    userInputEt.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                    micFab.isEnabled = true
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
        if (!isModelReady || isVoiceBusy || generationJob?.isActive == true) return
        val userMsg = userInputEt.text.toString().trim()
        if (userMsg.isEmpty()) return

        userInputEt.text = null
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        micFab.isEnabled = false

        messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
        rawAssistantMsg.clear()
        lastAssistantMsg.clear()
        messages.add(Message(UUID.randomUUID().toString(), "", false))
        messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        messagesRv.scrollToPosition(messages.size - 1)

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            var completedNormally = false
            try {
                engine.sendUserPrompt("/no_think\n$userMsg", predictLength = 320)
                    .collect { token ->
                        rawAssistantMsg.append(token)
                        val visible = stripThinking(rawAssistantMsg.toString())
                        lastAssistantMsg.clear().append(visible)
                        withContext(Dispatchers.Main) {
                            val last = messages.lastIndex
                            check(last >= 0 && !messages[last].isUser)
                            messages[last] = messages[last].copy(content = visible)
                            messageAdapter.notifyItemChanged(last)
                            messagesRv.scrollToPosition(last)
                        }
                    }
                completedNormally = true

                val finalText = stripThinking(rawAssistantMsg.toString()).trim()
                if (finalText.isNotEmpty()) {
                    speakWithSerenaAndRestoreLlm(finalText)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Generation or Serena speech failed", e)
                if (completedNormally) {
                    updateSerenaStatus("Serena: ${e.message ?: "viga"}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    userInputEt.isEnabled = true
                    userActionFab.isEnabled = true
                    micFab.isEnabled = true
                    userInputEt.hint = "Kirjuta või räägi"
                }
            }
        }
    }

    private suspend fun speakWithSerenaAndRestoreLlm(text: String) = withContext(Dispatchers.IO) {
        val llmFile = activeLlmModelFile
        val llmName = activeLlmModelName

        // S22 memory is limited: unload the chat model while Serena's 1.7B TTS model is resident.
        engine.cleanUp()
        try {
            serenaTts.speak(text) { status -> updateSerenaStatus(status) }
        } finally {
            if (llmFile != null && llmFile.isFile) {
                updateSerenaStatus("Serena valmis • Huielli mudeli taastamine…")
                engine.loadModel(llmFile.path)
                engine.setSystemPrompt(HUIELL_SYSTEM_PROMPT)
                if (llmName != null) Log.i(TAG, "Restored LLM $llmName after Serena")
                updateSerenaStatus(serenaTts.modelStatus())
            }
        }
    }

    private fun stripThinking(raw: String): String {
        var visible = raw
            .replace(THINK_BLOCK_REGEX, "")
            .replace(THINK_TAG_REGEX, "")
            .replace("/no_think", "", ignoreCase = true)

        val unclosedThink = visible.indexOf("<think>", ignoreCase = true)
        if (unclosedThink >= 0) visible = visible.substring(0, unclosedThink)

        return visible.trimStart()
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
        private val THINK_BLOCK_REGEX = Regex("(?is)<think>.*?</think>")
        private val THINK_TAG_REGEX = Regex("(?i)</?think>")

        private val HUIELL_SYSTEM_PROMPT = """
            /no_think
            You are Huiell. Execute the user's explicit request and return only the requested result.
            Never show chain-of-thought, internal reasoning, analysis, planning, self-talk, or hidden deliberation.
            Do not add unsolicited suggestions, alternatives, follow-up offers, lectures, warnings, or extra commentary.
            Do not ask a follow-up question unless the request genuinely cannot be completed without one missing fact.
            Match the user's language and tone. Keep the answer as short as the request allows.
            Never claim to have used tools, the internet, files, or actions that did not actually happen.
        """.trimIndent()
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> basic.name?.let { name -> basic.sizeLabel?.let { "$name-$it" } ?: name }
    architecture?.architecture != null -> architecture?.architecture?.let { arch -> basic.uuid?.let { "$arch-$it" } ?: "$arch-${System.currentTimeMillis()}" }
    else -> "model-${System.currentTimeMillis().toHexString()}"
}
