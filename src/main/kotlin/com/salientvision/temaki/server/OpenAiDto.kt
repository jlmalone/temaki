package com.salientvision.temaki.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ----- request ----- */

@Serializable
data class RequestMessage(val role: String = "user", val content: String = "")

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<RequestMessage> = emptyList(),
    val stream: Boolean? = false,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
) {
    /** The text to inject this turn: the last user message, or the last message of any role. */
    fun promptToInject(): String? =
        messages.lastOrNull { it.role == "user" }?.content
            ?: messages.lastOrNull()?.content
}

/* ----- non-streaming response ----- */

@Serializable
data class ResponseMessage(val role: String = "assistant", val content: String)

@Serializable
data class ResponseChoice(
    val index: Int,
    val message: ResponseMessage,
    @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

/** Non-standard, client-ignored detail carrying the marshaller's true verdict for transparency. */
@Serializable
data class TemakiMeta(val state: String, val confidence: Double, val rationale: String? = null)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ResponseChoice>,
    val usage: Usage,
    @SerialName("x_temaki") val xTemaki: TemakiMeta? = null,
)

/* ----- streaming (SSE) chunks ----- */

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
)

/* ----- models ----- */

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "temaki",
)

@Serializable
data class ModelList(
    @SerialName("object") val objectType: String = "list",
    val data: List<ModelInfo>,
)

/* ----- errors ----- */

@Serializable
data class ErrorBody(val message: String, val type: String, val code: String? = null)

@Serializable
data class ErrorEnvelope(val error: ErrorBody)
