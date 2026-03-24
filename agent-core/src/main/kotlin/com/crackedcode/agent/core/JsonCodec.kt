package com.crackedcode.agent.core

import kotlinx.serialization.json.Json

val AgentJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}
