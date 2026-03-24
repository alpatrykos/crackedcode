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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultAgentEngineTest {
    @Test
    fun `provider failures mark the session failed without crashing the flow`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("crackedcode-engine-provider-failure-test")
        val config = AgentConfig(workspaceRoot = workspaceRoot)
        val store = SqliteSessionStore(config.storageRoot.resolve("state.db"))
        val provider = object : ModelProvider {
            override fun streamTurn(
                context: SessionContext,
                conversation: List<ConversationItem>,
                tools: List<ToolSpec>,
            ): Flow<ProviderEvent> = flow {
                emit(ProviderEvent.TextDelta("partial"))
                error("Provider request failed with 401")
            }
        }
        val engine = DefaultAgentEngine(
            config = config,
            provider = provider,
            toolRegistry = ToolRegistry(emptyList()),
            sessionStore = store,
            approvalPolicy = DefaultApprovalPolicy(),
        )

        val events = engine.startSession("hello").toList()
        val sessionId = (events.first { it is AgentEvent.SessionStarted } as AgentEvent.SessionStarted).sessionId

        assertTrue(events.any { it is AgentEvent.StatusChanged && it.status == SessionStatus.RUNNING })
        assertTrue(events.any { it is AgentEvent.AssistantTextDelta && it.delta == "partial" })
        assertTrue(events.any { it is AgentEvent.StatusChanged && it.status == SessionStatus.FAILED })
        assertEquals(
            "Provider request failed with 401",
            (events.first { it is AgentEvent.Error } as AgentEvent.Error).message,
        )
        assertFalse(events.any { it is AgentEvent.AssistantTurnCompleted })

        val snapshot = store.loadSession(sessionId)
        assertNotNull(snapshot)
        assertEquals(SessionStatus.FAILED, snapshot.status)
        assertTrue(snapshot.items.none { it is AssistantMessage })
    }

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

    @Test
    fun `resolved approvals cannot be reviewed again`() = runBlocking {
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
        val approvalId = (initialEvents.first { it is AgentEvent.ApprovalRequired } as AgentEvent.ApprovalRequired).approval.id

        engine.reviewApproval(sessionId, approvalId, approved = true).toList()

        val error = assertFailsWith<IllegalStateException> {
            engine.reviewApproval(sessionId, approvalId, approved = true).toList()
        }

        assertEquals(1, executedCalls.size)
        assertEquals(
            "Approval $approvalId for session $sessionId is already EXECUTED and cannot be reviewed again",
            error.message,
        )
    }

    @Test
    fun `approved approvals are executed when a session resumes`() = runBlocking {
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
        val approvalId = (initialEvents.first { it is AgentEvent.ApprovalRequired } as AgentEvent.ApprovalRequired).approval.id

        store.updatePendingApproval(sessionId, approvalId, PendingApprovalStatus.APPROVED)

        val resumeEvents = engine.resumeSession(sessionId).toList()

        assertTrue(executedCalls.isNotEmpty(), "tool should execute after resuming an approved approval")
        assertTrue(resumeEvents.any { it is AgentEvent.ToolExecutionStarted && it.toolCall.id == "call-1" })
        assertTrue(resumeEvents.any { it is AgentEvent.ToolExecutionCompleted && it.toolCallId == "call-1" })
        assertTrue(resumeEvents.none { it is AgentEvent.StatusChanged && it.status == SessionStatus.WAITING_APPROVAL })

        val snapshot = store.loadSession(sessionId)
        assertNotNull(snapshot)
        assertEquals(SessionStatus.IDLE, snapshot.status)
        assertTrue(snapshot.pendingApprovals.none { it.status == PendingApprovalStatus.APPROVED || it.status == PendingApprovalStatus.PENDING })
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
