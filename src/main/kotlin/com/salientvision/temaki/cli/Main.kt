package com.salientvision.temaki.cli

import com.salientvision.temaki.marshaller.Fixtures
import com.salientvision.temaki.marshaller.HeuristicMarshaller
import com.salientvision.temaki.marshaller.LlmMarshaller
import com.salientvision.temaki.marshaller.Marshaller
import com.salientvision.temaki.marshaller.MarshallerConfig
import com.salientvision.temaki.marshaller.OpenAiChatClient
import com.salientvision.temaki.server.ServerConfig
import com.salientvision.temaki.server.TmuxAgentBackend
import com.salientvision.temaki.server.serve
import com.salientvision.temaki.session.SessionDriver
import com.salientvision.temaki.session.TmuxControl
import com.salientvision.temaki.session.TmuxSession
import java.io.File
import java.time.Duration
import kotlin.system.exitProcess

private const val VERSION = "0.1.0"

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "replay" -> replay(args.drop(1))
        "drive" -> drive(args.drop(1))
        "serve" -> serveCmd(args.drop(1))
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

/**
 * `marshal drive [--agent "<cmd>"] --prompt "<text>" [--prompt ...] [--llm]` — start a live tmux
 * session running the agent command, run each prompt through it, and print the marshaller's verdict.
 * Defaults the agent to `bc -q` (a free local REPL) so it smoke-tests with no paid backend.
 */
private fun drive(args: List<String>) {
    var agent = System.getenv("TEMAKI_AGENT_CMD") ?: "bc -q"
    var sessionName = "temaki-drive"
    var useLlm = false
    var timeoutSeconds = 30L
    val prompts = mutableListOf<String>()

    val it = args.iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--agent" -> agent = nextValue(it, arg)
            "--prompt" -> prompts += nextValue(it, arg)
            "--session" -> sessionName = nextValue(it, arg)
            "--timeout" -> timeoutSeconds = nextValue(it, arg).toLongOrNull()
                ?: run { System.err.println("drive: --timeout needs an integer"); exitProcess(2) }
            "--llm" -> useLlm = true
            else -> {
                System.err.println("drive: unknown argument '$arg'")
                exitProcess(2)
            }
        }
    }
    if (prompts.isEmpty()) {
        System.err.println("usage: marshal drive [--agent \"<cmd>\"] --prompt \"<text>\" [--prompt ...] [--llm]")
        exitProcess(2)
    }

    val tmux = TmuxControl()
    if (!tmux.available()) {
        System.err.println("drive: no tmux binary found (set TEMAKI_TMUX or install tmux)")
        exitProcess(3)
    }

    val marshaller: Marshaller = if (useLlm) {
        LlmMarshaller(OpenAiChatClient(MarshallerConfig.fromEnv()))
    } else {
        HeuristicMarshaller()
    }

    println("agent:      $agent")
    println("session:    $sessionName")
    println("marshaller: ${if (useLlm) "llm" else "heuristic"}")

    val session = TmuxSession(sessionName, tmux)
    session.start(agent)
    try {
        val driver = SessionDriver(session, marshaller, turnTimeout = Duration.ofSeconds(timeoutSeconds))
        for (prompt in prompts) {
            println()
            println(">>> $prompt")
            val a = driver.runTurn(prompt)
            println("    state:      ${a.state}")
            println("    confidence: ${"%.2f".format(a.confidence)}")
            a.rationale?.let { println("    rationale:  $it") }
            val rendered = a.response?.takeIf { it.isNotEmpty() }?.let { "\n" + it.prependIndent("      ") } ?: " (none)"
            println("    response:  $rendered")
        }
    } finally {
        session.kill()
        println()
        println("session killed.")
    }
}

/**
 * `marshal serve [--agent "<cmd>"] [--port N] [--token T] [--model M] [--host H]` — run the
 * OpenAI-compatible HTTP bridge over a live agent session. Settings default from the environment
 * (`TEMAKI_*`); flags override. Binds loopback and gates `/v1` behind a bearer token.
 */
private fun serveCmd(args: List<String>) {
    val base = ServerConfig.fromEnv()
    var host = base.host
    var port = base.port
    var token = base.token
    var tokenGenerated = base.tokenWasGenerated
    var agent = base.agentCmd
    var model = base.modelName
    var agentExplicit = false
    var modelExplicit = false

    val it = args.iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--host" -> host = nextValue(it, arg)
            "--port" -> port = nextValue(it, arg).toIntOrNull() ?: fail("serve: --port needs an integer")
            "--token" -> { token = nextValue(it, arg); tokenGenerated = false }
            "--agent" -> { agent = nextValue(it, arg); agentExplicit = true }
            "--model" -> { model = nextValue(it, arg); modelExplicit = true }
            else -> fail("serve: unknown argument '$arg'")
        }
    }
    if (agentExplicit && !modelExplicit) model = ServerConfig.modelNameFor(agent)

    val tmux = TmuxControl()
    if (!tmux.available()) fail("serve: no tmux binary found (set TEMAKI_TMUX or install tmux)")

    val config = ServerConfig(host, port, token, tokenGenerated, model, agent)
    val backend = TmuxAgentBackend(config.modelName, config.agentCmd, tmux = tmux)
    Runtime.getRuntime().addShutdownHook(Thread { backend.close() })
    serve(config, backend)
}

private fun nextValue(it: Iterator<String>, flag: String): String {
    if (!it.hasNext()) fail("$flag needs a value")
    return it.next()
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}

private fun printUsage() {
    println(
        """
        temaki marshal $VERSION — terminal-agent marshaller

        usage:
          marshal replay [--llm] <fixtureDir>           assess a captured fixture (state + response)
          marshal drive [--agent "<cmd>"] --prompt "<p>" drive a live tmux session through a prompt
                        [--prompt "<p>" ...] [--llm]     (repeat --prompt for sequential turns)
          marshal serve [--agent "<cmd>"] [--port N]     run the OpenAI-compatible HTTP bridge
                        [--token T] [--model M]          (loopback; bearer-gated; SSE supported)
          marshal version                               print version
          marshal help                                  show this help

        replay reads prompt.txt + snapshot-*.txt from a fixture directory (optionally expected.json).
        drive launches the agent command (default: bc -q) as a tmux pane and reports each turn.
        serve exposes POST /v1/chat/completions and GET /v1/models over the same live session.
        Settings default from TEMAKI_* env (see .env.example); --llm routes the verdict through the
        local TEMAKI_MARSHALLER_* endpoint instead of the deterministic heuristic.
        """.trimIndent(),
    )
}
