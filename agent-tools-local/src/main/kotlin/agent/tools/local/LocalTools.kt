package agent.tools.local

import agent.core.AgentJson
import agent.core.Tool
import agent.core.ToolExecutionContext
import agent.core.ToolResult
import agent.core.ToolSpec
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

fun defaultLocalTools(): List<Tool> = listOf(
    ListFilesTool(),
    ReadFileTool(),
    SearchFilesTool(),
    RunShellTool(),
    ApplyPatchTool(),
)

class ListFilesTool : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "list_files",
        description = "List files under the workspace root or a relative subdirectory.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("max_depth") { put("type", "integer") }
            }
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResult {
        val path = resolveWorkspacePath(arguments.string("path") ?: ".", context.workspaceRoot)
        requireModelReadablePath(path, context)
        val maxDepth = arguments.int("max_depth") ?: 4
        val entries = Files.walk(path, maxDepth).use { stream ->
            stream
                .filter { it != path }
                .filter { isModelReadablePath(it, context.workspaceRoot, context.protectedWorkspacePaths) }
                .sorted()
                .map { workspaceRelative(it, context.workspaceRoot) + if (Files.isDirectory(it)) "/" else "" }
                .toList()
        }
        return ToolResult(
            content = entries.joinToString(separator = "\n").ifBlank { "(empty)" },
            metadata = buildJsonObject {
                put("count", entries.size)
                put("path", workspaceRelative(path, context.workspaceRoot))
            },
        )
    }
}

class ReadFileTool : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "read_file",
        description = "Read a UTF-8 text file inside the workspace. Optional line range is inclusive.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("start_line") { put("type", "integer") }
                putJsonObject("end_line") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResult {
        val file = resolveWorkspacePath(arguments.requiredString("path"), context.workspaceRoot)
        requireModelReadablePath(file, context)
        val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
        val requestedStartLine = arguments.int("start_line") ?: 1
        val requestedEndLine = arguments.int("end_line") ?: lines.size
        val startLine = requestedStartLine.coerceIn(1, lines.size + 1)
        val endLine = requestedEndLine.coerceIn(startLine - 1, lines.size)
        val rendered = if (lines.isEmpty()) {
            "(empty file)"
        } else {
            lines.subList(startLine - 1, endLine).mapIndexed { index, content ->
                "${startLine + index}: $content"
            }.joinToString(separator = "\n")
        }
        return ToolResult(
            content = rendered,
            metadata = buildJsonObject {
                put("path", workspaceRelative(file, context.workspaceRoot))
                put("start_line", startLine)
                put("end_line", endLine)
            },
        )
    }
}

class SearchFilesTool : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "search_files",
        description = "Search text files for a pattern. Uses ripgrep when available and falls back to a Kotlin walker otherwise.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string") }
                putJsonObject("path") { put("type", "string") }
                putJsonObject("max_results") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("query")) }
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResult {
        val query = arguments.requiredString("query")
        val path = resolveWorkspacePath(arguments.string("path") ?: ".", context.workspaceRoot)
        requireModelReadablePath(path, context)
        val maxResults = (arguments.int("max_results") ?: 50).coerceIn(1, 200)

        val result = runCatching {
            searchWithRipgrep(query, path, context.workspaceRoot, context.protectedWorkspacePaths, maxResults)
        }.getOrElse {
            fallbackSearch(query, path, context.workspaceRoot, context.protectedWorkspacePaths, maxResults)
        }

        return ToolResult(
            content = result.joinToString(separator = "\n").ifBlank { "(no matches)" },
            metadata = buildJsonObject {
                put("count", result.size)
                put("query", query)
            },
        )
    }

    private fun searchWithRipgrep(
        query: String,
        path: Path,
        workspaceRoot: Path,
        protectedWorkspacePaths: Set<Path>,
        maxResults: Int,
    ): List<String> {
        val command = mutableListOf(
            "rg",
            "-n",
            "--color",
            "never",
            "--max-count",
            maxResults.toString(),
        )
        ripgrepExclusions(workspaceRoot, protectedWorkspacePaths).forEach { glob ->
            command += listOf("--glob", glob)
        }
        command += listOf(query, path.toString())

        val process = ProcessBuilder(command).directory(workspaceRoot.toFile()).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("ripgrep timed out")
        }
        if (process.exitValue() !in setOf(0, 1)) {
            error(process.errorStream.readAllBytes().toString(StandardCharsets.UTF_8))
        }
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8).trim()
        return output.lines().filter { it.isNotBlank() }.take(maxResults)
    }

    private fun fallbackSearch(
        query: String,
        path: Path,
        workspaceRoot: Path,
        protectedWorkspacePaths: Set<Path>,
        maxResults: Int,
    ): List<String> {
        val matches = mutableListOf<String>()
        Files.walk(path).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && isModelReadablePath(it, workspaceRoot, protectedWorkspacePaths) }
                .forEach { file ->
                    if (matches.size >= maxResults) {
                        return@forEach
                    }
                    val relative = workspaceRelative(file, workspaceRoot)
                    runCatching {
                        Files.readAllLines(file, StandardCharsets.UTF_8).forEachIndexed { index, line ->
                            if (line.contains(query) && matches.size < maxResults) {
                                matches += "$relative:${index + 1}:$line"
                            }
                        }
                    }
                }
        }
        return matches
    }
}

