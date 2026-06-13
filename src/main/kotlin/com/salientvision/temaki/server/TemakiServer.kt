package com.salientvision.temaki.server

import com.salientvision.temaki.marshaller.Assessment
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Install plugins and routes onto an [Application]. Shared by the live server and the route tests. */
fun Application.temakiModule(config: ServerConfig, backend: Backend) {
    install(ContentNegotiation) { json(JSON) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorEnvelope(ErrorBody(cause.message ?: "internal error", "internal_error")),
            )
        }
    }
    install(Authentication) {
        bearer("api") {
            authenticate { credential ->
                if (credential.token == config.token) UserIdPrincipal("client") else null
            }
        }
    }

    routing {
        // Unauthenticated liveness probe.
        get("/healthz") { call.respondText("ok") }

        authenticate("api") {
            get("/v1/models") {
                call.respond(ModelList(data = listOf(ModelInfo(id = backend.modelName, created = nowEpochSeconds()))))
            }

            post("/v1/chat/completions") {
                val request = call.receive<ChatCompletionRequest>()
                val prompt = request.promptToInject()
                if (prompt.isNullOrEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorEnvelope(ErrorBody("no message content to send to the agent", "invalid_request_error")),
                    )
                    return@post
                }

                val model = request.model ?: backend.modelName
                val assessment = backend.complete(prompt)

                if (request.stream == true) {
                    streamCompletion(call, model, assessment)
                } else {
                    call.respond(assessment.toChatCompletion(model, prompt))
                }
            }
        }
    }
}

/** Start the live server (blocks until shut down). Binds loopback by default; gates `/v1` by token. */
fun serve(config: ServerConfig, backend: Backend) {
    if (!config.isLoopback) {
        System.err.println("temaki: WARNING binding to non-loopback host ${config.host} — the token is the only guard")
    }
    if (config.tokenWasGenerated) {
        System.err.println("temaki: generated API token: ${config.token}")
    }
    System.err.println(
        "temaki: model '${config.modelName}' (agent: ${config.agentCmd}) " +
            "on http://${config.host}:${config.port}  [Ctrl-C to stop]",
    )
    embeddedServer(Netty, host = config.host, port = config.port) {
        temakiModule(config, backend)
    }.start(wait = true)
}

/* ----- mapping & streaming ----- */

private fun Assessment.toChatCompletion(model: String, prompt: String): ChatCompletionResponse {
    val content = response ?: ""
    val promptTokens = estimateTokens(prompt)
    val completionTokens = estimateTokens(content)
    return ChatCompletionResponse(
        id = newId(),
        created = nowEpochSeconds(),
        model = model,
        choices = listOf(ResponseChoice(0, ResponseMessage("assistant", content), "stop")),
        usage = Usage(promptTokens, completionTokens, promptTokens + completionTokens),
        xTemaki = TemakiMeta(state.name.lowercase(), confidence, rationale),
    )
}

private suspend fun streamCompletion(
    call: io.ktor.server.application.ApplicationCall,
    model: String,
    assessment: Assessment,
) {
    val id = newId()
    val created = nowEpochSeconds()
    val content = assessment.response ?: ""

    call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
        fun frame(chunk: ChatCompletionChunk) {
            write("data: ")
            write(JSON.encodeToString(ChatCompletionChunk.serializer(), chunk))
            write("\n\n")
            flush()
        }

        frame(ChatCompletionChunk(id = id, created = created, model = model, choices = listOf(ChunkChoice(0, Delta(role = "assistant")))))
        for (piece in content.chunked(24)) {
            frame(ChatCompletionChunk(id = id, created = created, model = model, choices = listOf(ChunkChoice(0, Delta(content = piece)))))
        }
        frame(ChatCompletionChunk(id = id, created = created, model = model, choices = listOf(ChunkChoice(0, Delta(), finishReason = "stop"))))
        write("data: [DONE]\n\n")
        flush()
    }
}

private fun newId(): String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")

private fun nowEpochSeconds(): Long = Instant.now().epochSecond

private fun estimateTokens(s: String): Int = if (s.isEmpty()) 0 else (s.length + 3) / 4
