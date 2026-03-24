package com.crackedcode.agent.cli

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = runBlocking {
        CliApplication(
            io = SystemTerminalIO(),
        ).run(args.toList())
    }
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}
