package com.salientvision.temaki.marshaller

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Minimal chat interface to a *local* OpenAI-compatible server. Pane text only ever goes here. */
fun interface ChatClient {
    /** Send a system + user message pair, returning the assistant's raw text content. */
    fun complete(system: String, user: String): String
}

/** Thrown when the marshaller endpoint is unreachable or returns an unusable response. */
class ChatClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Where the local marshaller model lives. Sourced from the environment with localhost defaults
 * so a fresh checkout works against LM Studio (`:1234`) out of the box; point [baseUrl] at
 * Ollama (`:11434/v1`) or any other local OpenAI-compatible server as needed. Never a cloud host.
 */
data class MarshallerConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val requestTimeout: Duration = Duration.ofSeconds(60),
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:1234/v1"
        const val DEFAULT_MODEL = "local-model"

        fun fromEnv(env: (String) -> String? = System::getenv): MarshallerConfig =
            MarshallerConfig(
                baseUrl = (env("TEMAKI_MARSHALLER_BASE_URL") ?: DEFAULT_BASE_URL).trimEnd('/'),
                model = env("TEMAKI_MARSHALLER_MODEL") ?: DEFAULT_MODEL,
                apiKey = env("TEMAKI_MARSHALLER_KEY")?.takeIf { it.isNotBlank() },
            )
    }
}

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    val stream: Boolean = false,
)

@Serializable
private data class ChatChoice(val message: ChatMessage? = null)

@Serializable
private data class ChatCompletion(val choices: List<ChatChoice> = emptyList())

/** [ChatClient] backed by `POST {baseUrl}/chat/completions` on a local OpenAI-compatible server. */
class OpenAiChatClient(
    private val config: MarshallerConfig,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : ChatClient {

    override fun complete(system: String, user: String): String {
        val payload = ChatRequest(
            model = config.model,
            messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
        )
        val body = JSON.encodeToString(ChatRequest.serializer(), payload)

        val builder = HttpRequest.newBuilder(URI.create("${config.baseUrl}/chat/completions"))
            .timeout(config.requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        config.apiKey?.let { builder.header("Authorization", "Bearer $it") }

        val resp = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw ChatClientException("could not reach marshaller endpoint ${config.baseUrl}: ${e.message}", e)
        }
        if (resp.statusCode() !in 200..299) {
            throw ChatClientException("marshaller endpoint ${config.baseUrl} returned HTTP ${resp.statusCode()}")
        }
        val parsed = try {
            JSON.decodeFromString(ChatCompletion.serializer(), resp.body())
        } catch (e: Exception) {
            throw ChatClientException("could not parse marshaller response: ${e.message}", e)
        }
        return parsed.choices.firstOrNull()?.message?.content
            ?: throw ChatClientException("marshaller response had no choices/content")
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    }
}
