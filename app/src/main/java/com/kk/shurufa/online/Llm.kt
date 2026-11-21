package com.kk.shurufa.online

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.kk.shurufa.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The language-model services the user can bring a key for. Claude goes through the official
 * Anthropic SDK; OpenAI, DeepSeek and any OpenAI-compatible endpoint share the Chat Completions
 * protocol; Gemini has its own. All stream, so text appears while it is written.
 */
object Llm {
    /** [protocol]: "claude" (Anthropic Messages, official SDK), "openai" (Chat Completions) or "gemini". */
    class Provider(val id: String, val title: String, val defaultModel: String, val defaultBase: String, val keyHint: String, val site: String, val protocol: String)

    val PROVIDERS = listOf(
        Provider("claude", "Claude", "claude-opus-5-5", "", "sk-ant-…", "console.anthropic.com", "claude"),
        Provider("openai", "OpenAI", "gpt-5-mini", "https://api.openai.com/v1", "sk-…", "platform.openai.com", "openai"),
        Provider("gemini", "Gemini", "gemini-3.5-flash-lite", "https://generativelanguage.googleapis.com/v1beta", "AIza…", "aistudio.google.com", "gemini"),
        Provider("deepseek", "DeepSeek", "deepseek-chat", "https://api.deepseek.com/v1", "sk-…", "platform.deepseek.com", "openai"),
        Provider("qwen", "通义千问", "qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-…", "bailian.console.aliyun.com", "openai"),
        Provider("kimi", "Kimi", "moonshot-v1-8k", "https://api.moonshot.cn/v1", "sk-…", "platform.moonshot.cn", "openai"),
        Provider("glm", "智谱 GLM", "glm-4-flash", "https://open.bigmodel.cn/api/paas/v4", "", "open.bigmodel.cn", "openai"),
        Provider("doubao", "豆包（火山方舟）", "", "https://ark.cn-beijing.volces.com/api/v3", "", "console.volcengine.com/ark（模型填接入点 ID）", "openai"),
        Provider("siliconflow", "硅基流动", "deepseek-ai/DeepSeek-V3", "https://api.siliconflow.cn/v1", "sk-…", "cloud.siliconflow.cn", "openai"),
        Provider("openrouter", "OpenRouter", "openai/gpt-5-mini", "https://openrouter.ai/api/v1", "sk-or-…", "openrouter.ai", "openai"),
        Provider("grok", "Grok（xAI）", "grok-4", "https://api.x.ai/v1", "xai-…", "console.x.ai", "openai"),
        Provider("mistral", "Mistral", "mistral-small-latest", "https://api.mistral.ai/v1", "", "console.mistral.ai", "openai"),
        Provider("custom", "自定义", "", "", "", "任何服务，选择接口格式", "openai"),
    )

    val PROTOCOLS = listOf("openai" to "OpenAI 格式", "claude" to "Anthropic 格式", "gemini" to "Gemini 格式")
    /** The protocol a provider speaks (the custom one is chosen in settings). */
    fun protocol(prefs: Prefs, id: String) = if (id == "custom") prefs.sp.getString("llm_custom_protocol", "openai")!! else provider(id).protocol

    /** Suggested models per provider (any model name can be typed in settings). */
    val MODEL_SUGGESTIONS = mapOf(
        "claude" to listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
        "openai" to listOf("gpt-5-mini", "gpt-5", "gpt-5-nano"),
        "deepseek" to listOf("deepseek-chat", "deepseek-reasoner"),
        "gemini" to listOf("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-flash-lite-latest", "gemini-3.8-flash"),
    )

    fun provider(id: String) = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS[0]
    fun key(prefs: Prefs, id: String) = prefs.sp.getString("llm_${id}_key", if (id == "claude") prefs.aiKey else "")!!.trim()
    fun model(prefs: Prefs, id: String) = prefs.sp.getString("llm_${id}_model", "")!!.trim().ifEmpty { provider(id).defaultModel }
    fun base(prefs: Prefs, id: String) = prefs.sp.getString("llm_${id}_base", "")!!.trim().ifEmpty { provider(id).defaultBase }.trimEnd('/')
    fun configured(prefs: Prefs, id: String) = key(prefs, id).isNotEmpty() && model(prefs, id).isNotEmpty() && (id == "claude" || base(prefs, id).isNotEmpty())
    fun configuredIds(prefs: Prefs) = PROVIDERS.map { it.id }.filter { configured(prefs, it) }