class RunShellTool : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "run_shell",
        description = "Run a shell command inside the workspace root and return stdout, stderr, and exit code.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("command")) }
        },
        mutating = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResult {
        val command = arguments.requiredString("command")
        Files.createDirectories(context.artifactRoot)
        val process = ProcessBuilder("/bin/zsh", "-lc", command)
            .directory(context.workspaceRoot.toFile())
            .start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = thread(start = true) {
            stdout.append(process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8))
        }
        val stderrThread = thread(start = true) {
            stderr.append(process.errorStream.readAllBytes().toString(StandardCharsets.UTF_8))
        }

        val finished = process.waitFor(context.shellTimeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join()
            stderrThread.join()
            return ToolResult(content = "Command timed out after ${context.shellTimeoutMillis} ms.", isError = true)
        }

        stdoutThread.join()
        stderrThread.join()

        val transcript = buildString {
            appendLine("command: $command")
            appendLine("exit_code: ${process.exitValue()}")
            appendLine("stdout:")
            appendLine(stdout.toString())
            appendLine("stderr:")
            appendLine(stderr.toString())
        }
        val transcriptFile = context.artifactRoot.resolve("command-${Instant.now().toEpochMilli()}.txt")
        Files.writeString(
            transcriptFile,
            transcript.take(context.maxOutputChars * 2),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        val content = truncateTranscript(stdout.toString(), stderr.toString(), process.exitValue(), context.maxOutputChars)
        return ToolResult(
            content = content,
            isError = process.exitValue() != 0,
            metadata = buildJsonObject {
                put("exit_code", process.exitValue())
                put("artifact", workspaceRelative(transcriptFile, context.workspaceRoot))
            },
        )
    }

    private fun truncateTranscript(stdout: String, stderr: String, exitCode: Int, maxOutputChars: Int): String {
        val rendered = buildString {
            appendLine("exit_code: $exitCode")
            appendLine("stdout:")
            appendLine(stdout)
            appendLine("stderr:")
            appendLine(stderr)
        }
        return if (rendered.length <= maxOutputChars) {
            rendered
        } else {
            rendered.take(maxOutputChars) + "\n... output truncated ..."
        }
    }
}

class ApplyPatchTool : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "apply_patch",
        description = "Apply a unified diff patch inside the workspace root.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("patch") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("patch")) }
        },
        mutating = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResult {
        val patch = arguments.requiredString("patch")
        Files.createDirectories(context.artifactRoot)
        val patchFile = context.artifactRoot.resolve("patch-${Instant.now().toEpochMilli()}.diff")
        Files.writeString(
            patchFile,
            patch,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        val filesChanged = UnifiedDiffApplier(context.workspaceRoot).apply(patch)
        return ToolResult(
            content = "Applied patch to ${filesChanged.size} file(s): ${filesChanged.joinToString()}",
            metadata = buildJsonObject {
                put("artifact", workspaceRelative(patchFile, context.workspaceRoot))
                putJsonArray("files") {
                    filesChanged.forEach { add(JsonPrimitive(it)) }
                }
            },
        )
    }
}

