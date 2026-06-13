package com.salientvision.temaki.session

import java.io.File
import java.time.Duration

/**
 * Wraps the handful of `tmux` commands the bridge needs, via [ProcessBuilder]. The agent command
 * is launched *directly* as the pane process (not typed into an interactive shell), so the pane
 * carries only the agent's own output — no shell prompt, no host/user noise.
 */
class TmuxControl(val tmuxBin: String = resolveTmux()) {

    /** True if a `tmux` binary is available — used to skip live tests in environments without it. */
    fun available(): Boolean =
        runCatching { Proc.exec(listOf(tmuxBin, "-V"), Duration.ofSeconds(5)).ok }.getOrDefault(false)

    fun hasSession(session: String): Boolean =
        Proc.exec(listOf(tmuxBin, "has-session", "-t", session), Duration.ofSeconds(5)).ok

    fun newSession(session: String, agentCmd: String, width: Int, height: Int) {
        // The trailing agentCmd is run by tmux via `sh -c`, as the pane's foreground process.
        val r = Proc.exec(
            listOf(
                tmuxBin, "new-session", "-d", "-s", session, "-n", "main",
                "-x", width.toString(), "-y", height.toString(), agentCmd,
            ),
        )
        if (!r.ok) throw SessionException("tmux new-session failed: ${r.stderr.trim().ifEmpty { "exit ${r.exitCode}" }}")
    }

    fun killSession(session: String) {
        // Best effort: a missing session is not an error.
        Proc.exec(listOf(tmuxBin, "kill-session", "-t", session), Duration.ofSeconds(5))
    }

    /** Capture the rendered pane as plain text (escape sequences resolved by tmux). */
    fun capturePane(session: String): String {
        val r = Proc.exec(listOf(tmuxBin, "capture-pane", "-t", session, "-p"))
        if (!r.ok) throw SessionException("tmux capture-pane failed: ${r.stderr.trim().ifEmpty { "exit ${r.exitCode}" }}")
        return r.stdout
    }

    /** Send [text] to the pane literally (`-l`), so tmux key names inside it are not interpreted. */
    fun sendLiteral(session: String, text: String) {
        val r = Proc.exec(listOf(tmuxBin, "send-keys", "-t", session, "-l", text))
        if (!r.ok) throw SessionException("tmux send-keys (literal) failed: ${r.stderr.trim()}")
    }

    /** Send named [keys] (e.g. "Enter", "C-c") to the pane, interpreted by tmux. */
    fun sendKeys(session: String, vararg keys: String) {
        val r = Proc.exec(listOf(tmuxBin, "send-keys", "-t", session) + keys)
        if (!r.ok) throw SessionException("tmux send-keys failed: ${r.stderr.trim()}")
    }

    /** True if the pane's foreground process is still alive (the session still exists). */
    fun paneAlive(session: String): Boolean = hasSession(session)

    companion object {
        /** Locate a tmux binary, preferring an explicit override, then common install paths, then PATH. */
        fun resolveTmux(): String {
            System.getenv("TEMAKI_TMUX")?.let { if (File(it).canExecute()) return it }
            val candidates = listOf(
                "/opt/homebrew/bin/tmux",
                "/usr/local/bin/tmux",
                "/usr/bin/tmux",
                "/bin/tmux",
            )
            candidates.firstOrNull { File(it).canExecute() }?.let { return it }
            return "tmux"
        }
    }
}