    // ---- models the user enabled, and "targets" (a service with one of its models) ----

    /** Models switched on for [id] (选中启用); the default model when none are. */
    fun enabledModels(prefs: Prefs, id: String): List<String> =
        prefs.sp.getString("llm_${id}_enabled", "")!!.split('\n').filter { it.isNotBlank() }.ifEmpty { listOf(model(prefs, id)).filter { it.isNotBlank() } }
    fun setEnabledModels(prefs: Prefs, id: String, models: List<String>) = prefs.put("llm_${id}_enabled", models.distinct().joinToString("\n"))
    /** The last fetched model list, kept so it can be searched without fetching again. */
    fun cachedModels(prefs: Prefs, id: String): List<String> = prefs.sp.getString("llm_${id}_models", "")!!.split('\n').filter { it.isNotBlank() }
    fun setCachedModels(prefs: Prefs, id: String, models: List<String>) = prefs.put("llm_${id}_models", models.joinToString("\n"))

    /** Every service and model that can be picked: ("id|model", "Service · model"). */
    fun targets(prefs: Prefs): List<Pair<String, String>> = configuredIds(prefs).flatMap { id ->
        enabledModels(prefs, id).map { m -> "$id|$m" to "${provider(id).title} · $m" }
    }
    fun targetId(t: String) = t.substringBefore('|')
    fun targetModel(prefs: Prefs, t: String) = t.substringAfter('|', "").ifEmpty { model(prefs, targetId(t)) }
    fun targetLabel(prefs: Prefs, t: String) = "${provider(targetId(t)).title} · ${targetModel(prefs, t)}"
    /** [t] if it is still usable, else the first usable target (or null). */
    fun resolve(prefs: Prefs, t: String?): String? {
        val all = targets(prefs).map { it.first }
        if (t != null && (t in all || (!t.contains('|') && configured(prefs, t)))) return if (t.contains('|')) t else "$t|${model(prefs, t)}"
        return all.firstOrNull()
    }
    fun completeTarget(prefs: Prefs, target: String, system: String, user: String, onText: (String) -> Unit = {}, cancelled: () -> Boolean = { false }): String =
        complete(prefs, targetId(target), system, user, onText, cancelled, targetModel(prefs, target))

    /**
     * Runs one request, calling [onText] with the text so far as it streams. Blocking; call
     * it off the main thread. Errors are thrown as [IllegalStateException] with a message for
     * the user. [cancelled] is polled while streaming.
     */
    fun complete(prefs: Prefs, id: String, system: String, user: String, onText: (String) -> Unit = {}, cancelled: () -> Boolean = { false }, model: String? = null): String {
        if (!configured(prefs, id)) error("还没有设置 ${provider(id).title} 的 API Key")
        val m0 = model?.takeIf { it.isNotBlank() } ?: model(prefs, id)
        // a busy free tier (503, 429) is common: wait and try again, then a lighter Gemini model
        val models = if (protocol(prefs, id) == "gemini") listOf(m0) + GEMINI_FALLBACK.filter { it != m0 } else listOf(m0)
        var last: HttpError? = null
        for (m in models) {
            for (attempt in 0..2) {
                if (cancelled()) return ""
                var streamed = false
                try {
                    return when (protocol(prefs, id)) {
                        // Claude itself, or a service that speaks the Anthropic format at its own address
                        "claude" -> claude(key(prefs, id), m, system, user, { streamed = true; onText(it) }, cancelled, if (id == "claude") null else base(prefs, id))
                        "gemini" -> gemini(base(prefs, id), key(prefs, id), m, system, user, { streamed = true; onText(it) }, cancelled)
                        else -> openAi(base(prefs, id), key(prefs, id), m, system, user, { streamed = true; onText(it) }, cancelled)
                    }.trim().also { if (m != m0) remember(prefs, id, model, m0, m, last) }
                } catch (e: HttpError) {
                    last = e
                    if (streamed) error(e.userMessage())
                    if (e.gone && models.size > 1) break      // a retired model: the next one
                    if (!e.retryable) error(e.userMessage())
                    if (attempt < 2) Thread.sleep(if (attempt == 0) 1200L else 2800L)
                }
            }
        }
        error(last?.userMessage() ?: "出错了")
    }

    /** The default model was retired and a fallback answered: make that the default from now on. */
    private fun remember(prefs: Prefs, id: String, asked: String?, m0: String, m: String, why: HttpError?) {
        if (asked.isNullOrBlank() && why?.gone == true && model(prefs, id) == m0) prefs.put("llm_${id}_model", m)
    }

