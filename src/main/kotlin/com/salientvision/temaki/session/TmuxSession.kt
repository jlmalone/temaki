package com.salientvision.temaki.session

import java.time.Duration

/**
 * A single live agent session running inside its own tmux session. The agent command is the pane's
 * foreground process, so [capture] returns only the agent's own rendered output.
 */
class TmuxSession(
    val name: String,
    private val tmux: TmuxControl = TmuxControl(),
) : AutoCloseable {

    /** (Re)create the tmux session running [agentCmd] and wait until it has rendered something. */
    fun start(
        agentCmd: String,
        width: Int = 120,
        height: Int = 50,
        startupTimeout: Duration = Duration.ofSeconds(5),
    ) {
        if (tmux.hasSession(name)) tmux.killSession(name)
        tmux.newSession(name, agentCmd, width, height)

        // Give the agent a moment to boot so the first turn observes a real baseline, not a blank pane.
        val deadline = System.nanoTime() + startupTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (capture().isNotBlank()) return
            sleepMillis(100)
        }
    }

    fun isAlive(): Boolean = tmux.paneAlive(name)

    fun capture(): String = tmux.capturePane(name)

    /** Type [text] into the pane literally, without submitting it. */
    fun sendText(text: String) = tmux.sendLiteral(name, text)

    fun sendEnter() = tmux.sendKeys(name, "Enter")

    /** Send Ctrl-C to the pane (interrupt the current operation). */
    fun interrupt() = tmux.sendKeys(name, "C-c")

    /** Type [text] and submit it with Enter. */
    fun sendLine(text: String) {
        sendText(text)
        sendEnter()
    }

    fun kill() = tmux.killSession(name)

    override fun close() = kill()

    private fun sleepMillis(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
