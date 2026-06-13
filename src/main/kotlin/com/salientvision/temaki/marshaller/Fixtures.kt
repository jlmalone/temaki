package com.salientvision.temaki.marshaller

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A captured turn for offline replay: the prompt that was sent, the ordered pane snapshots that
 * followed, and (optionally) the expected verdict for regression tests.
 *
 * On-disk layout of a fixture directory:
 * ```
 * <name>/
 *   prompt.txt            the prompt that was injected
 *   snapshot-000.txt      pane captures, oldest first (lexicographic order)
 *   snapshot-001.txt
 *   ...
 *   expected.json         optional: {"state":"DONE","response":"4"}
 * ```
 */
data class Fixture(
    val name: String,
    val prompt: String,
    val snapshots: List<String>,
    val expectedState: SessionState? = null,
    val expectedResponse: String? = null,
)

object Fixtures {
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class ExpectedSpec(val state: String? = null, val response: String? = null)

    /** Load a single fixture directory. Throws if it is missing or has no snapshots. */
    fun load(dir: File): Fixture {
        require(dir.isDirectory) { "fixture directory not found: ${dir.path}" }

        val prompt = File(dir, "prompt.txt")
            .takeIf { it.isFile }
            ?.readText()
            ?.removeSuffix("\n")
            ?: ""

        val snapshots = (dir.listFiles { f -> f.isFile && f.name.startsWith("snapshot-") && f.name.endsWith(".txt") } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.readText() }
        require(snapshots.isNotEmpty()) { "no snapshot-*.txt files in ${dir.path}" }

        var expectedState: SessionState? = null
        var expectedResponse: String? = null
        File(dir, "expected.json").takeIf { it.isFile }?.let { f ->
            val spec = JSON.decodeFromString(ExpectedSpec.serializer(), f.readText())
            expectedState = spec.state?.let {
                runCatching { SessionState.valueOf(it.trim().uppercase()) }.getOrNull()
            }
            expectedResponse = spec.response
        }

        return Fixture(dir.name, prompt, snapshots, expectedState, expectedResponse)
    }

    /** Load every immediate sub-directory of [root] that contains snapshots, sorted by name. */
    fun loadAll(root: File): List<Fixture> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { runCatching { load(it) }.getOrNull() }
}
