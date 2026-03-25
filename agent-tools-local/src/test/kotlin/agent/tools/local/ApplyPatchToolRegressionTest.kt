package agent.tools.local

import agent.core.ToolExecutionContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplyPatchToolRegressionTest {
    @Test
    fun `newline terminated unified diff applies successfully`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("apply-patch-regression")
        val file = workspaceRoot.resolve("hello.txt")
        Files.writeString(file, "old\n", StandardCharsets.UTF_8)

        val result = ApplyPatchTool().execute(
            buildJsonObject {
                put(
                    "patch",
                    """
                    --- a/hello.txt
                    +++ b/hello.txt
                    @@ -1 +1 @@
                    -old
                    +new
                    """.trimIndent() + "\n",
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
}