private class UnifiedDiffApplier(
    private val workspaceRoot: Path,
) {
    fun apply(patch: String): List<String> {
        val files = parsePatch(patch)
        if (files.isEmpty()) {
            error("Patch did not contain any unified diff hunks.")
        }
        val changed = mutableListOf<String>()
        for (patchFile in files) {
            val targetRelative = patchFile.newPath ?: patchFile.oldPath ?: error("Patch file is missing both paths.")
            val targetPath = resolveWorkspacePath(targetRelative, workspaceRoot)
            requireWritableWorkspacePath(targetPath, workspaceRoot)
            val sourceLines = when {
                patchFile.oldPath == null -> emptyList()
                Files.exists(targetPath) -> Files.readAllLines(targetPath, StandardCharsets.UTF_8)
                else -> emptyList()
            }
            val outputLines = applyHunks(sourceLines, patchFile)
            when {
                patchFile.newPath == null -> Files.deleteIfExists(targetPath)
                else -> {
                    Files.createDirectories(targetPath.parent)
                    Files.writeString(
                        targetPath,
                        if (outputLines.isEmpty()) "" else outputLines.joinToString(separator = "\n", postfix = "\n"),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    )
                }
            }
            changed += targetRelative
        }
        return changed
    }

    private fun applyHunks(sourceLines: List<String>, patchFile: PatchFile): List<String> {
        val result = mutableListOf<String>()
        var sourceIndex = 0
        for (hunk in patchFile.hunks) {
            val hunkStart = (hunk.oldStart.takeIf { it > 0 } ?: 1) - 1
            if (hunkStart < sourceIndex) {
                error("Overlapping hunks for ${patchFile.displayPath()}")
            }
            result += sourceLines.subList(sourceIndex, hunkStart.coerceAtMost(sourceLines.size))
            var localIndex = hunkStart
            for (line in hunk.lines) {
                val prefix = line.firstOrNull() ?: error("Empty hunk line in ${patchFile.displayPath()}")
                val content = line.drop(1)
                when (prefix) {
                    ' ' -> {
                        requireSourceLine(sourceLines, localIndex, content, patchFile)
                        result += content
                        localIndex += 1
                    }

                    '-' -> {
                        requireSourceLine(sourceLines, localIndex, content, patchFile)
                        localIndex += 1
                    }

                    '+' -> result += content

                    else -> error("Unsupported hunk prefix '$prefix' in ${patchFile.displayPath()}")
                }
            }
            sourceIndex = localIndex
        }
        result += sourceLines.subList(sourceIndex, sourceLines.size)
        return result
    }

    private fun requireSourceLine(sourceLines: List<String>, index: Int, expected: String, patchFile: PatchFile) {
        val actual = sourceLines.getOrNull(index)
        if (actual != expected) {
            error(
                "Patch context mismatch in ${patchFile.displayPath()} at source line ${index + 1}. Expected <$expected> but found <$actual>.",
            )
        }
    }

    private fun parsePatch(patch: String): List<PatchFile> {
        val lines = patch.replace("\r\n", "\n").split('\n').dropLastWhile { it.isEmpty() }
        val files = mutableListOf<PatchFile>()
        var index = 0
        while (index < lines.size) {
            when {
                lines[index].startsWith("--- ") -> {
                    val oldPath = normalizePatchPath(lines[index].removePrefix("--- ").trim())
                    index += 1
                    require(index < lines.size && lines[index].startsWith("+++ ")) { "Missing +++ line after ---" }
                    val newPath = normalizePatchPath(lines[index].removePrefix("+++ ").trim())
                    index += 1
                    val hunks = mutableListOf<PatchHunk>()
                    while (index < lines.size && !lines[index].startsWith("--- ")) {
                        if (lines[index].startsWith("@@")) {
                            val header = lines[index]
                            val match = HUNK_HEADER.matchEntire(header)
                                ?: error("Invalid hunk header: $header")
                            val oldStart = match.groupValues[1].toInt()
                            val oldCount = match.groupValues[2].ifBlank { "1" }.removePrefix(",").toInt()
                            val newStart = match.groupValues[3].toInt()
                            val newCount = match.groupValues[4].ifBlank { "1" }.removePrefix(",").toInt()
                            index += 1
                            val hunkLines = mutableListOf<String>()
                            while (index < lines.size && !lines[index].startsWith("@@") && !lines[index].startsWith("--- ")) {
                                val line = lines[index]
                                if (line.startsWith("\\ No newline at end of file")) {
                                    index += 1
                                    continue
                                }
                                if (line.isNotEmpty()) {
                                    hunkLines += line
                                } else {
                                    hunkLines += " "
                                }
                                index += 1
                            }
                            hunks += PatchHunk(oldStart, oldCount, newStart, newCount, hunkLines)
                        } else {
                            index += 1
                        }
                    }
                    files += PatchFile(oldPath, newPath, hunks)
                }

                else -> index += 1
            }
        }
        return files
    }

    private fun normalizePatchPath(raw: String): String? {
        if (raw == "/dev/null") {
            return null
        }
        return raw.removePrefix("a/").removePrefix("b/")
    }

    private fun PatchFile.displayPath(): String = newPath ?: oldPath ?: "<unknown>"

    companion object {
        private val HUNK_HEADER = Regex("""@@ -(\d+)(,\d+)? \+(\d+)(,\d+)? @@.*""")
    }
}

