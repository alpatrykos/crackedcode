package com.crackedcode.agent.cli

class FakeTerminalIO(
    override val isInteractive: Boolean,
    private val inputs: MutableList<String> = mutableListOf(),
) : TerminalIO {
    private val buffer = StringBuilder()
    val output: String
        get() = buffer.toString()

    override fun print(text: String) {
        buffer.append(text)
    }

    override fun println(text: String) {
        buffer.append(text).append('\n')
    }

    override fun readLine(): String? {
        return if (inputs.isEmpty()) null else inputs.removeAt(0)
    }
}
