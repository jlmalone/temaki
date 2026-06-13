package com.salientvision.temaki.cli

import com.salientvision.temaki.marshaller.Fixtures
import com.salientvision.temaki.marshaller.HeuristicMarshaller
import com.salientvision.temaki.marshaller.LlmMarshaller
import com.salientvision.temaki.marshaller.Marshaller
import com.salientvision.temaki.marshaller.MarshallerConfig
import com.salientvision.temaki.marshaller.OpenAiChatClient
import java.io.File
import kotlin.system.exitProcess

private const val VERSION = "0.1.0"

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "replay" -> replay(args.drop(1))
        "version", "--version", "-v" -> println("temaki marshal $VERSION")
        "help", "--help", "-h", null -> printUsage()
        else -> {
            System.err.println("unknown command: ${args.first()}")
            printUsage()
            exitProcess(2)
        }
    }
}

/**
 * `marshal replay [--llm] <fixtureDir>` — run a marshaller over a captured fixture and print the
 * verdict. Uses the deterministic heuristic by default; `--llm` uses the local model endpoint
 * from the environment (see `.env.example`).
 */
private fun replay(args: List<String>) {
    var useLlm = false
    var dirArg: String? = null
    for (a in args) {
        when (a) {
            "--llm" -> useLlm = true
            "--heuristic" -> useLlm = false
            else -> dirArg = a
        }
    }
    if (dirArg == null) {
        System.err.println("usage: marshal replay [--llm] <fixtureDir>")
        exitProcess(2)
    }

    val fixture = Fixtures.load(File(dirArg))
    val marshaller: Marshaller = if (useLlm) {
        LlmMarshaller(OpenAiChatClient(MarshallerConfig.fromEnv()))
    } else {
        HeuristicMarshaller()
    }

    val assessment = marshaller.assess(fixture.prompt, fixture.snapshots)

    println("fixture:    ${fixture.name}")
    println("prompt:     ${fixture.prompt}")
    println("snapshots:  ${fixture.snapshots.size}")
    println("marshaller: ${if (useLlm) "llm" else "heuristic"}")
    println("state:      ${assessment.state}")
    println("confidence: ${"%.2f".format(assessment.confidence)}")
    assessment.rationale?.let { println("rationale:  $it") }
    println("--- response ---")
    println(assessment.response ?: "(none)")

    fixture.expectedState?.let { expected ->
        val match = expected == assessment.state
        println("--- expected ---")
        println("state: $expected -> ${if (match) "MATCH" else "MISMATCH"}")
        if (!match) exitProcess(1)
    }
}

private fun printUsage() {
    println(
        """
        temaki marshal $VERSION — terminal-agent marshaller

        usage:
          marshal replay [--llm] <fixtureDir>   assess a captured fixture, print state + response
          marshal version                       print version
          marshal help                          show this help

        A fixture directory holds prompt.txt and snapshot-*.txt captures (optionally expected.json).
        --llm uses the local marshaller endpoint from TEMAKI_MARSHALLER_* env vars; default is the
        deterministic heuristic, which needs no model.
        """.trimIndent(),
    )
}
