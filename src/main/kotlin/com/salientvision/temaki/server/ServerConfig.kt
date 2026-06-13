package com.salientvision.temaki.server

import java.util.UUID

/**
 * Server settings, sourced from the environment. The bridge binds to loopback and gates every
 * `/v1` route behind a bearer token. If no token is configured an ephemeral one is generated and
 * printed at startup, so the server is never accidentally open.
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val token: String,
    val tokenWasGenerated: Boolean,
    val modelName: String,
    val agentCmd: String,
) {
    val isLoopback: Boolean
        get() = host == "127.0.0.1" || host == "::1" || host == "localhost"

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8765
        const val DEFAULT_AGENT_CMD = "bc -q"

        fun fromEnv(env: (String) -> String? = System::getenv): ServerConfig {
            val explicitToken = env("TEMAKI_API_TOKEN")?.takeIf { it.isNotBlank() }
            val agentCmd = env("TEMAKI_AGENT_CMD")?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT_CMD
            return ServerConfig(
                host = env("TEMAKI_SERVER_HOST")?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST,
                port = env("TEMAKI_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
                token = explicitToken ?: generateToken(),
                tokenWasGenerated = explicitToken == null,
                modelName = env("TEMAKI_MODEL_NAME")?.takeIf { it.isNotBlank() } ?: modelNameFor(agentCmd),
                agentCmd = agentCmd,
            )
        }

        private fun generateToken(): String = "temaki-" + UUID.randomUUID().toString().replace("-", "")

        /** Derive a tidy model id from the agent command, e.g. "bc -q" -> "bc", "/usr/bin/cat" -> "cat". */
        fun modelNameFor(agentCmd: String): String =
            agentCmd.trim().substringBefore(' ').substringAfterLast('/').ifBlank { "temaki-agent" }
    }
}