    /** Gemini models to fall back on when the chosen one is overloaded or retired. */
    private val GEMINI_FALLBACK = listOf("gemini-3.5-flash-lite", "gemini-flash-lite-latest")

    /**
     * Thinking costs seconds a keyboard can't spare (3.5 Flash thinks ~800 tokens on a
     * one-line rewrite: 6 s instead of 0.9). Ask for the least each model accepts; models
     * that refuse a level say so with a 400, and the answer is remembered per model.
     */
    private val LEVELS = listOf("minimal", "low", null)
    private val levelFor = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun <T> withThinking(model: String, call: (JSONObject) -> T): T {
        var i = levelFor[model] ?: 0
        while (true) {
            val cfg = JSONObject().apply { LEVELS[i]?.let { put("thinkingConfig", JSONObject().put("thinkingLevel", it)) } }
            try { return call(cfg).also { levelFor[model] = i } } catch (e: HttpError) {
                if (e.code == 400 && e.detail.contains("thinking", true) && i < LEVELS.lastIndex) { i++; continue }
                throw e
            }
        }
    }

    /** An HTTP failure, with the service's own explanation. */
    class HttpError(val code: Int, val detail: String) : Exception("HTTP $code: $detail") {
        val retryable get() = code == 429 || code == 500 || code in 502..504
        /** The model does not exist (any more) for this key. */
        val gone get() = code == 404
        fun userMessage() = when (code) {
            401, 403 -> "API Key 无效或没有权限"
            402 -> "账户余额不足"
            404 -> "找不到这个模型或地址：$detail"
            429 -> "请求太频繁或免费额度用完了，稍后再试（$detail）"
            503 -> "服务繁忙，模型暂时过载，重试几次仍不行（$detail）"
            in 500..599 -> "服务暂时不可用（$code：$detail）"
            else -> "出错了（$code）：$detail"
        }
    }

