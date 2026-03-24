package com.crackedcode.agent.cli

import com.crackedcode.agent.core.AgentConfig
import com.crackedcode.agent.core.AgentEngine
import com.crackedcode.agent.core.AgentEvent
import com.crackedcode.agent.core.DefaultAgentEngine
import com.crackedcode.agent.core.DefaultApprovalPolicy
import com.crackedcode.agent.core.PendingApprovalStatus
import com.crackedcode.agent.core.SqliteSessionStore
import com.crackedcode.agent.core.ToolRegistry
import com.crackedcode.agent.provider.openai.OpenAiCompatibleConfig
import com.crackedcode.agent.provider.openai.OpenAiCompatibleProvider
import com.crackedcode.agent.tools.local.defaultLocalTools
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    val workspaceRoot = Path.of(System.getProperty("user.dir")).normalize()
    val cliConfig = CliConfig.load(workspaceRoot)
    val agentConfig = AgentConfig(workspaceRoot = workspaceRoot)
    Files.createDirectories(agentConfig.storageRoot)

    val engine = DefaultAgentEngine(
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

    repl(engine)
}

private suspend fun repl(engine: AgentEngine) {
    var activeSessionId: String? = null
    println("CrackedCode Kotlin Agent")
    printHelp()

    while (true) {
        print(if (activeSessionId == null) "agent> " else "agent[$activeSessionId]> ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.isBlank()) {
            continue
        }

        when {
            input == "/quit" -> return
            input == "/help" -> printHelp()
            input == "/tools" -> {
                engine.listTools().forEach { tool ->
                    val approval = if (tool.mutating) "approval required" else "auto-approved"
                    println("- ${tool.name}: ${tool.description} [$approval]")
                }
            }

            input.startsWith("/new") -> {
                val prompt = input.removePrefix("/new").trim()
                activeSessionId = if (prompt.isBlank()) {
                    println("Active session cleared. Enter a prompt to start a new session.")
                    null
                } else {
                    renderFlow(engine.startSession(prompt))
                }
            }

            input.startsWith("/resume ") -> {
                val sessionId = input.removePrefix("/resume ").trim()
                activeSessionId = sessionId
                renderFlow(engine.resumeSession(sessionId))
            }

            input == "/status" -> {
                val snapshot = activeSessionId?.let { engine.getSessionSnapshot(it) }
                if (snapshot == null) {
                    val recent = engine.listSessions(limit = 5)
                    if (recent.isEmpty()) {
                        println("No sessions found.")
                    } else {
                        recent.forEach { session ->
                            println("${session.id} ${session.status} updated=${session.updatedAt}")
                        }
                    }
                } else {
                    println("session=${snapshot.id} status=${snapshot.status} messages=${snapshot.items.size}")
                    if (snapshot.pendingApprovals.isEmpty()) {
                        println("pending approvals: none")
                    } else {
                        snapshot.pendingApprovals
                            .filter { it.status == PendingApprovalStatus.PENDING || it.status == PendingApprovalStatus.APPROVED }
                            .forEach { approval ->
                                println("approval ${approval.id}: ${approval.toolName} ${approval.summary}")
                            }
                    }
                }
            }

            input.startsWith("/approve ") -> {
                val sessionId = requireActiveSession(activeSessionId)
                val approvalId = input.removePrefix("/approve ").trim()
                renderFlow(engine.reviewApproval(sessionId, approvalId, approved = true))
            }

            input.startsWith("/deny ") -> {
                val sessionId = requireActiveSession(activeSessionId)
                val approvalId = input.removePrefix("/deny ").trim()
                renderFlow(engine.reviewApproval(sessionId, approvalId, approved = false))
            }

            input == "/diff" -> {
                val sessionId = requireActiveSession(activeSessionId)
                val snapshot = engine.getSessionSnapshot(sessionId) ?: error("Unknown session $sessionId")
                val patches = snapshot.pendingApprovals
                    .filter { it.toolName == "apply_patch" && it.status == PendingApprovalStatus.PENDING }
                if (patches.isEmpty()) {
                    println("No pending patch approvals.")
                } else {
                    patches.forEach { approval ->
                        println("approval ${approval.id}:")
                        println(approval.arguments["patch"]?.toString()?.trim('"') ?: "<missing patch>")
                    }
                }
            }

            input.startsWith("/") -> println("Unknown command. Use /help.")
            activeSessionId == null -> {
                activeSessionId = renderFlow(engine.startSession(input))
            }

            else -> {
                renderFlow(engine.sendUserMessage(requireActiveSession(activeSessionId), input))
            }
        }
    }
}

