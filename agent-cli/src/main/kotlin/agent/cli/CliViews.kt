package agent.cli

import agent.core.AgentEngine
import agent.core.PendingApprovalStatus

fun printUsage(io: TerminalIO) {
    io.println(
        """
        Usage:
          agent [--workspace PATH]
          agent resume <session-id> [--workspace PATH]
          agent status [session-id] [--workspace PATH]
          agent tools [--workspace PATH]
          agent version
          agent help
        """.trimIndent(),
    )
}

fun printReplHelp(io: TerminalIO) {
    io.println(
        """
        REPL commands:
          /new [prompt]    Start a new session, or clear the active session if no prompt is given.
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

fun printTools(io: TerminalIO, engine: AgentEngine) {
    engine.listTools().forEach { tool ->
        val approval = if (tool.mutating) "approval required" else "auto-approved"
        io.println("- ${tool.name}: ${tool.description} [$approval]")
    }
}

fun printVersion(io: TerminalIO) {
    io.println("agent ${VersionInfo.current}")
}

suspend fun printStatus(io: TerminalIO, engine: AgentEngine, sessionId: String?): Int {
    if (sessionId == null) {
        val recent = engine.listSessions(limit = 5)
        if (recent.isEmpty()) {
            io.println("No sessions found.")
        } else {
            recent.forEach { session ->
                io.println("${session.id} ${session.status} updated=${session.updatedAt}")
            }
        }
        return 0
    }

    val snapshot = engine.getSessionSnapshot(sessionId)
    if (snapshot == null) {
        io.println("No session found: $sessionId")
        return 1
    }

    io.println("session=${snapshot.id} status=${snapshot.status} messages=${snapshot.items.size}")
    val pendingApprovals = snapshot.pendingApprovals
        .filter { it.status == PendingApprovalStatus.PENDING || it.status == PendingApprovalStatus.APPROVED }
    if (pendingApprovals.isEmpty()) {
        io.println("pending approvals: none")
    } else {
        pendingApprovals.forEach { approval ->
            io.println("approval ${approval.id}: ${approval.toolName} ${approval.summary}")
        }
    }
    return 0
}
