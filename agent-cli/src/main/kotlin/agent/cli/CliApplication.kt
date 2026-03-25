package agent.cli

class CliApplication(
    private val io: TerminalIO,
    private val commandParser: CliCommandParser = CliCommandParser(),
    private val workspaceResolver: WorkspaceResolver = WorkspaceResolver(),
    configLoader: CliConfigLoader = CliConfigLoader(),
    private val engineFactory: AgentEngineFactory = DefaultAgentEngineFactory(configLoader),
) {
    suspend fun run(args: List<String>): Int {
        val invocation = try {
            commandParser.parse(args)
        } catch (error: CliUsageException) {
            io.println("error: ${error.message}")
            printUsage(io)
            return 1
        }

        if (invocation.command == CliCommand.Help) {
            printUsage(io)
            return 0
        }

        if (invocation.command == CliCommand.Version) {
            printVersion(io)
            return 0
        }

        val workspaceRoot = try {
            workspaceResolver.resolve(invocation.workspaceOverride)
        } catch (error: CliUsageException) {
            io.println("error: ${error.message}")
            return 1
        }

        return when (val command = invocation.command) {
            is CliCommand.Repl -> runRepl(workspaceRoot, command.resumeSessionId)
            is CliCommand.Status -> runStatus(workspaceRoot, command.sessionId)
            CliCommand.Tools -> runTools(workspaceRoot)
            CliCommand.Version -> 0
            CliCommand.Help -> 0
        }
    }

    private suspend fun runRepl(workspaceRoot: java.nio.file.Path, resumeSessionId: String?): Int {
        if (!io.isInteractive) {
            io.println(
                "agent requires an interactive terminal for REPL mode. Run `agent` from a terminal, or use `agent status` / `agent tools`.",
            )
            return 1
        }

        val engine = engineFactory.create(workspaceRoot)
        io.println("agent")
        io.println("workspace: $workspaceRoot")
        return ReplSession(io, engine).run(resumeSessionId)
    }

    private suspend fun runStatus(workspaceRoot: java.nio.file.Path, sessionId: String?): Int {
        val engine = engineFactory.create(workspaceRoot)
        return printStatus(io, engine, sessionId)
    }

    private suspend fun runTools(workspaceRoot: java.nio.file.Path): Int {
        val engine = engineFactory.create(workspaceRoot)
        printTools(io, engine)
        return 0
    }
}
