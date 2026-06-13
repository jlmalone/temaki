package com.salientvision.temaki.session

import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Raised when a subprocess (tmux) fails, times out, or a session operation cannot proceed. */
class SessionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The outcome of running an external command. */
data class ProcResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Thin wrapper over [ProcessBuilder] that runs a command to completion with a hard timeout. */
internal object Proc {
    fun exec(command: List<String>, timeout: Duration = Duration.ofSeconds(10)): ProcResult {
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: Exception) {
            throw SessionException("failed to start: ${command.joinToString(" ")} (${e.message})", e)
        }
        process.outputStream.close() // we never write to the child's stdin here

        val out = StreamGobbler(process.inputStream).also { it.start() }
        val err = StreamGobbler(process.errorStream).also { it.start() }

        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            out.join(500)
            err.join(500)
            throw SessionException("command timed out after $timeout: ${command.joinToString(" ")}")
        }
        out.join(1000)
        err.join(1000)
        return ProcResult(process.exitValue(), out.text(), err.text())
    }

    /** Reads a stream to EOF on its own thread so the child can never block on a full pipe. */
    private class StreamGobbler(private val source: InputStream) : Thread() {
        private var bytes: ByteArray = ByteArray(0)

        override fun run() {
            bytes = source.readBytes()
        }

        fun text(): String = String(bytes, Charsets.UTF_8)
    }
}
