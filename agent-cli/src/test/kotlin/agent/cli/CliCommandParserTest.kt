package agent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CliCommandParserTest {
    private val parser = CliCommandParser()

    @Test
    fun `parses default repl command`() {
        val invocation = parser.parse(emptyList())

        assertEquals(null, invocation.workspaceOverride)
        assertEquals(CliCommand.Repl(), invocation.command)
    }

    @Test
    fun `parses resume with workspace flag`() {
        val invocation = parser.parse(listOf("--workspace", "/tmp/project", "resume", "session-123"))

        assertEquals("/tmp/project", invocation.workspaceOverride)
        val command = assertIs<CliCommand.Repl>(invocation.command)
        assertEquals("session-123", command.resumeSessionId)
    }

    @Test
    fun `parses status command with optional session id`() {
        val invocation = parser.parse(listOf("status", "session-123"))

        val command = assertIs<CliCommand.Status>(invocation.command)
        assertEquals("session-123", command.sessionId)
    }

    @Test
    fun `parses version commands`() {
        assertEquals(CliCommand.Version, parser.parse(listOf("version")).command)
        assertEquals(CliCommand.Version, parser.parse(listOf("--version")).command)
        assertEquals(CliCommand.Version, parser.parse(listOf("-v")).command)
    }

    @Test
    fun `rejects unknown commands`() {
        assertFailsWith<CliUsageException> {
            parser.parse(listOf("bogus"))
        }
    }
}
