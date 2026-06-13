package com.salientvision.temaki.server

import com.salientvision.temaki.marshaller.Assessment
import com.salientvision.temaki.marshaller.HeuristicMarshaller
import com.salientvision.temaki.marshaller.Marshaller
import com.salientvision.temaki.session.SessionDriver
import com.salientvision.temaki.session.TmuxControl
import com.salientvision.temaki.session.TmuxSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A turn-taking backend the server drives: inject a prompt, get a marshalled [Assessment] back. */
interface Backend {
    val modelName: String

    /** Run one turn for [prompt]. Implementations serialize concurrent calls into a queue. */
    suspend fun complete(prompt: String): Assessment
}

/**
 * [Backend] backed by a live [TmuxSession]. A [Mutex] enforces one in-flight turn per session —
 * concurrent requests queue on it — and the blocking driver runs on [Dispatchers.IO] so it never
 * stalls the server's event loop. The session is started lazily and restarted if it has died.
 */
class TmuxAgentBackend(
    override val modelName: String,
    private val agentCmd: String,
    sessionName: String = "temaki-serve",
    private val marshaller: Marshaller = HeuristicMarshaller(),
    tmux: TmuxControl = TmuxControl(),
) : Backend, AutoCloseable {

    private val session = TmuxSession(sessionName, tmux)
    private val driver = SessionDriver(session, marshaller)
    private val mutex = Mutex()

    @Volatile
    private var started = false

    override suspend fun complete(prompt: String): Assessment = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureStarted()
            driver.runTurn(prompt)
        }
    }

    private fun ensureStarted() {
        if (!started || !session.isAlive()) {
            session.start(agentCmd)
            started = true
        }
    }

    override fun close() {
        runCatching { session.kill() }
    }
}
