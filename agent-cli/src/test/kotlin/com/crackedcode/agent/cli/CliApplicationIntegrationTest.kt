package com.crackedcode.agent.cli

import com.crackedcode.agent.core.AgentConfig
import com.crackedcode.agent.core.SqliteSessionStore
import com.crackedcode.agent.core.ToolCallRequest
import com.crackedcode.agent.core.ToolInvocationStatus
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliApplicationIntegrationTest {
    @Test
    fun `status command lists persisted sessions`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("ccode-status-workspace")
        val config = AgentConfig(workspaceRoot = workspaceRoot)
        val store = SqliteSessionStore(config.storageRoot.resolve("state.db"))
        val session = store.createSession(config)

        val io = FakeTerminalIO(isInteractive = false)
        val app = createApplication(workspaceRoot, io)

        val exitCode = app.run(listOf("status"))

        assertEquals(0, exitCode)
        assertContains(io.output, session.id)
    }

    @Test
    fun `tools command lists available tools`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("ccode-tools-workspace")
        val io = FakeTerminalIO(isInteractive = false)
        val app = createApplication(workspaceRoot, io)

        val exitCode = app.run(listOf("tools"))

        assertEquals(0, exitCode)
        assertContains(io.output, "list_files")
        assertContains(io.output, "apply_patch")
    }

    @Test
    fun `version command prints current version`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("ccode-version-workspace")
        val io = FakeTerminalIO(isInteractive = false)
        val app = createApplication(workspaceRoot, io)

        val exitCode = app.run(listOf("version"))

        assertEquals(0, exitCode)
        assertContains(io.output, "ccode ${VersionInfo.current}")
    }

    @Test
    fun `resume command enters repl and prints pending approval`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("ccode-resume-workspace")
        val config = AgentConfig(workspaceRoot = workspaceRoot)
        val store = SqliteSessionStore(config.storageRoot.resolve("state.db"))
        val session = store.createSession(config)
        val toolCall = ToolCallRequest(
            id = "call-1",
            name = "apply_patch",
            arguments = buildJsonObject { put("patch", "--- a/file.txt\n+++ b/file.txt\n") },
        )
        store.recordToolInvocation(
            sessionId = session.id,
            toolCall = toolCall,
            status = ToolInvocationStatus.APPROVAL_REQUIRED,
            summary = "Waiting for user approval",
        )
        val approval = store.createPendingApproval(session.id, toolCall, "Approve apply_patch")

        val io = FakeTerminalIO(isInteractive = true, inputs = mutableListOf("/quit"))
        val app = createApplication(workspaceRoot, io)

        val exitCode = app.run(listOf("resume", session.id))

        assertEquals(0, exitCode)
        assertContains(io.output, "workspace: $workspaceRoot")
        assertContains(io.output, "session resumed: ${session.id}")
        assertContains(io.output, "approval required: ${approval.id}")
    }

    private fun createApplication(workspaceRoot: java.nio.file.Path, io: FakeTerminalIO): CliApplication {
        return CliApplication(
            io = io,
            workspaceResolver = WorkspaceResolver(workspaceRoot),
            configLoader = CliConfigLoader(environment = emptyMap(), userHome = Files.createTempDirectory("ccode-home")),
        )
    }
}
