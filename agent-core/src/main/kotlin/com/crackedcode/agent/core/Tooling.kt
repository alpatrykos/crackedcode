package com.crackedcode.agent.core

import java.nio.file.Path

data class ToolExecutionContext(
    val sessionId: String,
    val workspaceRoot: Path,
    val artifactRoot: Path,
    val shellTimeoutMillis: Long,
    val maxOutputChars: Int,
    val protectedWorkspacePaths: Set<Path> = emptySet(),
)

interface Tool {
    val spec: ToolSpec

    suspend fun execute(arguments: kotlinx.serialization.json.JsonObject, context: ToolExecutionContext): ToolResult
}

class ToolRegistry(tools: Iterable<Tool>) {
    private val toolsByName: Map<String, Tool> = tools.associateBy { it.spec.name }

    fun list(): List<ToolSpec> = toolsByName.values.map { it.spec }.sortedBy { it.name }

    fun require(name: String): Tool {
        return toolsByName[name] ?: error("Unknown tool: $name")
    }
}
