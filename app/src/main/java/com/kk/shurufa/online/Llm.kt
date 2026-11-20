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
    class Provider(val id: String, val title: String, val defaultModel: String, val defaultBase: String, val keyHint: String, val site: String)

    val PROVIDERS = listOf(
        Provider("claude", "Claude", "claude-opus-5-5", "", "sk-ant-…", "console.anthropic.com"),
        Provider("openai", "OpenAI", "gpt-5-mini", "https://api.openai.com/v1", "sk-…", "platform.openai.com"),
        Provider("deepseek", "DeepSeek", "deepseek-chat", "https://api.deepseek.com/v1", "sk-…", "platform.deepseek.com"),
        Provider("gemini", "Gemini", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta", "AIza…", "aistudio.google.com"),
        Provider("custom", "自定义（OpenAI 兼容）", "", "", "", "任何兼容 /chat/completions 的服务"),
    )

    /** Suggested models per provider (any model name can be typed in settings). */
    val MODEL_SUGGESTIONS = mapOf(
        "claude" to listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
        "openai" to listOf("gpt-5-mini", "gpt-5", "gpt-5-nano"),
        "deepseek" to listOf("deepseek-chat", "deepseek-reasoner"),
        "gemini" to listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite"),
    )

    fun provider(id: String) = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS[0]
    fun key(prefs: Prefs, id: String) = prefs.sp.getString("llm_${id}_key", if (id == "claude") prefs.aiKey else "")!!.trim()
    fun model(prefs: Prefs, id: String) = prefs.sp.getString("llm_${id}_model", "")!!.trim().ifEmpty { provider(id).defaultModel }
    fun base(prefs: Prefs, id: String) = prefs.sp.getString("llm_${id}_base", "")!!.trim().ifEmpty { provider(id).defaultBase }.trimEnd('/')
    fun configured(prefs: Prefs, id: String) = key(prefs, id).isNotEmpty() && model(prefs, id).isNotEmpty() && (id == "claude" || base(prefs, id).isNotEmpty())
    fun configuredIds(prefs: Prefs) = PROVIDERS.map { it.id }.filter { configured(prefs, it) }

    /**
     * Runs one request, calling [onText] with the text so far as it streams. Blocking; call
     * it off the main thread. Errors are thrown as [IllegalStateException] with a message for
     * the user. [cancelled] is polled while streaming.
     */
    fun complete(prefs: Prefs, id: String, system: String, user: String, onText: (String) -> Unit = {}, cancelled: () -> Boolean = { false }): String {
        if (!configured(prefs, id)) error("还没有设置 ${provider(id).title} 的 API Key")
        return when (id) {
            "claude" -> claude(key(prefs, id), model(prefs, id), system, user, onText, cancelled)
            "gemini" -> gemini(base(prefs, id), key(prefs, id), model(prefs, id), system, user, onText, cancelled)
            else -> openAi(base(prefs, id), key(prefs, id), model(prefs, id), system, user, onText, cancelled)
        }.trim()
    }

    // ---- Claude (official SDK) ----

    private fun claude(key: String, model: String, system: String, user: String, onText: (String) -> Unit, cancelled: () -> Boolean): String {
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(8000L)
            .system(system)
            .addUserMessage(user)
            // short rewrites and translations: low effort is quick and good enough
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // if a safety classifier declines, the API retries on a suitable model by itself
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
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
            val detail = runCatching { JSONObject(msg).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotBlank() } ?: msg.take(120)
            c.disconnect()
            error(when (code) {
                401, 403 -> "API Key 无效或没有权限"
                402 -> "账户余额不足"
                404 -> "找不到这个模型或地址：$detail"
                429 -> "请求太频繁或额度用完了，稍后再试"
                in 500..599 -> "服务暂时不可用（$code）"
                else -> "出错了（$code）：$detail"
            })
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
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
        val c = post("$base/models/$model:streamGenerateContent?alt=sse", mapOf("x-goog-api-key" to key), body)
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
