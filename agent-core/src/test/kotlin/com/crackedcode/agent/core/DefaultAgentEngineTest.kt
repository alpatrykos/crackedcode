package com.crackedcode.agent.core

import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultAgentEngineTest {
    @Test
    fun `mutating tool requires approval before execution`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("crackedcode-engine-test")
        val config = AgentConfig(workspaceRoot = workspaceRoot)
        val store = SqliteSessionStore(config.storageRoot.resolve("state.db"))
        val executedCalls = mutableListOf<String>()
        val tool = object : Tool {
            override val spec: ToolSpec = ToolSpec(
                name = "apply_patch",
                description = "Apply a patch",
                parameters = JsonObject(emptyMap()),
                mutating = true,
            )

            override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResult {
                executedCalls += arguments.toString()
                return ToolResult("patch applied")
            }
        }
        val provider = ScriptedProvider()
        val engine = DefaultAgentEngine(
            config = config,
            provider = provider,
            toolRegistry = ToolRegistry(listOf(tool)),
            sessionStore = store,
            approvalPolicy = DefaultApprovalPolicy(),
        )

        val initialEvents = engine.startSession("change the file").toList()
        val sessionId = (initialEvents.first { it is AgentEvent.SessionStarted } as AgentEvent.SessionStarted).sessionId
        val approvalEvent = initialEvents.first { it is AgentEvent.ApprovalRequired } as AgentEvent.ApprovalRequired
        assertTrue(executedCalls.isEmpty(), "tool should not execute before approval")
        assertEquals(SessionStatus.WAITING_APPROVAL, store.loadSession(sessionId)?.status)

        val approvalEvents = engine.reviewApproval(sessionId, approvalEvent.approval.id, approved = true).toList()
        assertTrue(executedCalls.isNotEmpty(), "tool should execute after approval")
        assertTrue(approvalEvents.any { it is AgentEvent.ToolExecutionCompleted && it.toolName == "apply_patch" })
        val snapshot = store.loadSession(sessionId)
        assertNotNull(snapshot)
        assertEquals(SessionStatus.IDLE, snapshot.status)
        assertTrue(snapshot.items.any { it is ToolResultMessage && it.toolName == "apply_patch" })
        assertTrue(snapshot.items.any { it is AssistantMessage && it.content.contains("Done") })
    }

    private class ScriptedProvider : ModelProvider {
        override fun streamTurn(
            context: SessionContext,
            conversation: List<ConversationItem>,
            tools: List<ToolSpec>,
        ): Flow<ProviderEvent> = flow {
            val hasToolResult = conversation.any { it is ToolResultMessage }
            if (!hasToolResult) {
                emit(
                    ProviderEvent.ToolCallsPrepared(
                        listOf(
                            ToolCallRequest(
                                id = "call-1",
                                name = "apply_patch",
                                arguments = AgentJson.parseToJsonElement("""{"patch":"--- a/file.txt\n+++ b/file.txt\n@@ -1 +1 @@\n-old\n+new\n"}""").jsonObject,
                            ),
                        ),
                    ),
                )
                emit(ProviderEvent.Completed("tool_calls"))
            } else {
                emit(ProviderEvent.TextDelta("Done."))
                emit(ProviderEvent.Completed("stop"))
            }
        }
    }
}
