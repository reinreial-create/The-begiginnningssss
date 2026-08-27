package com.qwen.tts.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Local, transparent learning store. Huiell improves by retrieving facts,
 * corrections, conversations and approved web pages. It does not pretend to
 * retrain the base model weights after every message.
 */
class HuiellMemory(context: Context) {
    private val root = File(context.filesDir, "huiell").apply { mkdirs() }
    private val archive = File(root, "archive").apply { mkdirs() }

    val chatFile = File(root, "chat.jsonl")
    val factsFile = File(root, "facts.jsonl")
    val webFile = File(root, "web_knowledge.jsonl")
    val pronunciationFile = File(root, "pronunciation.jsonl")
    val voiceFile = File(root, "voice_learning.jsonl")

    @Synchronized
    fun saveChat(user: String, assistant: String) {
        append(
            chatFile,
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("user", user.take(12_000))
                .put("assistant", assistant.take(12_000))
        )
        rememberFromUser(user)
        learnPronunciationFromUser(user)
    }

    @Synchronized
    fun saveVoiceTranscript(text: String) {
        if (text.isBlank()) return
        append(
            voiceFile,
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("language", "et-EE")
                .put("source", "android-on-device-stt")
                .put("transcript", text.take(4_000))
        )
    }

    @Synchronized
    fun rememberFact(text: String, source: String = "user") {
        val clean = text.trim().take(4_000)
        if (clean.length < 3) return
        append(
            factsFile,
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("source", source)
                .put("text", clean)
        )
    }

    @Synchronized
    fun addPronunciation(written: String, spoken: String) {
        val from = written.trim().take(100)
        val to = spoken.trim().take(160)
        if (from.length < 2 || to.length < 2) return
        append(
            pronunciationFile,
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("written", from)
                .put("spoken", to)
        )
    }

    @Synchronized
    fun learnPage(url: String, title: String, pageText: String): Int {
        val clean = pageText
            .replace(Regex("[\\t\\r ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(80_000)
        if (clean.length < 80) return 0

        val fingerprint = "${url.trim()}|${clean.hashCode()}"
        if (tailObjects(webFile, 800).any { it.optString("fingerprint") == fingerprint }) return 0

        val chunks = chunk(clean, 1_500).take(48)
        chunks.forEachIndexed { index, text ->
            append(
                webFile,
                JSONObject()
                    .put("time", System.currentTimeMillis())
                    .put("url", url.take(2_000))
                    .put("title", title.take(500))
                    .put("fingerprint", fingerprint)
                    .put("chunk", index)
                    .put("text", text)
            )
        }
        return chunks.size
    }

    @Synchronized
    fun contextFor(query: String): String {
        val qTokens = tokens(query)
        if (qTokens.isEmpty()) return recentContext()

        data class Hit(val score: Int, val label: String, val text: String)
        val hits = ArrayList<Hit>()

        tailObjects(factsFile, 500).forEachIndexed { index, obj ->
            val text = obj.optString("text")
            score(qTokens, text, index)?.let { hits += Hit(it + 8, "remembered fact", text) }
        }
        tailObjects(webFile, 900).forEachIndexed { index, obj ->
            val text = obj.optString("text")
            val title = obj.optString("title")
            val url = obj.optString("url")
            score(qTokens, "$title $text", index)?.let {
                hits += Hit(it, "web: $title ($url)", text)
            }
        }
        tailObjects(chatFile, 220).forEachIndexed { index, obj ->
            val text = "User: ${obj.optString("user")}\nHuiell: ${obj.optString("assistant")}"
            score(qTokens, text, index)?.let { hits += Hit(it, "earlier conversation", text) }
        }

        val selected = hits
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.text.length })
            .distinctBy { it.text.take(240) }
            .take(7)

        if (selected.isEmpty()) return recentContext()
        return selected.joinToString("\n\n") { "[${it.label}]\n${it.text.take(1_800)}" }.take(8_500)
    }

    @Synchronized
    fun speakable(text: String): String {
        var result = text
        tailObjects(pronunciationFile, 250).forEach { obj ->
            val written = obj.optString("written")
            val spoken = obj.optString("spoken")
            if (written.isNotBlank() && spoken.isNotBlank()) {
                result = result.replace(
                    Regex("(?iu)(?<!\\p{L})${Regex.escape(written)}(?!\\p{L})"),
                    spoken
                )
            }
        }
        return result
    }

