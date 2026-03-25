package agent.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SessionStore {
    suspend fun createSession(config: AgentConfig): SessionSnapshot

    suspend fun appendConversationItem(sessionId: String, item: ConversationItem)

    suspend fun loadSession(sessionId: String): SessionSnapshot?

    suspend fun listSessions(limit: Int = 20): List<SessionSnapshot>

    suspend fun updateSessionStatus(sessionId: String, status: SessionStatus)

    suspend fun recordToolInvocation(
        sessionId: String,
        toolCall: ToolCallRequest,
        status: ToolInvocationStatus,
        summary: String? = null,
        outputJson: String? = null,
    ): ToolInvocationRecord

    suspend fun updateToolInvocation(
        sessionId: String,
        toolCallId: String,
        status: ToolInvocationStatus,
        summary: String? = null,
        outputJson: String? = null,
    )

    suspend fun createPendingApproval(
        sessionId: String,
        toolCall: ToolCallRequest,
        summary: String,
    ): PendingApproval

    suspend fun getPendingApproval(sessionId: String, approvalId: String): PendingApproval?

    suspend fun listPendingApprovals(sessionId: String): List<PendingApproval>

    suspend fun updatePendingApproval(
        sessionId: String,
        approvalId: String,
        status: PendingApprovalStatus,
    ): PendingApproval

    suspend fun saveCheckpoint(sessionId: String, summary: String)
}

