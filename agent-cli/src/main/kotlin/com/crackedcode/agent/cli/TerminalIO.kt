package com.crackedcode.agent.cli

interface TerminalIO {
    val isInteractive: Boolean

    fun print(text: String)

    fun println(text: String = "")

    fun readLine(): String?
}

class SystemTerminalIO : TerminalIO {
    override val isInteractive: Boolean = System.console() != null

    override fun print(text: String) {
        kotlin.io.print(text)
    }

    override fun println(text: String) {
        kotlin.io.println(text)
    }

    override fun readLine(): String? = readlnOrNull()
}