    fun backupFiles(): Map<String, File> = linkedMapOf(
        "Core Memory/chat.jsonl" to chatFile,
        "Core Memory/facts.jsonl" to factsFile,
        "Core Memory/pronunciation.jsonl" to pronunciationFile,
        "Web Library/web_knowledge.jsonl" to webFile,
        "Voice Learning/voice_learning.jsonl" to voiceFile,
    )

    fun stats(): String {
        val chats = countLines(chatFile)
        val facts = countLines(factsFile)
        val web = countLines(webFile)
        val voice = countLines(voiceFile)
        return "$chats chats • $facts facts • $web web chunks • $voice voice samples"
    }

    private fun rememberFromUser(user: String) {
        val match = Regex(
            """(?is)^\s*(?:jäta\s+meelde|pea\s+meeles|remember(?:\s+this)?)\s*[:\-]?\s+(.{3,4000})"""
        ).find(user) ?: return
        rememberFact(match.groupValues[1], "explicit-user-memory")
    }

    private fun learnPronunciationFromUser(user: String) {
        val match = Regex(
            """(?is)^\s*(?:häälda|ütle|pronounce)\s+["“]?(.{1,100}?)["”]?\s+(?:nagu|nii|as)\s*[:\-]?\s*["“]?(.{1,160}?)["”]?[.!]?\s*$"""
        ).find(user) ?: return
        addPronunciation(match.groupValues[1], match.groupValues[2])
    }

    private fun recentContext(): String {
        return tailObjects(factsFile, 12)
            .takeLast(6)
            .joinToString("\n") { "[remembered fact] ${it.optString("text")}" }
            .take(3_500)
    }

    private fun score(query: Set<String>, text: String, recency: Int): Int? {
        if (text.isBlank()) return null
        val body = tokens(text)
        val overlap = query.count { it in body }
        if (overlap == 0) return null
        val phraseBonus = if (text.lowercase(Locale.ROOT).contains(query.joinToString(" "))) 8 else 0
        return overlap * 6 + phraseBonus + (recency / 80).coerceAtMost(5)
    }

    private fun tokens(text: String): Set<String> {
        val stop = STOP_WORDS
        return TOKEN.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it !in stop }
            .take(160)
            .toSet()
    }

    private fun chunk(text: String, max: Int): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        val out = ArrayList<String>()
        var current = StringBuilder()
        fun flush() {
            val value = current.toString().trim()
            if (value.isNotBlank()) out += value
            current = StringBuilder()
        }
        for (paragraph in paragraphs) {
            val p = paragraph.trim()
            if (p.isBlank()) continue
            if (p.length > max) {
                flush()
                var start = 0
                while (start < p.length) {
                    var end = (start + max).coerceAtMost(p.length)
                    if (end < p.length) {
                        val boundary = p.lastIndexOfAny(charArrayOf('.', '!', '?', ' '), end)
                        if (boundary > start + max / 2) end = boundary + 1
                    }
                    out += p.substring(start, end).trim()
                    start = end
                }
            } else if (current.length + p.length + 2 > max) {
                flush()
                current.append(p)
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(p)
            }
        }
        flush()
        return out
    }

    private fun append(file: File, obj: JSONObject) {
        rotateIfNeeded(file)
        file.parentFile?.mkdirs()
        file.appendText(obj.toString() + "\n", Charsets.UTF_8)
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.isFile || file.length() < 12L * 1024 * 1024) return
        val target = File(archive, "${file.nameWithoutExtension}-${System.currentTimeMillis()}.jsonl")
        file.copyTo(target, overwrite = false)
        file.writeText("")
    }

    private fun tailObjects(file: File, limit: Int): List<JSONObject> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.filter { it.isNotBlank() }
                    .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                    .toList()
                    .takeLast(limit)
            }
        }.getOrDefault(emptyList())
    }

    private fun countLines(file: File): Int {
        if (!file.isFile) return 0
        return runCatching { file.useLines { it.count() } }.getOrDefault(0)
    }

    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}]{2,}")
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "have", "you", "your",
            "ning", "või", "see", "seda", "mis", "kui", "siis", "aga", "oma", "olen",
            "mulle", "sinu", "tema", "need", "seal", "siin", "ära"
        )
    }
}
