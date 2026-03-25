package agent.provider.openai

import agent.core.ProviderEvent
import agent.core.SessionContext
import agent.core.SystemMessage
import agent.core.ToolSpec
import agent.core.UserMessage
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {
    @Test
    fun `streams text and assembles tool calls`() {
        runBlocking {
            val server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/chat/completions") { exchange ->
                val body = buildString {
                    appendLine("""data: {"choices":[{"delta":{"content":"Hello "}}]}""")
                    appendLine()
                    appendLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":\""}}]}}]}""")
                    appendLine()
                    appendLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"README.md\"}"}}]},"finish_reason":"tool_calls"}]}""")
                    appendLine()
                    appendLine("data: [DONE]")
                    appendLine()
                }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { output ->
                    output.write(body.toByteArray())
                }
            }
            server.start()

            try {
                val provider = OpenAiCompatibleProvider(
                    config = OpenAiCompatibleConfig(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                        apiKey = "",
                        model = "test-model",
                    ),
                    httpClient = HttpClient.newBuilder().build(),
                )

                val events = provider.streamTurn(
                    context = SessionContext(
                        sessionId = "session-1",
                        workspaceRoot = Files.createTempDirectory("provider-workspace"),
                        storageRoot = Files.createTempDirectory("provider-storage"),
                        artifactRoot = Files.createTempDirectory("provider-artifacts"),
                        systemPrompt = "system",
                        shellTimeoutMillis = 1_000,
                        maxToolOutputChars = 10_000,
                    ),
                    conversation = listOf(SystemMessage("system"), UserMessage("inspect the repo")),
                    tools = listOf(
                        ToolSpec(
                            name = "read_file",
                            description = "Read a file",
                            parameters = buildJsonObject { put("type", "object") },
                        ),
                    ),
                ).toList()

                assertEquals("Hello ", (events[0] as ProviderEvent.TextDelta).delta)
                val toolCalls = (events[1] as ProviderEvent.ToolCallsPrepared).toolCalls
                assertEquals(1, toolCalls.size)
                assertEquals("read_file", toolCalls.first().name)
                assertEquals("README.md", toolCalls.first().arguments["path"]?.toString()?.trim('"'))
                assertIs<ProviderEvent.Completed>(events.last())
            } finally {
                server.stop(0)
            }
        }
    }
}
