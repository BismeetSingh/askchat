package com.askchat.rag

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import java.time.ZonedDateTime

class ClaudeService(apiKey: String) : LlmService {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override fun plan(question: String, now: ZonedDateTime, chatNames: List<String>, selfName: String): SearchPlan =
        Schemas.parsePlan(
            call(Prompts.PLAN_SYSTEM, Prompts.planUser(question, now, chatNames, selfName), Schemas.PLAN, 4000L)
        )

    override fun answer(question: String, now: ZonedDateTime, selfName: String, context: String): Answer =
        Schemas.parseAnswer(
            call(Prompts.answerSystem(selfName), Prompts.answerUser(question, now, context), Schemas.ANSWER, 8000L)
        )

    /** One structured-output request; returns the JSON text the model produced. */
    private fun call(system: String, user: String, schema: Map<String, Any>, maxTokens: Long): String {
        val format = JsonOutputFormat.builder()
            .schema(
                JsonOutputFormat.Schema.builder()
                    .putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) })
                    .build()
            )
            .build()
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(maxTokens)
            .system(system)
            .outputConfig(OutputConfig.builder().format(format).build())
            .addUserMessage(user)
            // Server-side fallback: if a safety classifier declines, retry on a fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        return extract(client.messages().create(params))
    }

    private fun extract(response: Message): String {
        when (response.stopReason().orElse(null)) {
            StopReason.REFUSAL -> {
                val why = response.stopDetails().flatMap { it.explanation() }.orElse("no details")
                throw LlmException("Claude declined this request ($why).")
            }
            StopReason.MAX_TOKENS -> throw LlmException("Response was cut off (max_tokens). Try a narrower question.")
            else -> Unit
        }
        return response.content().firstNotNullOfOrNull { block -> block.text().orElse(null)?.text() }
            ?: throw LlmException("Claude returned no answer.")
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
    }
}
