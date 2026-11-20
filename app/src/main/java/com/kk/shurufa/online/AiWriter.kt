package com.kk.shurufa.online

import com.anthropic.client.AnthropicClient
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

/**
 * AI 改写: rewrites a piece of text with Claude, using the API key the user entered.
 * Only the text the user chose to send (the selection, or the field) leaves the phone.
 */
class AiWriter(private val apiKey: String, private val model: String) {
    class Action(val id: String, val title: String, val instruction: String)

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Opus 5.5（默认，最好）",
            "claude-sonnet-5-5" to "Sonnet 5.5（更快）",
            "claude-haiku-5-5" to "Haiku 5.5（最快最省）",
        )
        val ACTIONS = listOf(
            Action("polish", "润色", "Polish the text: make it fluent and natural, fix awkward wording, keep the meaning, tone and language."),
            Action("fix", "纠错", "Fix typos, wrong characters, grammar and punctuation only. Change nothing else."),
            Action("formal", "正式", "Rewrite the text in a formal, polite register suitable for work email or official messages. Keep the language."),
            Action("casual", "口语", "Rewrite the text in a relaxed, friendly, conversational register. Keep the language."),
            Action("shorter", "精简", "Make the text shorter and clearer while keeping every important point. Keep the language."),
            Action("longer", "扩写", "Expand the text with natural detail so it reads fuller, without inventing facts. Keep the language."),
            Action("to_en", "译英文", "Translate the text into natural English."),
            Action("to_ru", "译俄语", "Translate the text into natural Russian."),
            Action("to_zh", "译中文", "Translate the text into natural Simplified Chinese."),
            Action("reply", "帮我回复", "The text is a message the user received. Write one short, natural reply the user could send, in the same language as the message."),
        )

        private const val SYSTEM = "You are the writing assistant inside a phone keyboard. The user sends a piece of text and one " +
            "instruction. Reply with the resulting text only: no quotes, no explanation, no preamble, no markdown. " +
            "Keep line breaks, emoji, names, numbers and links as they are unless the instruction says otherwise."
    }

    private val client: AnthropicClient by lazy { AnthropicOkHttpClient.builder().apiKey(apiKey).build() }

    /** Blocking; call off the main thread. Throws [IllegalStateException] with a message to show. */
    fun run(action: Action, text: String): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(8000L)
            .system(SYSTEM)
            .addUserMessage("Instruction: ${action.instruction}\n\nText:\n$text")
            // short rewrites: low effort is quick and good enough
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // if a safety classifier declines, the API retries on a suitable model by itself
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val msg = try {
            client.beta().messages().create(params)
        } catch (e: UnauthorizedException) {
            error("API Key 无效，请在设置 → 联网功能里检查")
        } catch (e: PermissionDeniedException) {
            error("这个 API Key 没有权限使用 $model")
        } catch (e: RateLimitException) {
            error("请求太频繁或额度用完了，稍后再试")
        } catch (e: BadRequestException) {
            error("请求被拒绝：${e.message?.take(120)}")
        } catch (e: InternalServerException) {
            error("Claude 服务暂时不可用，稍后再试")
        } catch (e: AnthropicServiceException) {
            error("出错了（${e.statusCode()}）")
        } catch (e: AnthropicIoException) {
            error("连不上 Claude，检查一下网络")
        }
        if (msg.stopReason().map { it == BetaStopReason.REFUSAL }.orElse(false)) error("这段内容 Claude 不便处理")
        val out = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("").trim()
        if (out.isEmpty()) error("没有得到结果")
        return out
    }
}
