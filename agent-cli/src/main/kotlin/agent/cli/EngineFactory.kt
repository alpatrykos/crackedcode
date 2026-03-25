package agent.cli

import agent.core.AgentConfig
import agent.core.AgentEngine
import agent.core.DefaultAgentEngine
import agent.core.DefaultApprovalPolicy
import agent.core.SqliteSessionStore
import agent.core.ToolRegistry
import agent.provider.openai.OpenAiCompatibleConfig
import agent.provider.openai.OpenAiCompatibleProvider
import agent.tools.local.defaultLocalTools
import java.nio.file.Files
import java.nio.file.Path

fun interface AgentEngineFactory {
    fun create(workspaceRoot: Path): AgentEngine
}

class DefaultAgentEngineFactory(
    private val configLoader: CliConfigLoader,
) : AgentEngineFactory {
    override fun create(workspaceRoot: Path): AgentEngine {
        val cliConfig = configLoader.load(workspaceRoot)
        val agentConfig = AgentConfig(workspaceRoot = workspaceRoot)
        Files.createDirectories(agentConfig.storageRoot)

        return DefaultAgentEngine(
            config = agentConfig,
            provider = OpenAiCompatibleProvider(
                OpenAiCompatibleConfig(
                    baseUrl = cliConfig.baseUrl,
                    apiKey = cliConfig.apiKey,
                    model = cliConfig.model,
                ),
            ),
            toolRegistry = ToolRegistry(defaultLocalTools()),
            sessionStore = SqliteSessionStore(agentConfig.storageRoot.resolve("state.db")),
            approvalPolicy = DefaultApprovalPolicy(),
        )
    }
}