class SqliteSessionStore(
    private val databasePath: java.nio.file.Path,
) : SessionStore {
    init {
        Files.createDirectories(databasePath.parent)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    create table if not exists sessions (
                        id text primary key,
                        workspace_root text not null,
                        status text not null,
                        created_at text not null,
                        updated_at text not null
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    create table if not exists conversation_items (
                        id integer primary key autoincrement,
                        session_id text not null,
                        item_json text not null,
                        created_at text not null
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    create table if not exists tool_invocations (
                        id text primary key,
                        session_id text not null,
                        tool_call_id text not null,
                        tool_name text not null,
                        arguments_json text not null,
                        status text not null,
                        summary text,
                        output_json text,
                        created_at text not null,
                        updated_at text not null
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    create table if not exists pending_approvals (
                        id text primary key,
                        session_id text not null,
                        tool_call_id text not null,
                        tool_name text not null,
                        arguments_json text not null,
                        summary text not null,
                        status text not null,
                        created_at text not null,
                        updated_at text not null
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    create table if not exists checkpoints (
                        id text primary key,
                        session_id text not null,
                        summary text not null,
                        snapshot_json text not null,
                        created_at text not null
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    override suspend fun createSession(config: AgentConfig): SessionSnapshot = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val sessionId = UUID.randomUUID().toString()
        Files.createDirectories(config.storageRoot)
        Files.createDirectories(config.sessionArtifactRoot(sessionId))
        connection().use { connection ->
            connection.prepareStatement(
                "insert into sessions(id, workspace_root, status, created_at, updated_at) values (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, config.workspaceRoot.toString())
                statement.setString(3, SessionStatus.IDLE.name)
                statement.setString(4, now.toString())
                statement.setString(5, now.toString())
                statement.executeUpdate()
            }
        }
        appendConversationItem(sessionId, SystemMessage(config.systemPrompt))
        loadSession(sessionId) ?: error("Failed to create session $sessionId")
    }

    override suspend fun appendConversationItem(sessionId: String, item: ConversationItem) {
        withContext(Dispatchers.IO) {
            val now = Instant.now().toString()
            connection().use { connection ->
                connection.prepareStatement(
                    "insert into conversation_items(session_id, item_json, created_at) values (?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, AgentJson.encodeToString(ConversationItem.serializer(), item))
                    statement.setString(3, now)
                    statement.executeUpdate()
                }
                touchSession(connection, sessionId)
            }
        }
    }

    override suspend fun loadSession(sessionId: String): SessionSnapshot? = withContext(Dispatchers.IO) {
        connection().use { connection ->
            connection.prepareStatement(
                "select workspace_root, status, created_at, updated_at from sessions where id = ?",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        return@withContext null
                    }
                    val workspaceRoot = java.nio.file.Path.of(rows.getString("workspace_root"))
                    val status = SessionStatus.valueOf(rows.getString("status"))
                    val createdAt = Instant.parse(rows.getString("created_at"))
                    val updatedAt = Instant.parse(rows.getString("updated_at"))
                    val items = readConversationItems(connection, sessionId)
                    val invocations = readToolInvocations(connection, sessionId)
                    val approvals = readPendingApprovals(connection, sessionId)
                    SessionSnapshot(
                        id = sessionId,
                        workspaceRoot = workspaceRoot,
                        status = status,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        items = items,
                        toolInvocations = invocations,
                        pendingApprovals = approvals,
                    )
                }
            }
        }
    }

    override suspend fun listSessions(limit: Int): List<SessionSnapshot> = withContext(Dispatchers.IO) {
        val ids = mutableListOf<String>()
        connection().use { connection ->
            connection.prepareStatement(
                "select id from sessions order by updated_at desc limit ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        ids += rows.getString("id")
                    }
                }
            }
        }
        ids.mapNotNull { loadSession(it) }
    }

    override suspend fun updateSessionStatus(sessionId: String, status: SessionStatus) {
        withContext(Dispatchers.IO) {
            connection().use { connection ->
                connection.prepareStatement(
                    "update sessions set status = ?, updated_at = ? where id = ?",
                ).use { statement ->
                    statement.setString(1, status.name)
                    statement.setString(2, Instant.now().toString())
                    statement.setString(3, sessionId)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun recordToolInvocation(
        sessionId: String,
        toolCall: ToolCallRequest,
        status: ToolInvocationStatus,
        summary: String?,
        outputJson: String?,
    ): ToolInvocationRecord = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val recordId = UUID.randomUUID().toString()
        connection().use { connection ->
            connection.prepareStatement(
                """
                insert into tool_invocations(
                    id, session_id, tool_call_id, tool_name, arguments_json, status, summary, output_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, recordId)
                statement.setString(2, sessionId)
                statement.setString(3, toolCall.id)
                statement.setString(4, toolCall.name)
                statement.setString(5, AgentJson.encodeToString(JsonObject.serializer(), toolCall.arguments))
                statement.setString(6, status.name)
                statement.setString(7, summary)
                statement.setString(8, outputJson)
                statement.setString(9, now.toString())
                statement.setString(10, now.toString())
                statement.executeUpdate()
            }
            touchSession(connection, sessionId)
        }
        ToolInvocationRecord(
            id = recordId,
            sessionId = sessionId,
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            arguments = toolCall.arguments,
            status = status,
            summary = summary,
            outputJson = outputJson,
            createdAt = now,
            updatedAt = now,
        )
    }

    override suspend fun updateToolInvocation(
        sessionId: String,
        toolCallId: String,
        status: ToolInvocationStatus,
        summary: String?,
        outputJson: String?,
    ) {
        withContext(Dispatchers.IO) {
            connection().use { connection ->
                connection.prepareStatement(
                    """
                    update tool_invocations
                    set status = ?, summary = coalesce(?, summary), output_json = coalesce(?, output_json), updated_at = ?
                    where session_id = ? and tool_call_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, status.name)
                    statement.setString(2, summary)
                    statement.setString(3, outputJson)
                    statement.setString(4, Instant.now().toString())
                    statement.setString(5, sessionId)
                    statement.setString(6, toolCallId)
                    statement.executeUpdate()
                }
                touchSession(connection, sessionId)
            }
        }
    }

    override suspend fun createPendingApproval(
        sessionId: String,
        toolCall: ToolCallRequest,
        summary: String,
    ): PendingApproval = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val approvalId = UUID.randomUUID().toString()
        connection().use { connection ->
            connection.prepareStatement(
                """
                insert into pending_approvals(
                    id, session_id, tool_call_id, tool_name, arguments_json, summary, status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, approvalId)
                statement.setString(2, sessionId)
                statement.setString(3, toolCall.id)
                statement.setString(4, toolCall.name)
                statement.setString(5, AgentJson.encodeToString(JsonObject.serializer(), toolCall.arguments))
                statement.setString(6, summary)
                statement.setString(7, PendingApprovalStatus.PENDING.name)
                statement.setString(8, now.toString())
                statement.setString(9, now.toString())
                statement.executeUpdate()
            }
            touchSession(connection, sessionId)
        }
        PendingApproval(
            id = approvalId,
            sessionId = sessionId,
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            arguments = toolCall.arguments,
            summary = summary,
            status = PendingApprovalStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
    }

    override suspend fun getPendingApproval(sessionId: String, approvalId: String): PendingApproval? = withContext(Dispatchers.IO) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                select tool_call_id, tool_name, arguments_json, summary, status, created_at, updated_at
                from pending_approvals
                where session_id = ? and id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, approvalId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        return@withContext null
                    }
                    mapApprovalRow(sessionId, approvalId, rows)
                }
            }
        }
    }

    override suspend fun listPendingApprovals(sessionId: String): List<PendingApproval> = withContext(Dispatchers.IO) {
        connection().use { connection ->
            readPendingApprovals(connection, sessionId)
        }
    }

    override suspend fun updatePendingApproval(
        sessionId: String,
        approvalId: String,
        status: PendingApprovalStatus,
    ): PendingApproval = withContext(Dispatchers.IO) {
        connection().use { connection ->
            connection.prepareStatement(
                "update pending_approvals set status = ?, updated_at = ? where session_id = ? and id = ?",
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, Instant.now().toString())
                statement.setString(3, sessionId)
                statement.setString(4, approvalId)
                statement.executeUpdate()
            }
            touchSession(connection, sessionId)
            getPendingApproval(sessionId, approvalId) ?: error("Missing approval $approvalId")
        }
    }

    override suspend fun saveCheckpoint(sessionId: String, summary: String) {
        withContext(Dispatchers.IO) {
            val snapshot = loadSession(sessionId) ?: return@withContext
            connection().use { connection ->
                connection.prepareStatement(
                    "insert into checkpoints(id, session_id, summary, snapshot_json, created_at) values (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, UUID.randomUUID().toString())
                    statement.setString(2, sessionId)
                    statement.setString(3, summary)
                    statement.setString(4, AgentJson.encodeToString(SessionSnapshotSurrogate.from(snapshot)))
                    statement.setString(5, Instant.now().toString())
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun readConversationItems(connection: Connection, sessionId: String): List<ConversationItem> {
        val items = mutableListOf<ConversationItem>()
        connection.prepareStatement(
            "select item_json from conversation_items where session_id = ? order by id asc",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    items += AgentJson.decodeFromString(ConversationItem.serializer(), rows.getString("item_json"))
                }
            }
        }
        return items
    }

    private fun readToolInvocations(connection: Connection, sessionId: String): List<ToolInvocationRecord> {
        val records = mutableListOf<ToolInvocationRecord>()
        connection.prepareStatement(
            """
            select id, tool_call_id, tool_name, arguments_json, status, summary, output_json, created_at, updated_at
            from tool_invocations
            where session_id = ?
            order by created_at asc
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    records += ToolInvocationRecord(
                        id = rows.getString("id"),
                        sessionId = sessionId,
                        toolCallId = rows.getString("tool_call_id"),
                        toolName = rows.getString("tool_name"),
                        arguments = AgentJson.decodeFromString(JsonObject.serializer(), rows.getString("arguments_json")),
                        status = ToolInvocationStatus.valueOf(rows.getString("status")),
                        summary = rows.getString("summary"),
                        outputJson = rows.getString("output_json"),
                        createdAt = Instant.parse(rows.getString("created_at")),
                        updatedAt = Instant.parse(rows.getString("updated_at")),
                    )
                }
            }
        }
        return records
    }

    private fun readPendingApprovals(connection: Connection, sessionId: String): List<PendingApproval> {
        val approvals = mutableListOf<PendingApproval>()
        connection.prepareStatement(
            """
            select id, tool_call_id, tool_name, arguments_json, summary, status, created_at, updated_at
            from pending_approvals
            where session_id = ? and status in ('PENDING', 'APPROVED')
            order by created_at asc
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    approvals += mapApprovalRow(sessionId, rows.getString("id"), rows)
                }
            }
        }
        return approvals
    }

    private fun mapApprovalRow(sessionId: String, approvalId: String, rows: java.sql.ResultSet): PendingApproval {
        return PendingApproval(
            id = approvalId,
            sessionId = sessionId,
            toolCallId = rows.getString("tool_call_id"),
            toolName = rows.getString("tool_name"),
            arguments = AgentJson.decodeFromString(JsonObject.serializer(), rows.getString("arguments_json")),
            summary = rows.getString("summary"),
            status = PendingApprovalStatus.valueOf(rows.getString("status")),
            createdAt = Instant.parse(rows.getString("created_at")),
            updatedAt = Instant.parse(rows.getString("updated_at")),
        )
    }

    private fun touchSession(connection: Connection, sessionId: String) {
        connection.prepareStatement(
            "update sessions set updated_at = ? where id = ?",
        ).use { statement ->
            statement.setString(1, Instant.now().toString())
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${databasePath}")
}

@Serializable
private data class SessionSnapshotSurrogate(
    val id: String,
    val workspaceRoot: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val items: List<ConversationItem>,
    val toolInvocations: List<ToolInvocationRecordSurrogate>,
    val pendingApprovals: List<PendingApprovalSurrogate>,
) {
    companion object {
        fun from(snapshot: SessionSnapshot): SessionSnapshotSurrogate {
            return SessionSnapshotSurrogate(
                id = snapshot.id,
                workspaceRoot = snapshot.workspaceRoot.toString(),
                status = snapshot.status.name,
                createdAt = snapshot.createdAt.toString(),
                updatedAt = snapshot.updatedAt.toString(),
                items = snapshot.items,
                toolInvocations = snapshot.toolInvocations.map { ToolInvocationRecordSurrogate.from(it) },
                pendingApprovals = snapshot.pendingApprovals.map { PendingApprovalSurrogate.from(it) },
            )
        }
    }
}

@Serializable
private data class ToolInvocationRecordSurrogate(
    val id: String,
    val sessionId: String,
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
    val status: String,
    val summary: String?,
    val outputJson: String?,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        fun from(record: ToolInvocationRecord): ToolInvocationRecordSurrogate {
            return ToolInvocationRecordSurrogate(
                id = record.id,
                sessionId = record.sessionId,
                toolCallId = record.toolCallId,
                toolName = record.toolName,
                arguments = record.arguments,
                status = record.status.name,
                summary = record.summary,
                outputJson = record.outputJson,
                createdAt = record.createdAt.toString(),
                updatedAt = record.updatedAt.toString(),
            )
        }
    }
}

@Serializable
private data class PendingApprovalSurrogate(
    val id: String,
    val sessionId: String,
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
    val summary: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        fun from(approval: PendingApproval): PendingApprovalSurrogate {
            return PendingApprovalSurrogate(
                id = approval.id,
                sessionId = approval.sessionId,
                toolCallId = approval.toolCallId,
                toolName = approval.toolName,
                arguments = approval.arguments,
                summary = approval.summary,
                status = approval.status.name,
                createdAt = approval.createdAt.toString(),
                updatedAt = approval.updatedAt.toString(),
            )
        }
    }
}
