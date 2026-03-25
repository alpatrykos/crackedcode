package agent.tools.local

import agent.core.ToolExecutionContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                protectedWorkspacePaths = setOf(workspaceRoot.resolve(".agent").normalize()),
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
                protectedWorkspacePaths = setOf(workspaceRoot.resolve(".agent").normalize()),
            ),
        )

        assertTrue(result.content.contains("exit_code: 0"))
        assertTrue(result.content.contains("hi"))
    }

    @Test
    fun `read file blocks protected workspace paths`() {
        val workspaceRoot = Files.createTempDirectory("local-tools-read")
        val protectedFile = workspaceRoot.resolve(".agent").resolve("config.properties")
        Files.createDirectories(protectedFile.parent)
        Files.writeString(protectedFile, "apiKey=secret\n", StandardCharsets.UTF_8)
        val symlink = workspaceRoot.resolve("linked-secret.properties")
        Files.createSymbolicLink(symlink, protectedFile)

        val error = assertFailsWith<IllegalStateException> {
            runBlocking {
                ReadFileTool().execute(
                    buildJsonObject { put("path", ".agent/config.properties") },
                    toolContext(workspaceRoot),
                )
            }
        }

        assertTrue(error.message.orEmpty().contains(".agent/config.properties"))
        val symlinkError = assertFailsWith<IllegalStateException> {
            runBlocking {
                ReadFileTool().execute(
                    buildJsonObject { put("path", "linked-secret.properties") },
                    toolContext(workspaceRoot),
                )
            }
        }

        assertTrue(symlinkError.message.orEmpty().contains("linked-secret.properties"))
    }

    @Test
    fun `read file clamps invalid line ranges`() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("local-tools-range")
        Files.writeString(
            workspaceRoot.resolve("sample.txt"),
            "first\nsecond\nthird\n",
            StandardCharsets.UTF_8,
        )

        val pastEof = ReadFileTool().execute(
            buildJsonObject {
                put("path", "sample.txt")
                put("start_line", 10)
            },
            toolContext(workspaceRoot),
        )

        assertEquals("", pastEof.content)
        assertEquals("4", pastEof.metadata["start_line"]?.jsonPrimitive?.content)
        assertEquals("3", pastEof.metadata["end_line"]?.jsonPrimitive?.content)

        val reversed = ReadFileTool().execute(
            buildJsonObject {
                put("path", "sample.txt")
                put("start_line", 2)
                put("end_line", -5)
            },
            toolContext(workspaceRoot),
        )

        assertEquals("", reversed.content)
        assertEquals("2", reversed.metadata["start_line"]?.jsonPrimitive?.content)
        assertEquals("1", reversed.metadata["end_line"]?.jsonPrimitive?.content)
    }

    @Test
    fun `list files hides protected workspace paths`() {
        val workspaceRoot = Files.createTempDirectory("local-tools-list")
        Files.writeString(workspaceRoot.resolve("visible.txt"), "hello\n", StandardCharsets.UTF_8)
        val protectedDir = workspaceRoot.resolve(".agent")
        Files.createDirectories(protectedDir)
        Files.writeString(protectedDir.resolve("secret.txt"), "secret\n", StandardCharsets.UTF_8)
        Files.createSymbolicLink(workspaceRoot.resolve("linked-secret.txt"), protectedDir.resolve("secret.txt"))

        val result = runBlocking {
            ListFilesTool().execute(
                buildJsonObject {
                    put("path", ".")
                    put("max_depth", 3)
                },
                toolContext(workspaceRoot),
            )
        }

        assertTrue(result.content.contains("visible.txt"))
        assertTrue(!result.content.contains(".agent"))
        assertTrue(!result.content.contains("linked-secret.txt"))
        assertFailsWith<IllegalStateException> {
            runBlocking {
                ListFilesTool().execute(
                    buildJsonObject { put("path", ".agent") },
                    toolContext(workspaceRoot),
                )
            }
        }
    }

    @Test
    fun `search files hides protected workspace paths`() {
        val workspaceRoot = Files.createTempDirectory("local-tools-search")
        Files.writeString(workspaceRoot.resolve("visible.txt"), "needle\n", StandardCharsets.UTF_8)
        val protectedDir = workspaceRoot.resolve(".agent")
        Files.createDirectories(protectedDir)
        Files.writeString(protectedDir.resolve("secret.txt"), "needle\n", StandardCharsets.UTF_8)

        val result = runBlocking {
            SearchFilesTool().execute(
                buildJsonObject {
                    put("query", "needle")
                    put("path", ".")
                },
                toolContext(workspaceRoot),
            )
        }

        assertTrue(result.content.contains("visible.txt:1:needle"))
        assertTrue(!result.content.contains(".agent"))
        assertFailsWith<IllegalStateException> {
            runBlocking {
                SearchFilesTool().execute(
                    buildJsonObject {
                        put("query", "needle")
                        put("path", ".agent")
                    },
                    toolContext(workspaceRoot),
                )
            }
        }
    }

    private fun toolContext(workspaceRoot: java.nio.file.Path): ToolExecutionContext {
        return ToolExecutionContext(
            sessionId = "session-test",
            workspaceRoot = workspaceRoot,
            artifactRoot = workspaceRoot.resolve(".artifacts"),
            shellTimeoutMillis = 1_000,
            maxOutputChars = 10_000,
            protectedWorkspacePaths = setOf(workspaceRoot.resolve(".agent").normalize()),
        )
    }
}
