package com.salientvision.temaki.server

import com.salientvision.temaki.marshaller.Assessment
import com.salientvision.temaki.marshaller.SessionState
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hermetic route tests for the OpenAI-compatible surface. A fake [Backend] stands in for the live
 * tmux session, so these run without tmux, an agent, or a model.
 */
class ChatCompletionsRouteTest {
    private val token = "test-token"
    private val config = ServerConfig(
        host = "127.0.0.1", port = 0, token = token, tokenWasGenerated = false,
        modelName = "bc", agentCmd = "bc -q",
    )

    private fun backend(state: SessionState, response: String?): Backend = object : Backend {
        override val modelName = "bc"
        override suspend fun complete(prompt: String) = Assessment(state, response, 0.9, "fake verdict")
    }

    @Test
    fun modelsRequiresBearerToken() = testApplication {
        application { temakiModule(config, backend(SessionState.DONE, "4")) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models").status)
    }

    @Test
    fun rejectsWrongToken() = testApplication {
        application { temakiModule(config, backend(SessionState.DONE, "4")) }
        val res = client.get("/v1/models") { bearerAuth("nope") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun listsModels() = testApplication {
        application { temakiModule(config, backend(SessionState.DONE, "4")) }
        val res = client.get("/v1/models") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"object\":\"list\""), body)
        assertTrue(body.contains("\"id\":\"bc\""), body)
    }

    @Test
    fun healthzIsOpen() = testApplication {
        application { temakiModule(config, backend(SessionState.DONE, "4")) }
        val res = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("ok", res.bodyAsText())
    }

    @Test
    fun nonStreamingCompletionIsOpenAiShaped() = testApplication {
        application { temakiModule(config, backend(SessionState.DONE, "4")) }
        val res = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"bc","messages":[{"role":"user","content":"2+2"}]}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"object\":\"chat.completion\""), body)
        assertTrue(body.contains("\"content\":\"4\""), body)
        assertTrue(body.contains("\"finish_reason\":\"stop\""), body)
        assertTrue(body.contains("\"role\":\"assistant\""), body)
    }

    @Test
    fun emptyMessagesIsBadRequest() = testApplication {
        application { temakiModule(config, backend(SessionState.DONE, "4")) }
        val res = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"bc","messages":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun streamingEmitsChunksAndDone() = testApplication {
        application { temakiModule(config, backend(SessionState.DONE, "Paris")) }
        val res = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"bc","stream":true,"messages":[{"role":"user","content":"capital?"}]}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("chat.completion.chunk"), body)
        assertTrue(body.contains("\"role\":\"assistant\""), body)
        assertTrue(body.contains("Paris"), body)
        assertTrue(body.contains("data: [DONE]"), body)
    }

    @Test
    fun errorStateSurfacesAsContentAndMeta() = testApplication {
        application { temakiModule(config, backend(SessionState.ERROR, "Parse error: bad expression")) }
        val res = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"role":"user","content":"(2+2"}]}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("Parse error: bad expression"), body)
        assertTrue(body.contains("\"state\":\"error\""), body)
    }
}
