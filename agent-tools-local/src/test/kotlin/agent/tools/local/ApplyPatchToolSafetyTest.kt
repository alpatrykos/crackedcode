package agent.tools.local

import agent.core.ToolExecutionContext
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApplyPatchToolSafetyTest {
    @Test
    fun `apply patch rejects symlinked paths outside the workspace`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("local-tools-patch-symlink")
        val outsideRoot = Files.createTempDirectory("local-tools-patch-outside")
        Files.createSymbolicLink(workspaceRoot.resolve("linked"), outsideRoot)

        val error = assertFailsWith<IllegalStateException> {
            runBlocking {
                ApplyPatchTool().execute(
                    buildJsonObject {
                        put(
                            "patch",
                            """
                            --- /dev/null
                            +++ b/linked/escape.txt
                            @@ -0,0 +1 @@
                            +owned
                            """.trimIndent(),
                        )
                    },
                    ToolExecutionContext(
                        sessionId = "session-safety",
                        workspaceRoot = workspaceRoot,
                        artifactRoot = workspaceRoot.resolve(".artifacts"),
                        shellTimeoutMillis = 1_000,
                        maxOutputChars = 10_000,
                        protectedWorkspacePaths = setOf(workspaceRoot.resolve(".agent").normalize()),
                    ),
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("linked/escape.txt"))
        assertTrue(Files.notExists(outsideRoot.resolve("escape.txt")))
    }
}
