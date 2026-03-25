package agent.cli

object VersionInfo {
    val current: String by lazy {
        VersionInfo::class.java.getResource("/agent-version.txt")
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "dev"
    }
}
