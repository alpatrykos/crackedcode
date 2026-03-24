package com.crackedcode.agent.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentConfigTest {
    @Test
    fun `protected workspace paths include workspace agent dir and in-workspace storage root`() {
        val workspaceRoot = Files.createTempDirectory("crackedcode-config")
        val storageRoot = workspaceRoot.resolve(".internal-agent-state")

        val config = AgentConfig(
            workspaceRoot = workspaceRoot,
            storageRoot = storageRoot,
        )

        assertEquals(
            setOf(
                workspaceRoot.resolve(".crackedcode-agent").normalize(),
                storageRoot.normalize(),
            ),
            config.protectedWorkspacePaths(),
        )
    }
}
