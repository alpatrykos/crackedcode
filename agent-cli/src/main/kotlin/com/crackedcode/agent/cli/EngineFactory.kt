package com.crackedcode.agent.cli

import com.crackedcode.agent.core.AgentConfig
import com.crackedcode.agent.core.AgentEngine
import com.crackedcode.agent.core.DefaultAgentEngine
import com.crackedcode.agent.core.DefaultApprovalPolicy
import com.crackedcode.agent.core.SqliteSessionStore
import com.crackedcode.agent.core.ToolRegistry
import com.crackedcode.agent.provider.openai.OpenAiCompatibleConfig
import com.crackedcode.agent.provider.openai.OpenAiCompatibleProvider
import com.crackedcode.agent.tools.local.defaultLocalTools
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
