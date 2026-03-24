package com.crackedcode.agent.core

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString

interface AgentEngine {
    fun startSession(prompt: String): Flow<AgentEvent>

    fun sendUserMessage(sessionId: String, prompt: String): Flow<AgentEvent>

    fun resumeSession(sessionId: String): Flow<AgentEvent>

    fun reviewApproval(sessionId: String, approvalId: String, approved: Boolean): Flow<AgentEvent>

    suspend fun getSessionSnapshot(sessionId: String): SessionSnapshot?

    suspend fun listSessions(limit: Int = 20): List<SessionSnapshot>

    fun listTools(): List<ToolSpec>
}

class DefaultAgentEngine(
    private val config: AgentConfig,
    private val provider: ModelProvider,
    private val toolRegistry: ToolRegistry,
    private val sessionStore: SessionStore,
    private val approvalPolicy: ApprovalPolicy = DefaultApprovalPolicy(),
) : AgentEngine {
    override fun startSession(prompt: String): Flow<AgentEvent> = flow {
        val snapshot = sessionStore.createSession(config)
        emit(AgentEvent.SessionStarted(snapshot.id))
        sessionStore.appendConversationItem(snapshot.id, UserMessage(prompt))
        emitAll(runLoop(snapshot.id))
    }

    override fun sendUserMessage(sessionId: String, prompt: String): Flow<AgentEvent> = flow {
        requireSession(sessionId)
        sessionStore.appendConversationItem(sessionId, UserMessage(prompt))
        emitAll(runLoop(sessionId))
    }

    override fun resumeSession(sessionId: String): Flow<AgentEvent> = flow {
        requireSession(sessionId)
        emit(AgentEvent.SessionResumed(sessionId))
        emitAll(runLoop(sessionId))
    }

    override fun reviewApproval(sessionId: String, approvalId: String, approved: Boolean): Flow<AgentEvent> = flow {
        val approval = sessionStore.getPendingApproval(sessionId, approvalId)
            ?: error("Unknown approval $approvalId for session $sessionId")
        check(approval.status == PendingApprovalStatus.PENDING) {
            "Approval $approvalId for session $sessionId is already ${approval.status} and cannot be reviewed again"
        }
        val nextStatus = if (approved) PendingApprovalStatus.APPROVED else PendingApprovalStatus.DENIED
        sessionStore.updatePendingApproval(sessionId, approvalId, nextStatus)
        emit(AgentEvent.ApprovalResolved(sessionId, approvalId, approved))
        if (approved) {
            executeApprovedTool(sessionId, approval, approvalId)?.let { emit(it) }
            sessionStore.updatePendingApproval(sessionId, approvalId, PendingApprovalStatus.EXECUTED)
        } else {
            val denialMessage = ToolResultMessage(
                toolCallId = approval.toolCallId,
                toolName = approval.toolName,
                content = "Execution denied by the user.",
                isError = true,
            )
            sessionStore.appendConversationItem(sessionId, denialMessage)
            sessionStore.updateToolInvocation(
                sessionId = sessionId,
                toolCallId = approval.toolCallId,
                status = ToolInvocationStatus.DENIED,
                summary = "Denied by user",
                outputJson = AgentJson.encodeToString(ToolResult(content = denialMessage.content, isError = true)),
            )
        }

        val remaining = sessionStore.listPendingApprovals(sessionId).filter { it.status == PendingApprovalStatus.PENDING }
        if (remaining.isNotEmpty()) {
            sessionStore.updateSessionStatus(sessionId, SessionStatus.WAITING_APPROVAL)
            emit(AgentEvent.StatusChanged(sessionId, SessionStatus.WAITING_APPROVAL))
            remaining.forEach { emit(AgentEvent.ApprovalRequired(sessionId, it)) }
        } else {
            emitAll(runLoop(sessionId))
        }
    }

    override suspend fun getSessionSnapshot(sessionId: String): SessionSnapshot? = sessionStore.loadSession(sessionId)

    override suspend fun listSessions(limit: Int): List<SessionSnapshot> = sessionStore.listSessions(limit)

    override fun listTools(): List<ToolSpec> = toolRegistry.list()

    private fun runLoop(sessionId: String): Flow<AgentEvent> = flow {
        while (true) {
            val snapshot = requireSession(sessionId)
            val approved = snapshot.pendingApprovals.filter { it.status == PendingApprovalStatus.APPROVED }
            if (approved.isNotEmpty()) {
                sessionStore.updateSessionStatus(sessionId, SessionStatus.RUNNING)
                emit(AgentEvent.StatusChanged(sessionId, SessionStatus.RUNNING))
                for (approval in approved) {
                    val toolCall = ToolCallRequest(
                        id = approval.toolCallId,
                        name = approval.toolName,
                        arguments = approval.arguments,
                    )
                    emit(AgentEvent.ToolExecutionStarted(sessionId, toolCall))
                    executeStoredApproval(sessionId, approval)?.let { emit(it) }
                    sessionStore.updatePendingApproval(sessionId, approval.id, PendingApprovalStatus.EXECUTED)
                }
                continue
            }

            val pending = snapshot.pendingApprovals.filter { it.status == PendingApprovalStatus.PENDING }
            if (pending.isNotEmpty()) {
                sessionStore.updateSessionStatus(sessionId, SessionStatus.WAITING_APPROVAL)
                emit(AgentEvent.StatusChanged(sessionId, SessionStatus.WAITING_APPROVAL))
                pending.forEach { emit(AgentEvent.ApprovalRequired(sessionId, it)) }
                break
            }

            sessionStore.updateSessionStatus(sessionId, SessionStatus.RUNNING)
            emit(AgentEvent.StatusChanged(sessionId, SessionStatus.RUNNING))

            val freshSnapshot = requireSession(sessionId)
            val context = SessionContext(
                sessionId = sessionId,
                workspaceRoot = config.workspaceRoot,
                storageRoot = config.storageRoot,
                artifactRoot = config.sessionArtifactRoot(sessionId),
                systemPrompt = config.systemPrompt,
                shellTimeoutMillis = config.shellTimeoutMillis,
                maxToolOutputChars = config.maxToolOutputChars,
            )
            Files.createDirectories(context.artifactRoot)

            val textBuffer = StringBuilder()
            val toolCalls = mutableListOf<ToolCallRequest>()
            try {
                provider.streamTurn(context, freshSnapshot.items, toolRegistry.list()).collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            textBuffer.append(event.delta)
                            emit(AgentEvent.AssistantTextDelta(sessionId, event.delta))
                        }

                        is ProviderEvent.ToolCallsPrepared -> {
                            toolCalls += event.toolCalls
                        }

                        is ProviderEvent.Completed -> {
                            // The engine already knows whether work should continue based on tool calls.
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                sessionStore.updateSessionStatus(sessionId, SessionStatus.FAILED)
                sessionStore.saveCheckpoint(sessionId, "provider turn failed")
                emit(AgentEvent.StatusChanged(sessionId, SessionStatus.FAILED))
                emit(AgentEvent.Error(sessionId, error.message ?: "Provider turn failed."))
                break
            }

            if (textBuffer.isNotBlank() || toolCalls.isNotEmpty()) {
                sessionStore.appendConversationItem(
                    sessionId,
                    AssistantMessage(content = textBuffer.toString(), toolCalls = toolCalls.toList()),
                )
                emit(AgentEvent.AssistantTurnCompleted(sessionId, textBuffer.toString(), toolCalls.toList()))
            }

            if (toolCalls.isEmpty()) {
                sessionStore.updateSessionStatus(sessionId, SessionStatus.IDLE)
                sessionStore.saveCheckpoint(sessionId, "assistant turn completed")
                emit(AgentEvent.StatusChanged(sessionId, SessionStatus.IDLE))
                break
            }

            var shouldContinue = false
            for (toolCall in toolCalls) {
                val tool = toolRegistry.require(toolCall.name)
                sessionStore.recordToolInvocation(
                    sessionId = sessionId,
                    toolCall = toolCall,
                    status = ToolInvocationStatus.REQUESTED,
                    summary = "Tool requested by model",
                )
                when (approvalPolicy.decide(ToolApprovalRequest(sessionId, toolCall, tool.spec))) {
                    ApprovalDecision.AUTO_APPROVED -> {
                        emit(AgentEvent.ToolExecutionStarted(sessionId, toolCall))
                        executeToolAndPersist(sessionId, toolCall, tool)?.let { emit(it) }
                        shouldContinue = true
                    }

                    ApprovalDecision.REQUIRES_USER_APPROVAL -> {
                        sessionStore.updateToolInvocation(
                            sessionId = sessionId,
                            toolCallId = toolCall.id,
                            status = ToolInvocationStatus.APPROVAL_REQUIRED,
                            summary = "Waiting for user approval",
                        )
                        val approval = sessionStore.createPendingApproval(
                            sessionId = sessionId,
                            toolCall = toolCall,
                            summary = "Approve ${toolCall.name}",
                        )
                        sessionStore.updateSessionStatus(sessionId, SessionStatus.WAITING_APPROVAL)
                        emit(AgentEvent.ApprovalRequired(sessionId, approval))
                    }

                    ApprovalDecision.DENIED -> {
                        val result = ToolResult(content = "Execution denied by policy.", isError = true)
                        sessionStore.updateToolInvocation(
                            sessionId = sessionId,
                            toolCallId = toolCall.id,
                            status = ToolInvocationStatus.DENIED,
                            summary = result.content,
                            outputJson = AgentJson.encodeToString(result),
                        )
                        sessionStore.appendConversationItem(
                            sessionId,
                            ToolResultMessage(
                                toolCallId = toolCall.id,
                                toolName = toolCall.name,
                                content = result.content,
                                isError = true,
                            ),
                        )
                        emit(AgentEvent.ToolExecutionCompleted(sessionId, toolCall.id, toolCall.name, result))
                        shouldContinue = true
                    }
                }
            }

            sessionStore.saveCheckpoint(sessionId, "tool cycle completed")
            val status = requireSession(sessionId).status
            if (status == SessionStatus.WAITING_APPROVAL) {
                emit(AgentEvent.StatusChanged(sessionId, SessionStatus.WAITING_APPROVAL))
                break
            }
            if (!shouldContinue) {
                sessionStore.updateSessionStatus(sessionId, SessionStatus.IDLE)
                emit(AgentEvent.StatusChanged(sessionId, SessionStatus.IDLE))
                break
            }
        }
    }

    private suspend fun executeApprovedTool(
        sessionId: String,
        approval: PendingApproval,
        approvalId: String,
    ): AgentEvent.ToolExecutionCompleted? {
        sessionStore.updateToolInvocation(
            sessionId = sessionId,
            toolCallId = approval.toolCallId,
            status = ToolInvocationStatus.APPROVED,
            summary = "Approved by user via $approvalId",
        )
        return executeStoredApproval(sessionId, approval)
    }

    private suspend fun executeStoredApproval(
        sessionId: String,
        approval: PendingApproval,
    ): AgentEvent.ToolExecutionCompleted? {
        val toolCall = ToolCallRequest(
            id = approval.toolCallId,
            name = approval.toolName,
            arguments = approval.arguments,
        )
        val tool = toolRegistry.require(toolCall.name)
        return executeToolAndPersist(sessionId, toolCall, tool)
    }

    private suspend fun executeToolAndPersist(
        sessionId: String,
        toolCall: ToolCallRequest,
        tool: Tool,
    ): AgentEvent.ToolExecutionCompleted? {
        val executionContext = ToolExecutionContext(
            sessionId = sessionId,
            workspaceRoot = config.workspaceRoot,
            artifactRoot = config.sessionArtifactRoot(sessionId),
            shellTimeoutMillis = config.shellTimeoutMillis,
            maxOutputChars = config.maxToolOutputChars,
            protectedWorkspacePaths = config.protectedWorkspacePaths(),
        )
        return try {
            sessionStore.updateToolInvocation(
                sessionId = sessionId,
                toolCallId = toolCall.id,
                status = ToolInvocationStatus.RUNNING,
            )
            val result = tool.execute(toolCall.arguments, executionContext)
            sessionStore.updateToolInvocation(
                sessionId = sessionId,
                toolCallId = toolCall.id,
                status = if (result.isError) ToolInvocationStatus.FAILED else ToolInvocationStatus.COMPLETED,
                summary = result.content.take(400),
                outputJson = AgentJson.encodeToString(result),
            )
            sessionStore.appendConversationItem(
                sessionId,
                ToolResultMessage(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    content = result.content,
                    isError = result.isError,
                ),
            )
            AgentEvent.ToolExecutionCompleted(sessionId, toolCall.id, toolCall.name, result)
        } catch (error: Throwable) {
            val result = ToolResult(content = error.message ?: "Tool execution failed.", isError = true)
            sessionStore.updateToolInvocation(
                sessionId = sessionId,
                toolCallId = toolCall.id,
                status = ToolInvocationStatus.FAILED,
                summary = result.content,
                outputJson = AgentJson.encodeToString(result),
            )
            sessionStore.appendConversationItem(
                sessionId,
                ToolResultMessage(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    content = result.content,
                    isError = true,
                ),
            )
            AgentEvent.ToolExecutionCompleted(sessionId, toolCall.id, toolCall.name, result)
        }
    }

    private suspend fun requireSession(sessionId: String): SessionSnapshot {
        return sessionStore.loadSession(sessionId) ?: error("Unknown session $sessionId")
    }
}
