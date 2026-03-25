package agent.cli

import java.nio.file.Files
import java.nio.file.Path

class WorkspaceResolver(
    private val currentWorkingDirectory: Path = Path.of(System.getProperty("user.dir")).normalize(),
) {
    fun resolve(workspaceOverride: String?): Path {
        val resolved = when {
            workspaceOverride == null -> currentWorkingDirectory
            else -> {
                val candidate = Path.of(workspaceOverride)
                if (candidate.isAbsolute) candidate else currentWorkingDirectory.resolve(candidate)
            }
        }.toAbsolutePath().normalize()

        if (!Files.isDirectory(resolved)) {
            throw CliUsageException("Workspace does not exist or is not a directory: $resolved")
        }
        return resolved
    }
}
