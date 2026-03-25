package agent.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentConfigTest {
    @Test
    fun `protected workspace paths include workspace agent dir and in-workspace storage root`() {
        val workspaceRoot = Files.createTempDirectory("agent-config")
        val storageRoot = workspaceRoot.resolve(".internal-agent-state")

        val config = AgentConfig(
            workspaceRoot = workspaceRoot,
            storageRoot = storageRoot,
        )

        assertEquals(
            setOf(
                workspaceRoot.resolve(".agent").normalize(),
                storageRoot.normalize(),
            ),
            config.protectedWorkspacePaths(),
        )
    }
}
