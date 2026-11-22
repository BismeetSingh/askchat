package com.askchat.rag

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import java.time.ZonedDateTime

/** Same two-step flow as [ClaudeService], via OpenAI Chat Completions with strict JSON schemas. */
class OpenAiService(apiKey: String, private val model: String) : LlmService {

    private val client: OpenAIClient = OpenAIOkHttpClient.builder().apiKey(apiKey).build()

    override fun plan(question: String, now: ZonedDateTime, chatNames: List<String>, selfName: String): SearchPlan =
        Schemas.parsePlan(
            call(Prompts.PLAN_SYSTEM, Prompts.planUser(question, now, chatNames, selfName), "search_plan", Schemas.PLAN, 4000L)
        )

    override fun answer(question: String, now: ZonedDateTime, selfName: String, context: String): Answer =
        Schemas.parseAnswer(
            call(Prompts.answerSystem(selfName), Prompts.answerUser(question, now, context), "answer", Schemas.ANSWER, 8000L)
        )

    private fun call(system: String, user: String, name: String, schema: Map<String, Any>, maxTokens: Long): String {
        val format = ResponseFormatJsonSchema.builder()
            .jsonSchema(
                ResponseFormatJsonSchema.JsonSchema.builder()
                    .name(name)
                    .strict(true)
                    .schema(
                        ResponseFormatJsonSchema.JsonSchema.Schema.builder()
                            .putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) })
                            .build()
                    )
                    .build()
            )
            .build()
        val params = ChatCompletionCreateParams.builder()
            .model(model)
            .maxCompletionTokens(maxTokens)
            .addSystemMessage(system)
            .addUserMessage(user)
            .responseFormat(format)
            .build()

        val choice = client.chat().completions().create(params).choices().firstOrNull()
            ?: throw LlmException("OpenAI returned no answer.")
        choice.message().refusal().orElse(null)?.let { throw LlmException("OpenAI declined this request ($it).") }
        if (choice.finishReason() == ChatCompletion.Choice.FinishReason.LENGTH) {
            throw LlmException("Response was cut off (token limit). Try a narrower question.")
        }
        return choice.message().content().orElse(null) ?: throw LlmException("OpenAI returned no answer.")
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-6.1-sol"
    }
}
