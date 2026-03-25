package agent.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CliConfigLoaderTest {
    @Test
    fun `environment overrides workspace and global config`() {
        val userHome = Files.createTempDirectory("agent-home")
        val workspaceRoot = Files.createTempDirectory("agent-workspace")
        val globalConfig = userHome.resolve(".config").resolve("agent").resolve("config.properties")
        Files.createDirectories(globalConfig.parent)
        Files.writeString(
            globalConfig,
            """
            baseUrl=https://global.example/v1
            model=global-model
            apiKey=global-key
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val workspaceConfig = workspaceRoot.resolve(".agent").resolve("config.properties")
        Files.createDirectories(workspaceConfig.parent)
        Files.writeString(
            workspaceConfig,
            """
            baseUrl=https://workspace.example/v1
            model=workspace-model
            apiKey=workspace-key
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val loader = CliConfigLoader(
            environment = mapOf(
                "OPENAI_BASE_URL" to "https://env.example/v1",
                "AGENT_MODEL" to "env-model",
                "OPENAI_API_KEY" to "env-key",
            ),
            userHome = userHome,
        )

        val config = loader.load(workspaceRoot)

        assertEquals("https://env.example/v1", config.baseUrl)
        assertEquals("env-model", config.model)
        assertEquals("env-key", config.apiKey)
    }
}
