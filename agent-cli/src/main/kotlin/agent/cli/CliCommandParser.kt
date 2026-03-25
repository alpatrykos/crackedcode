package agent.cli

sealed interface CliCommand {
    data class Repl(val resumeSessionId: String? = null) : CliCommand

    data class Status(val sessionId: String? = null) : CliCommand

    data object Tools : CliCommand

    data object Version : CliCommand

    data object Help : CliCommand
}

data class CliInvocation(
    val workspaceOverride: String?,
    val command: CliCommand,
)

class CliUsageException(message: String) : IllegalArgumentException(message)

class CliCommandParser {
    fun parse(args: List<String>): CliInvocation {
        var workspaceOverride: String? = null
        var helpRequested = false
        var versionRequested = false
        val positionals = mutableListOf<String>()

        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "--workspace" -> {
                    if (index + 1 >= args.size) {
                        throw CliUsageException("Missing value for --workspace")
                    }
                    workspaceOverride = args[index + 1]
                    index += 2
                }

                "--help", "-h" -> {
                    helpRequested = true
                    index += 1
                }

                "--version", "-v" -> {
                    versionRequested = true
                    index += 1
                }

                else -> {
                    positionals += arg
                    index += 1
                }
            }
        }

        if (helpRequested) {
            return CliInvocation(workspaceOverride, CliCommand.Help)
        }

        if (versionRequested) {
            return CliInvocation(workspaceOverride, CliCommand.Version)
        }

        if (positionals.isEmpty()) {
            return CliInvocation(workspaceOverride, CliCommand.Repl())
        }

        return when (positionals.first()) {
            "help" -> CliInvocation(workspaceOverride, CliCommand.Help)
            "version" -> {
                if (positionals.size != 1) {
                    throw CliUsageException("Usage: agent version")
                }
                CliInvocation(workspaceOverride, CliCommand.Version)
            }
            "resume" -> {
                if (positionals.size != 2) {
                    throw CliUsageException("Usage: agent resume <session-id> [--workspace PATH]")
                }
                CliInvocation(workspaceOverride, CliCommand.Repl(resumeSessionId = positionals[1]))
            }

            "status" -> {
                if (positionals.size > 2) {
                    throw CliUsageException("Usage: agent status [session-id] [--workspace PATH]")
                }
                CliInvocation(workspaceOverride, CliCommand.Status(positionals.getOrNull(1)))
            }

            "tools" -> {
                if (positionals.size != 1) {
                    throw CliUsageException("Usage: agent tools [--workspace PATH]")
                }
                CliInvocation(workspaceOverride, CliCommand.Tools)
            }

            else -> throw CliUsageException("Unknown command: ${positionals.first()}")
        }
    }
}