private suspend fun renderFlow(flow: Flow<AgentEvent>): String? {
    var currentSessionId: String? = null
    var streamedText = false
    flow.collect { event ->
        when (event) {
            is AgentEvent.SessionStarted -> {
                currentSessionId = event.sessionId
                println("session started: ${event.sessionId}")
            }

            is AgentEvent.SessionResumed -> {
                currentSessionId = event.sessionId
                println("session resumed: ${event.sessionId}")
            }

            is AgentEvent.AssistantTextDelta -> {
                if (!streamedText) {
                    print("assistant> ")
                    streamedText = true
                }
                print(event.delta)
            }

            is AgentEvent.AssistantTurnCompleted -> {
                currentSessionId = event.sessionId
                if (streamedText) {
                    println()
                    streamedText = false
                } else if (event.content.isNotBlank()) {
                    println("assistant> ${event.content}")
                }
                if (event.toolCalls.isNotEmpty()) {
                    event.toolCalls.forEach { call ->
                        println("tool requested: ${call.name} id=${call.id}")
                    }
                }
            }

            is AgentEvent.ToolExecutionStarted -> println("tool starting: ${event.toolCall.name} id=${event.toolCall.id}")
            is AgentEvent.ToolExecutionCompleted -> println("tool completed: ${event.toolName} id=${event.toolCallId}\n${event.result.content}")
            is AgentEvent.ApprovalRequired -> {
                currentSessionId = event.sessionId
                println("approval required: ${event.approval.id} ${event.approval.toolName} ${event.approval.summary}")
            }

            is AgentEvent.ApprovalResolved -> println("approval ${event.approvalId} resolved approved=${event.approved}")
            is AgentEvent.StatusChanged -> println("status=${event.status}")
            is AgentEvent.Info -> println(event.message)
            is AgentEvent.Error -> println("error: ${event.message}")
        }
    }
    if (streamedText) {
        println()
    }
    return currentSessionId
}

private fun requireActiveSession(activeSessionId: String?): String {
    return activeSessionId ?: error("No active session. Start one with a prompt or /new <prompt>.")
}

private fun printHelp() {
    println(
        """
        Commands:
          /new [prompt]    Start a new session, or clear the current session if no prompt is given.
          /resume <id>     Resume an existing session.
          /status          Show recent sessions or the current session state.
          /tools           List available tools.
          /approve <id>    Approve a pending action in the active session.
          /deny <id>       Deny a pending action in the active session.
          /diff            Show pending patch diffs for the active session.
          /help            Show this help text.
          /quit            Exit the REPL.
        """.trimIndent(),
    )
}

private data class CliConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    companion object {
        fun load(workspaceRoot: Path): CliConfig {
            val properties = Properties()
            val configFile = workspaceRoot.resolve(".crackedcode-agent").resolve("config.properties")
            if (Files.exists(configFile)) {
                Files.newBufferedReader(configFile).use { reader ->
                    properties.load(reader)
                }
            }
            val environment = System.getenv()
            return CliConfig(
                baseUrl = environment["OPENAI_BASE_URL"]
                    ?: properties.getProperty("baseUrl")
                    ?: "https://api.openai.com/v1",
                model = environment["CRACKEDCODE_MODEL"]
                    ?: properties.getProperty("model")
                    ?: "gpt-4.1-mini",
                apiKey = environment["OPENAI_API_KEY"]
                    ?: properties.getProperty("apiKey")
                    ?: "",
            )
        }
    }
}