private data class PatchFile(
    val oldPath: String?,
    val newPath: String?,
    val hunks: List<PatchHunk>,
)

private data class PatchHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<String>,
)

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.requiredString(name: String): String =
    string(name) ?: error("Missing required string argument: $name")

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun workspaceRelative(path: Path, workspaceRoot: Path): String {
    return if (path.startsWith(workspaceRoot)) {
        workspaceRoot.relativize(path).toString().ifBlank { "." }
    } else {
        path.toString()
    }
}

private fun isModelReadablePath(path: Path, workspaceRoot: Path, protectedWorkspacePaths: Set<Path>): Boolean {
    val normalizedPath = path.normalize()
    if (protectedWorkspacePaths.any { normalizedPath.startsWith(it) }) {
        return false
    }

    val realPath = existingRealPath(normalizedPath) ?: return true
    val realWorkspaceRoot = existingRealPath(workspaceRoot.normalize()) ?: workspaceRoot.normalize()
    if (!realPath.startsWith(realWorkspaceRoot)) {
        return false
    }

    return protectedWorkspacePaths.none { protectedPath ->
        val realProtectedPath = existingRealPath(protectedPath) ?: protectedPath
        realPath.startsWith(realProtectedPath)
    }
}

private fun requireModelReadablePath(path: Path, context: ToolExecutionContext) {
    if (!isModelReadablePath(path, context.workspaceRoot, context.protectedWorkspacePaths)) {
        error("Path is protected from model-readable tools: ${workspaceRelative(path, context.workspaceRoot)}")
    }
}

private fun ripgrepExclusions(workspaceRoot: Path, protectedWorkspacePaths: Set<Path>): List<String> {
    return protectedWorkspacePaths.flatMap { protectedPath ->
        val relative = workspaceRelative(protectedPath, workspaceRoot).replace('\\', '/')
        listOf("!$relative", "!$relative/**")
    }
}

private fun existingRealPath(path: Path): Path? {
    return if (Files.exists(path)) {
        runCatching { path.toRealPath() }.getOrNull()
    } else {
        null
    }
}

private fun requireWritableWorkspacePath(path: Path, workspaceRoot: Path) {
    val normalizedWorkspaceRoot = workspaceRoot.normalize()
    val normalizedPath = path.normalize()
    if (!normalizedPath.startsWith(normalizedWorkspaceRoot)) {
        error("Path escapes workspace root: ${workspaceRelative(path, workspaceRoot)}")
    }

    val realWorkspaceRoot = existingRealPath(normalizedWorkspaceRoot) ?: normalizedWorkspaceRoot
    val existingAncestor = nearestExistingPath(normalizedPath) ?: normalizedWorkspaceRoot
    val realAncestor = existingRealPath(existingAncestor) ?: existingAncestor
    if (!realAncestor.startsWith(realWorkspaceRoot)) {
        error("Path escapes workspace root: ${workspaceRelative(path, workspaceRoot)}")
    }
}

private fun nearestExistingPath(path: Path): Path? {
    var current: Path? = path
    while (current != null) {
        if (Files.exists(current) || Files.isSymbolicLink(current)) {
            return current
        }
        current = current.parent
    }
    return null
}

private fun resolveWorkspacePath(candidate: String, workspaceRoot: Path): Path {
    val resolved = workspaceRoot.resolve(candidate).normalize()
    if (!resolved.startsWith(workspaceRoot.normalize())) {
        error("Path escapes workspace root: $candidate")
    }
    return resolved
}
