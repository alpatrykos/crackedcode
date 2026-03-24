package com.crackedcode.agent.core

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

data class AgentConfig(
    val workspaceRoot: Path,
    val storageRoot: Path = workspaceRoot.resolve(".crackedcode-agent"),
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val shellTimeoutMillis: Long = 30_000,
    val maxToolOutputChars: Int = 24_000,
) {
    fun sessionArtifactRoot(sessionId: String): Path = storageRoot.resolve("sessions").resolve(sessionId)

    fun protectedWorkspacePaths(): Set<Path> {
        val normalizedWorkspaceRoot = workspaceRoot.normalize()
        val protectedPaths = mutableSetOf(workspaceRoot.resolve(".crackedcode-agent").normalize())
        val normalizedStorageRoot = storageRoot.normalize()
        if (normalizedStorageRoot != normalizedWorkspaceRoot && normalizedStorageRoot.startsWith(normalizedWorkspaceRoot)) {
            protectedPaths.add(normalizedStorageRoot)
        }
        return protectedPaths
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT: String =
            "You are a Kotlin coding agent. Prefer safe local analysis, describe tradeoffs clearly, and use tools only when needed."
    }
}

data class SessionContext(
    val sessionId: String,
    val workspaceRoot: Path,
    val storageRoot: Path,
    val artifactRoot: Path,
    val systemPrompt: String,
    val shellTimeoutMillis: Long,
    val maxToolOutputChars: Int,
)

@Serializable
sealed interface ConversationItem {
    val timestamp: String
}

@Serializable
@SerialName("system")
data class SystemMessage(
    val content: String,
    override val timestamp: String = Instant.now().toString(),
) : ConversationItem

@Serializable
@SerialName("user")
data class UserMessage(
    val content: String,
    override val timestamp: String = Instant.now().toString(),
) : ConversationItem

@Serializable
@SerialName("assistant")
data class AssistantMessage(
    val content: String = "",
    val toolCalls: List<ToolCallRequest> = emptyList(),
    override val timestamp: String = Instant.now().toString(),
) : ConversationItem

@Serializable
@SerialName("tool")
data class ToolResultMessage(
    val toolCallId: String,
    val toolName: String,
    val content: String,
    val isError: Boolean = false,
    override val timestamp: String = Instant.now().toString(),
) : ConversationItem

@Serializable
data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val mutating: Boolean = false,
)

@Serializable
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

data class ToolApprovalRequest(
    val sessionId: String,
    val toolCall: ToolCallRequest,
    val toolSpec: ToolSpec,
)

enum class ApprovalDecision {
    AUTO_APPROVED,
    REQUIRES_USER_APPROVAL,
    DENIED,
}

fun interface ApprovalPolicy {
    fun decide(request: ToolApprovalRequest): ApprovalDecision
}

class DefaultApprovalPolicy(
    private val guardedToolNames: Set<String> = setOf("run_shell", "apply_patch"),
) : ApprovalPolicy {
    override fun decide(request: ToolApprovalRequest): ApprovalDecision {
        return if (request.toolSpec.name in guardedToolNames || request.toolSpec.mutating) {
            ApprovalDecision.REQUIRES_USER_APPROVAL
        } else {
            ApprovalDecision.AUTO_APPROVED
        }
    }
}

enum class SessionStatus {
    IDLE,
    RUNNING,
    WAITING_APPROVAL,
    FAILED,
}

enum class ToolInvocationStatus {
    REQUESTED,
    APPROVAL_REQUIRED,
    APPROVED,
    DENIED,
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class PendingApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXECUTED,
}

data class ToolInvocationRecord(
    val id: String,
    val sessionId: String,
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
    val status: ToolInvocationStatus,
    val summary: String?,
    val outputJson: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PendingApproval(
    val id: String,
    val sessionId: String,
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
    val summary: String,
    val status: PendingApprovalStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SessionSnapshot(
    val id: String,
    val workspaceRoot: Path,
    val status: SessionStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val items: List<ConversationItem>,
    val toolInvocations: List<ToolInvocationRecord>,
    val pendingApprovals: List<PendingApproval>,
)

sealed interface ProviderEvent {
    data class TextDelta(val delta: String) : ProviderEvent

    data class ToolCallsPrepared(val toolCalls: List<ToolCallRequest>) : ProviderEvent

    data class Completed(val finishReason: String?) : ProviderEvent
}

interface ModelProvider {
    fun streamTurn(
        context: SessionContext,
        conversation: List<ConversationItem>,
        tools: List<ToolSpec>,
    ): kotlinx.coroutines.flow.Flow<ProviderEvent>
}

sealed interface AgentEvent {
    data class SessionStarted(val sessionId: String) : AgentEvent

    data class SessionResumed(val sessionId: String) : AgentEvent

    data class AssistantTextDelta(val sessionId: String, val delta: String) : AgentEvent

    data class AssistantTurnCompleted(val sessionId: String, val content: String, val toolCalls: List<ToolCallRequest>) : AgentEvent

    data class ToolExecutionStarted(val sessionId: String, val toolCall: ToolCallRequest) : AgentEvent

    data class ToolExecutionCompleted(val sessionId: String, val toolCallId: String, val toolName: String, val result: ToolResult) : AgentEvent

    data class ApprovalRequired(val sessionId: String, val approval: PendingApproval) : AgentEvent

    data class ApprovalResolved(val sessionId: String, val approvalId: String, val approved: Boolean) : AgentEvent

    data class StatusChanged(val sessionId: String, val status: SessionStatus) : AgentEvent

    data class Info(val sessionId: String, val message: String) : AgentEvent

    data class Error(val sessionId: String, val message: String) : AgentEvent
}
