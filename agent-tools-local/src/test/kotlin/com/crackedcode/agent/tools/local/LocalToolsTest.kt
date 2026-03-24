package com.crackedcode.agent.tools.local

import com.crackedcode.agent.core.ToolExecutionContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalToolsTest {
    @Test
    fun `apply patch updates a file`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("local-tools-patch")
        val file = workspaceRoot.resolve("hello.txt")
        Files.writeString(file, "old\n", StandardCharsets.UTF_8)
        val tool = ApplyPatchTool()

        val result = tool.execute(
            buildJsonObject {
                put(
                    "patch",
                    """
                    --- a/hello.txt
                    +++ b/hello.txt
                    @@ -1 +1 @@
                    -old
                    +new
                    """.trimIndent(),
                )
            },
            ToolExecutionContext(
                sessionId = "session-1",
                workspaceRoot = workspaceRoot,
                artifactRoot = workspaceRoot.resolve(".artifacts"),
                shellTimeoutMillis = 1_000,
                maxOutputChars = 10_000,
            ),
        )

        assertTrue(result.content.contains("hello.txt"))
        assertEquals("new\n", Files.readString(file, StandardCharsets.UTF_8))
    }

    @Test
    fun `run shell captures output and exit code`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("local-tools-shell")
        val tool = RunShellTool()

        val result = tool.execute(
            buildJsonObject { put("command", "printf 'hi'") },
            ToolExecutionContext(
                sessionId = "session-2",
                workspaceRoot = workspaceRoot,
                artifactRoot = workspaceRoot.resolve(".artifacts"),
                shellTimeoutMillis = 1_000,
                maxOutputChars = 10_000,
            ),
        )

        assertTrue(result.content.contains("exit_code: 0"))
        assertTrue(result.content.contains("hi"))
    }
}
