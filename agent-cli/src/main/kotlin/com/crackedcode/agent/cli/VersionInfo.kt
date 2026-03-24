package com.crackedcode.agent.cli

object VersionInfo {
    val current: String by lazy {
        VersionInfo::class.java.getResource("/ccode-version.txt")
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "dev"
    }
}
