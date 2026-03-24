package com.crackedcode.agent.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteSessionStoreTest {
    @Test
    fun `create session and reload persisted data`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("crackedcode-store-test")
        val config = AgentConfig(workspaceRoot = workspaceRoot)
        val store = SqliteSessionStore(config.storageRoot.resolve("state.db"))

        val session = store.createSession(config)
        store.appendConversationItem(session.id, UserMessage("hello"))
        val toolCall = ToolCallRequest(id = "call-1", name = "read_file", arguments = JsonObject(emptyMap()))
        store.recordToolInvocation(session.id, toolCall, ToolInvocationStatus.REQUESTED, "tool requested")
        store.createPendingApproval(session.id, toolCall, "Approve read_file")

        val reloaded = store.loadSession(session.id)
        assertNotNull(reloaded)
        assertEquals(session.id, reloaded.id)
        assertEquals(SessionStatus.IDLE, reloaded.status)
        assertEquals(2, reloaded.items.size)
        assertTrue(reloaded.pendingApprovals.isNotEmpty())
        assertEquals("read_file", reloaded.pendingApprovals.first().toolName)
        assertEquals("hello", (reloaded.items[1] as UserMessage).content)
    }
}
