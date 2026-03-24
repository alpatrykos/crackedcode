package com.crackedcode.agent.provider.openai

import com.crackedcode.agent.core.AgentJson
import com.crackedcode.agent.core.AssistantMessage
import com.crackedcode.agent.core.ConversationItem
import com.crackedcode.agent.core.ModelProvider
import com.crackedcode.agent.core.ProviderEvent
import com.crackedcode.agent.core.SessionContext
import com.crackedcode.agent.core.SystemMessage
import com.crackedcode.agent.core.ToolCallRequest
import com.crackedcode.agent.core.ToolResultMessage
import com.crackedcode.agent.core.ToolSpec
import com.crackedcode.agent.core.UserMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

data class OpenAiCompatibleConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double? = null,
)

class OpenAiCompatibleProvider(
    private val config: OpenAiCompatibleConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder().build(),
) : ModelProvider {
    override fun streamTurn(
        context: SessionContext,
        conversation: List<ConversationItem>,
        tools: List<ToolSpec>,
    ): Flow<ProviderEvent> = flow {
        val requestBody = ChatCompletionsRequest(
            model = config.model,
            messages = conversation.map(::toChatMessage),
            tools = tools.takeIf { it.isNotEmpty() }?.map(::toChatTool),
            stream = true,
            temperature = config.temperature,
        )
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${config.baseUrl.trimEnd('/')}/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(AgentJson.encodeToString(requestBody)))
        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }
        val response = httpClient.send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() !in 200..299) {
            val body = response.body().readAllBytes().toString(StandardCharsets.UTF_8)
            error("Provider request failed with ${response.statusCode()}: $body")
        }

        BufferedReader(InputStreamReader(response.body(), StandardCharsets.UTF_8)).use { reader ->
            val toolCallAccumulators = linkedMapOf<Int, ToolCallAccumulator>()
            var finishReason: String? = null
            while (true) {
                val rawLine = reader.readLine() ?: break
                if (rawLine.isBlank() || !rawLine.startsWith("data:")) {
                    continue
                }
                val payload = rawLine.removePrefix("data:").trim()
                if (payload == "[DONE]") {
                    break
                }
                val chunk = AgentJson.decodeFromString(ChatCompletionsChunk.serializer(), payload)
                val choice = chunk.choices.firstOrNull() ?: continue
                choice.delta.content?.let { emit(ProviderEvent.TextDelta(it)) }
                choice.delta.toolCalls.orEmpty().forEach { delta ->
                    val accumulator = toolCallAccumulators.getOrPut(delta.index) { ToolCallAccumulator() }
                    if (delta.id != null) {
                        accumulator.id = delta.id
                    }
                    if (delta.function?.name != null) {
                        accumulator.name = delta.function.name
                    }
                    if (delta.function?.arguments != null) {
                        accumulator.arguments.append(delta.function.arguments)
                    }
                }
                if (choice.finishReason != null) {
                    finishReason = choice.finishReason
                }
            }

            if (toolCallAccumulators.isNotEmpty()) {
                emit(
                    ProviderEvent.ToolCallsPrepared(
                        toolCallAccumulators.entries.sortedBy { it.key }.map { (_, accumulator) ->
                            ToolCallRequest(
                                id = accumulator.id ?: "call_${UUID.randomUUID()}",
                                name = accumulator.name ?: error("Missing function name in streamed tool call."),
                                arguments = parseArguments(accumulator.arguments.toString()),
                            )
                        },
                    ),
                )
            }
            emit(ProviderEvent.Completed(finishReason))
        }
    }

    private fun parseArguments(arguments: String): JsonObject {
        if (arguments.isBlank()) {
            return JsonObject(emptyMap())
        }
        return AgentJson.parseToJsonElement(arguments).jsonObject
    }

    private fun toChatTool(spec: ToolSpec): ChatTool {
        return ChatTool(
            function = ChatToolFunction(
                name = spec.name,
                description = spec.description,
                parameters = spec.parameters,
                strict = true,
            ),
        )
    }

    private fun toChatMessage(item: ConversationItem): ChatMessage {
        return when (item) {
            is SystemMessage -> ChatMessage(role = "system", content = item.content)
            is UserMessage -> ChatMessage(role = "user", content = item.content)
            is AssistantMessage -> ChatMessage(
                role = "assistant",
                content = item.content.takeIf { it.isNotBlank() },
                toolCalls = item.toolCalls.takeIf { it.isNotEmpty() }?.map { toolCall ->
                    ChatToolCall(
                        id = toolCall.id,
                        function = ChatToolCallFunction(
                            name = toolCall.name,
                            arguments = AgentJson.encodeToString(JsonObject.serializer(), toolCall.arguments),
                        ),
                    )
                },
            )

            is ToolResultMessage -> ChatMessage(
                role = "tool",
                content = item.content,
                toolCallId = item.toolCallId,
                name = item.toolName,
            )
        }
    }
}

private data class ToolCallAccumulator(
    var id: String? = null,
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder(),
)

@Serializable
private data class ChatCompletionsRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ChatTool>? = null,
    val stream: Boolean = true,
    val temperature: Double? = null,
)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
private data class ChatTool(
    val type: String = "function",
    val function: ChatToolFunction,
)

@Serializable
private data class ChatToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val strict: Boolean = true,
)

@Serializable
private data class ChatToolCall(
    val id: String,
    val type: String = "function",
    val function: ChatToolCallFunction,
)

@Serializable
private data class ChatToolCallFunction(
    val name: String,
    val arguments: String,
)

@Serializable
private data class ChatCompletionsChunk(
    val choices: List<ChatChoice>,
)

@Serializable
private data class ChatChoice(
    val delta: ChatDelta = ChatDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
private data class ChatDelta(
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCallDelta>? = null,
)

@Serializable
private data class ChatToolCallDelta(
    val index: Int,
    val id: String? = null,
    val function: ChatToolCallDeltaFunction? = null,
)

@Serializable
private data class ChatToolCallDeltaFunction(
    val name: String? = null,
    val arguments: String? = null,
)
