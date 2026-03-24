package com.crackedcode.agent.cli

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliApplicationTest {
    @Test
    fun `non interactive repl is rejected`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("ccode-app-test")
        val io = FakeTerminalIO(isInteractive = false)
        var engineFactoryCalled = false
        val app = CliApplication(
            io = io,
            workspaceResolver = WorkspaceResolver(workspaceRoot),
            engineFactory = AgentEngineFactory {
                engineFactoryCalled = true
                error("engine should not be created for non-interactive repl")
            },
        )

        val exitCode = app.run(emptyList())

        assertEquals(1, exitCode)
        assertContains(io.output, "interactive terminal")
        assertEquals(false, engineFactoryCalled)
    }

    @Test
    fun `version command does not create engine and prints version`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("ccode-version-test")
        val io = FakeTerminalIO(isInteractive = false)
        var engineFactoryCalled = false
        val app = CliApplication(
            io = io,
            workspaceResolver = WorkspaceResolver(workspaceRoot),
            engineFactory = AgentEngineFactory {
                engineFactoryCalled = true
                error("engine should not be created for version command")
            },
        )

        val exitCode = app.run(listOf("--version"))

        assertEquals(0, exitCode)
        assertEquals(false, engineFactoryCalled)
        assertContains(io.output, "ccode ${VersionInfo.current}")
    }
}