    /**
     * The models this key can use, from the service's own model list. Blocking. Chat models
     * only where the service says so (Gemini); others are listed as given.
     */
    fun listModels(prefs: Prefs, id: String): List<String> {
        val key = key(prefs, id)
        if (key.isEmpty()) error("先填 API Key")
        return when (protocol(prefs, id)) {
            "claude" -> {
                val client = AnthropicOkHttpClient.builder().apiKey(key).apply { if (id != "claude") baseUrl(base(prefs, id)) }.build()
                try {
                    val out = ArrayList<String>()
                    client.models().list().autoPager().forEach { out += it.id() }
                    out
                } catch (e: UnauthorizedException) { error("API Key 无效")
                } catch (e: AnthropicServiceException) { error("获取失败（${e.statusCode()}）")
                } catch (e: AnthropicIoException) { error("连不上服务器，检查一下网络或地址")
                } finally { client.close() }
            }
            "gemini" -> {
                val root = JSONObject(get("${base(prefs, id)}/models?pageSize=1000", mapOf("x-goog-api-key" to key)))
                val arr = root.optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } ?: true }
                    .map { it.getString("name").removePrefix("models/") }
                    // speech output, images, music, robots, agents: nothing a keyboard can use
                    .filterNot { n -> NOT_TEXT.any { n.contains(it) } }
                    // the suggested ones first, then newest first (Google still lists retired models)
                    .sortedWith(compareBy<String>({ s -> MODEL_SUGGESTIONS["gemini"]!!.indexOf(s).let { if (it < 0) 99 else it } }, { !it.startsWith("gemini") }).thenByDescending { it })
            }
            else -> {
                val root = JSONObject(get("${base(prefs, id)}/models", mapOf("Authorization" to "Bearer $key")))
                val arr = root.optJSONArray("data") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.sorted()
            }
        }
    }

    private val noSystem = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val NOT_TEXT = listOf("tts", "image", "banana", "lyria", "robotics", "computer-use", "customtools", "deep-research", "antigravity", "embedding", "aqa", "veo", "imagen")

    private fun get(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            if (code !in 200..299) error(when (code) { 401, 403 -> "API Key 无效或没有权限"; 404 -> "这个地址不提供模型列表"; else -> "获取失败（$code）" })
            return c.inputStream.bufferedReader().readText()
        } catch (e: java.io.IOException) { error("连不上服务器，检查一下网络或地址")
        } finally { c.disconnect() }
    }

    // ---- speech to text ----

    /** Targets that can transcribe audio (OpenAI-format audio endpoint, or Gemini listening to it). */
    fun speechTargets(prefs: Prefs): List<Pair<String, String>> = targets(prefs).filter { (t, _) -> protocol(prefs, targetId(t)) != "claude" }

    /**
     * Transcribes 16 kHz mono [pcm] with an AI service. [lang] is "zh", "en", "ru" or "auto".
     * Busy services are retried like [complete]. Blocking.
     */
    fun transcribe(prefs: Prefs, target: String, pcm: FloatArray, lang: String): String {
        val id = targetId(target); val model = targetModel(prefs, target)
        if (!configured(prefs, id)) error("还没有设置 ${provider(id).title} 的 API Key")
        val wav = wav16(pcm)
        val gemini = protocol(prefs, id) == "gemini"
        val models = if (gemini) listOf(model) + GEMINI_FALLBACK.filter { it != model } else listOf(model)
        var last: HttpError? = null
        for (m in models) {
            for (attempt in 0..2) {
                try {
                    return when (protocol(prefs, id)) {
                        "gemini" -> geminiListen(base(prefs, id), key(prefs, id), m, wav, lang)
                        "claude" -> error("${provider(id).title} 不支持语音识别")
                        else -> openAiTranscribe(base(prefs, id), key(prefs, id), m, wav, lang)
                    }.trim()
                } catch (e: HttpError) {
                    last = e
                    if (e.gone && models.size > 1) break
                    if (!e.retryable) error(e.userMessage())
                    if (attempt < 2) Thread.sleep(if (attempt == 0) 1000L else 2500L)
                }
            }
        }
        error(last?.userMessage() ?: "识别失败")
    }

    private fun wav16(pcm: FloatArray): ByteArray {
        val n = pcm.size
        val b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + n * 2); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(n * 2)
        for (v in pcm) b.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }

    private fun openAiTranscribe(base: String, key: String, model: String, wav: ByteArray, lang: String): String {
        val boundary = "kk" + System.nanoTime()
        val c = URL("$base/audio/transcriptions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 90000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        fun field(name: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
        try {
            c.outputStream.use { o ->
                o.write(field("model", model))
                if (lang != "auto") o.write(field("language", lang))
                o.write(field("response_format", "json"))
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                o.write(wav)
                o.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val msg = runCatching { c.errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
                val detail = runCatching { JSONObject(msg).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotBlank() } ?: msg.take(120)
                throw HttpError(code, detail.take(160))
            }
            val body = c.inputStream.bufferedReader().readText()
            return runCatching { JSONObject(body).optString("text") }.getOrNull() ?: body
        } catch (e: java.io.IOException) { error("连不上服务器，检查一下网络或地址")
        } finally { c.disconnect() }
    }

    private fun geminiListen(base: String, key: String, model: String, wav: ByteArray, lang: String): String {
        val langName = when (lang) { "zh" -> "Chinese (write it in Simplified Chinese)"; "en" -> "English"; "ru" -> "Russian"; else -> "the language spoken" }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))))
            .put(JSONObject().put("text", "Transcribe this recording exactly as spoken, in $langName, with natural punctuation. " +
                "Leave out hesitation sounds. Output only the transcript, nothing else.")))))
        val c = withThinking(model) { cfg -> post("$base/models/$model:generateContent", mapOf("x-goog-api-key" to key), body.put("generationConfig", cfg)) }
        val text = try { c.inputStream.bufferedReader().readText() } catch (e: java.io.IOException) { error("连接中断了，再试一次") } finally { c.disconnect() }
        val parts = runCatching { JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts") }.getOrNull() ?: return ""
        return (0 until parts.length()).map { parts.getJSONObject(it) }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
    }

    // ---- Claude (official SDK) ----

    private fun claude(key: String, model: String, system: String, user: String, onText: (String) -> Unit, cancelled: () -> Boolean, baseUrl: String? = null): String {
        val client = AnthropicOkHttpClient.builder().apiKey(key).apply { if (baseUrl != null) baseUrl(baseUrl) }.build()
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(8000L)
            .system(system)
            .addUserMessage(user)
        if (baseUrl == null) {
            // Claude: short rewrites and translations are quick at low effort, and if a safety
            // classifier declines, the API retries on a suitable model by itself
            b.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val params = b.build()
        val sb = StringBuilder()
        var refused = false
        try {
            client.beta().messages().createStreaming(params).use { stream ->
                val it = stream.stream().iterator()
                while (it.hasNext()) {
                    if (cancelled()) break
                    val ev = it.next()
                    ev.contentBlockDelta().ifPresent { d -> d.delta().text().ifPresent { t -> sb.append(t.text()); onText(sb.toString()) } }
                    ev.messageDelta().ifPresent { m -> m.delta().stopReason().ifPresent { r -> if (r == BetaStopReason.REFUSAL) refused = true } }
                }
            }
        } catch (e: UnauthorizedException) {
            error("Claude API Key 无效")
        } catch (e: PermissionDeniedException) {
            error("这个 Key 没有权限使用 $model")
        } catch (e: RateLimitException) {
            error("请求太频繁或额度用完了，稍后再试")
        } catch (e: BadRequestException) {
            error("请求被拒绝：${e.message?.take(120)}")
        } catch (e: InternalServerException) {
            error("Claude 服务暂时不可用，稍后再试")
        } catch (e: AnthropicServiceException) {
            error("Claude 出错了（${e.statusCode()}）")
        } catch (e: AnthropicIoException) {
            error("连不上 Claude，检查一下网络")
        } finally {
            client.close()
        }
        if (refused && sb.isEmpty()) error("这段内容 Claude 不便处理")
        return sb.toString()
    }

    // ---- HTTP helpers for the other services ----

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 90000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        } catch (e: java.io.IOException) { error("连不上服务器，检查一下网络或地址") }
        val code = try { c.responseCode } catch (e: java.io.IOException) { error("连不上服务器，检查一下网络或地址") }
        if (code !in 200..299) {
            val msg = runCatching { c.errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
            // {"error":{"message":…}} or, from Gemini's stream endpoint, [{"error":…}]
            val obj = runCatching { if (msg.trimStart().startsWith("[")) JSONArray(msg).getJSONObject(0) else JSONObject(msg) }.getOrNull()
            val detail = obj?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: msg.take(120)
            c.disconnect()
            throw HttpError(code, detail.take(160))
        }
        return c
    }

    /** Reads a server-sent event stream, calling [onData] with each `data:` payload. */
    private fun sse(c: HttpURLConnection, cancelled: () -> Boolean, onData: (String) -> Unit) {
        try {
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (cancelled()) break
                    if (!line.startsWith("data:")) continue
                    val d = line.removePrefix("data:").trim()
                    if (d == "[DONE]") break
                    if (d.isNotEmpty()) onData(d)
                }
            }
        } catch (e: java.io.IOException) {
            error("连接中断了，再试一次")
        } finally { c.disconnect() }
    }

    // ---- OpenAI, DeepSeek and compatible services: Chat Completions ----

    private fun openAi(base: String, key: String, model: String, system: String, user: String, onText: (String) -> Unit, cancelled: () -> Boolean): String {
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val c = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
        val sb = StringBuilder()
        sse(c, cancelled) { d ->
            val choice = runCatching { JSONObject(d).getJSONArray("choices").optJSONObject(0) }.getOrNull() ?: return@sse
            val t = choice.optJSONObject("delta")?.optString("content").orEmpty()
            if (t.isNotEmpty() && t != "null") { sb.append(t); onText(sb.toString()) }
        }
        return sb.toString()
    }

    // ---- Gemini ----

    private fun gemini(base: String, key: String, model: String, system: String, user: String, onText: (String) -> Unit, cancelled: () -> Boolean): String {
        // some models (Gemma, the transcribe model) refuse a system instruction: put it in the message instead
        fun body(fold: Boolean) = JSONObject().apply {
            if (!fold) put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", if (fold) "$system\n\n$user" else user)))))
        }
        val url = "$base/models/$model:streamGenerateContent?alt=sse"
        val c = withThinking(model) { cfg ->
            if (model in noSystem) post(url, mapOf("x-goog-api-key" to key), body(true).put("generationConfig", cfg))
            else try { post(url, mapOf("x-goog-api-key" to key), body(false).put("generationConfig", cfg)) } catch (e: HttpError) {
                if (e.code != 400 || !e.detail.contains("instruction", true)) throw e
                noSystem += model
                post(url, mapOf("x-goog-api-key" to key), body(true).put("generationConfig", cfg))
            }
        }
        val sb = StringBuilder()
        sse(c, cancelled) { d ->
            val parts = runCatching { JSONObject(d).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts") }.getOrNull() ?: return@sse
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought")) continue
                val t = p.optString("text")
                if (t.isNotEmpty()) { sb.append(t); onText(sb.toString()) }
            }
        }
        return sb.toString()
    }
}
