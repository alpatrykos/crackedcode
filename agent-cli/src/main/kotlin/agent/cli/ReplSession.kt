package agent.cli

import agent.core.AgentEngine
import agent.core.AgentEvent
import agent.core.PendingApprovalStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class ReplSession(
    private val io: TerminalIO,
    private val engine: AgentEngine,
) {
    suspend fun run(resumeSessionId: String?): Int {
        var activeSessionId: String? = null
        printReplHelp(io)
        if (resumeSessionId != null) {
            activeSessionId = renderFlow(engine.resumeSession(resumeSessionId))
        }

        while (true) {
            io.print(if (activeSessionId == null) "agent> " else "agent[$activeSessionId]> ")
            val input = io.readLine()?.trim() ?: run {
                io.println()
                return 0
            }
            if (input.isBlank()) {
                continue
            }

            when {
                input == "/quit" -> return 0
                input == "/help" -> printReplHelp(io)
                input == "/tools" -> printTools(io, engine)
                input.startsWith("/new") -> {
                    val prompt = input.removePrefix("/new").trim()
                    activeSessionId = if (prompt.isBlank()) {
                        io.println("Active session cleared. Enter a prompt to start a new session.")
                        null
                    } else {
                        renderFlow(engine.startSession(prompt))
                    }
                }

                input.startsWith("/resume ") -> {
                    val sessionId = input.removePrefix("/resume ").trim()
                    activeSessionId = renderFlow(engine.resumeSession(sessionId)) ?: activeSessionId
                }

                input == "/status" -> {
                    val statusCode = printStatus(io, engine, activeSessionId)
                    if (statusCode != 0) {
                        activeSessionId = null
                    }
                }

                input.startsWith("/approve ") -> {
                    val sessionId = activeSessionId
                    if (sessionId == null) {
                        io.println("No active session. Start one with a prompt or /new <prompt>.")
                    } else {
                        val approvalId = input.removePrefix("/approve ").trim()
                        renderFlow(engine.reviewApproval(sessionId, approvalId, approved = true))
                    }
                }

                input.startsWith("/deny ") -> {
                    val sessionId = activeSessionId
                    if (sessionId == null) {
                        io.println("No active session. Start one with a prompt or /new <prompt>.")
                    } else {
                        val approvalId = input.removePrefix("/deny ").trim()
                        renderFlow(engine.reviewApproval(sessionId, approvalId, approved = false))
                    }
                }

                input == "/diff" -> {
                    val sessionId = activeSessionId
                    if (sessionId == null) {
                        io.println("No active session. Start one with a prompt or /new <prompt>.")
                    } else {
                        printPendingDiffs(sessionId)
                    }
                }

                input.startsWith("/") -> io.println("Unknown command. Use /help.")
                activeSessionId == null -> activeSessionId = renderFlow(engine.startSession(input))
                else -> renderFlow(engine.sendUserMessage(activeSessionId, input))
            }
        }
    }

    private suspend fun renderFlow(flow: Flow<AgentEvent>): String? {
        var currentSessionId: String? = null
        var streamedText = false
        try {
            flow.collect { event ->
                when (event) {
                    is AgentEvent.SessionStarted -> {
                        currentSessionId = event.sessionId
                        io.println("session started: ${event.sessionId}")
                    }

                    is AgentEvent.SessionResumed -> {
                        currentSessionId = event.sessionId
                        io.println("session resumed: ${event.sessionId}")
                    }

                    is AgentEvent.AssistantTextDelta -> {
                        if (!streamedText) {
                            io.print("assistant> ")
                            streamedText = true
                        }
                        io.print(event.delta)
                    }

                    is AgentEvent.AssistantTurnCompleted -> {
                        currentSessionId = event.sessionId
                        if (streamedText) {
                            io.println()
                            streamedText = false
                        } else if (event.content.isNotBlank()) {
                            io.println("assistant> ${event.content}")
                        }
                        event.toolCalls.forEach { call ->
                            io.println("tool requested: ${call.name} id=${call.id}")
                        }
                    }

                    is AgentEvent.ToolExecutionStarted -> io.println("tool starting: ${event.toolCall.name} id=${event.toolCall.id}")
                    is AgentEvent.ToolExecutionCompleted -> io.println("tool completed: ${event.toolName} id=${event.toolCallId}\n${event.result.content}")
                    is AgentEvent.ApprovalRequired -> {
                        currentSessionId = event.sessionId
                        io.println("approval required: ${event.approval.id} ${event.approval.toolName} ${event.approval.summary}")
                    }

                    is AgentEvent.ApprovalResolved -> io.println("approval ${event.approvalId} resolved approved=${event.approved}")
                    is AgentEvent.StatusChanged -> io.println("status=${event.status}")
                    is AgentEvent.Info -> io.println(event.message)
                    is AgentEvent.Error -> io.println("error: ${event.message}")
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            if (streamedText) {
                io.println()
                streamedText = false
            }
            io.println("error: ${error.message ?: "Unexpected error"}")
        }
        if (streamedText) {
            io.println()
        }
        return currentSessionId
    }

    private suspend fun printPendingDiffs(sessionId: String) {
        val snapshot = engine.getSessionSnapshot(sessionId)
        if (snapshot == null) {
            io.println("No session found: $sessionId")
            return
        }
        val patches = snapshot.pendingApprovals
            .filter { it.toolName == "apply_patch" && it.status == PendingApprovalStatus.PENDING }
        if (patches.isEmpty()) {
            io.println("No pending patch approvals.")
            return
        }
        patches.forEach { approval ->
            io.println("approval ${approval.id}:")
            io.println(approval.arguments["patch"]?.jsonPrimitive?.contentOrNull ?: "<missing patch>")
        }
    }
}
