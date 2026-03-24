package com.crackedcode.agent.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class CliConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

class CliConfigLoader(
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: Path = Path.of(System.getProperty("user.home")).normalize(),
) {
    fun load(workspaceRoot: Path): CliConfig {
        val globalProperties = readProperties(userHome.resolve(".config").resolve("ccode").resolve("config.properties"))
        val workspaceProperties = readProperties(workspaceRoot.resolve(".crackedcode-agent").resolve("config.properties"))

        return CliConfig(
            baseUrl = environment["OPENAI_BASE_URL"]
                ?: workspaceProperties.getProperty("baseUrl")
                ?: globalProperties.getProperty("baseUrl")
                ?: "https://api.openai.com/v1",
            model = environment["CRACKEDCODE_MODEL"]
                ?: workspaceProperties.getProperty("model")
                ?: globalProperties.getProperty("model")
                ?: "gpt-4.1-mini",
            apiKey = environment["OPENAI_API_KEY"]
                ?: workspaceProperties.getProperty("apiKey")
                ?: globalProperties.getProperty("apiKey")
                ?: "",
        )
    }

    private fun readProperties(path: Path): Properties {
        val properties = Properties()
        if (Files.exists(path)) {
            Files.newBufferedReader(path).use { reader ->
                properties.load(reader)
            }
        }
        return properties
    }
}
