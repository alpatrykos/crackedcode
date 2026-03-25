package agent.cli

import agent.core.AgentEngine
import agent.core.AgentEvent
import agent.core.SessionSnapshot
import agent.core.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ReplSessionTest {
    @Test
    fun `approval review failures are printed without closing the repl`() = runBlocking {
        val io = FakeTerminalIO(
            isInteractive = true,
            inputs = mutableListOf("/approve reused-approval", "/quit"),
        )
        val engine = ThrowingApprovalEngine()

        val exitCode = ReplSession(io, engine).run("session-1")

        assertEquals(0, exitCode)
        assertEquals(listOf("reused-approval"), engine.reviewedApprovalIds)
        assertContains(io.output, "session resumed: session-1")
        assertContains(
            io.output,
            "error: Approval reused-approval for session session-1 is already EXECUTED and cannot be reviewed again",
        )
        assertContains(
            io.output,
            "error: Approval reused-approval for session session-1 is already EXECUTED and cannot be reviewed again\nagent[session-1]> ",
        )
    }

    private class ThrowingApprovalEngine : AgentEngine {
        val reviewedApprovalIds = mutableListOf<String>()

        override fun startSession(prompt: String): Flow<AgentEvent> = emptyFlow()

        override fun sendUserMessage(sessionId: String, prompt: String): Flow<AgentEvent> = emptyFlow()

        override fun resumeSession(sessionId: String): Flow<AgentEvent> = flowOf(AgentEvent.SessionResumed(sessionId))

        override fun reviewApproval(sessionId: String, approvalId: String, approved: Boolean): Flow<AgentEvent> = flow {
            reviewedApprovalIds += approvalId
            throw IllegalStateException(
                "Approval $approvalId for session $sessionId is already EXECUTED and cannot be reviewed again",
            )
        }

        override suspend fun getSessionSnapshot(sessionId: String): SessionSnapshot? = null

        override suspend fun listSessions(limit: Int): List<SessionSnapshot> = emptyList()

        override fun listTools(): List<ToolSpec> = emptyList()
    }
}
