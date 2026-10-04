package dev.rizwan.pulse

import dev.rizwan.pulse.model.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The in-memory board every connected client is watching.
 *
 * Two things here are worth more than the rest of the file.
 *
 * **1. Sequence numbers, not timestamps.** Ordering by wall-clock time breaks
 * the moment two events land in the same millisecond, or the clock steps. A
 * monotonic counter gives a total order and an unambiguous "I have seen up to
 * here" cursor for reconnecting clients.
 *
 * **2. The history buffer is bounded.** Replay is capped at [HISTORY]. A client
 * gone longer than that is told it lagged rather than being handed a partial
 * history it would mistake for a complete one. An unbounded buffer is a memory
 * leak with a nice name.
 */
class IncidentBoard(private val historySize: Int = HISTORY) {

    companion object {
        const val HISTORY = 500
    }

    private val sequence = AtomicLong(0)
    private val mutex = Mutex()

    private val incidents = LinkedHashMap<String, Incident>()
    private val history = ArrayDeque<ServerMessage>()

    /**
     * extraBufferCapacity with DROP_OLDEST: a slow consumer never blocks the
     * producer or the other subscribers. It loses messages instead - which is
     * recoverable, because it can detect the gap from the seq and ask for a
     * replay. Suspending the producer to wait for one slow socket is how a
     * single bad client takes down a broadcast.
     */
    private val _stream = MutableSharedFlow<ServerMessage>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val stream: SharedFlow<ServerMessage> = _stream.asSharedFlow()

    suspend fun snapshot(): ServerMessage.Snapshot = mutex.withLock {
        ServerMessage.Snapshot(incidents.values.toList(), sequence.get())
    }

    /**
     * The answer to "I have seen up to [since], what did I miss?".
     *
     * Three distinct answers, not two. Returning a nullable list conflated the
     * two failures, and a client cannot react correctly to a reason it was
     * never told.
     */
    sealed interface Resume {
        /** The cursor is inside the buffer: exactly what was missed, in order. */
        data class Replay(val messages: List<ServerMessage>) : Resume

        /** The cursor is older than the buffer. The client fell behind. */
        data object TooOld : Resume

        /** The cursor is ahead of this server. The server restarted. */
        data object Restarted : Resume
    }

    suspend fun replayAfter(since: Long): Resume = mutex.withLock {
        // A cursor ahead of our own sequence cannot have come from this
        // process: it restarted and began counting again, so the client is
        // holding a board from a server that no longer exists. Replaying
        // "everything after 7" against a sequence at 0 matches nothing, so the
        // old code returned an empty list - indistinguishable from "you are
        // up to date" - and the client sat on a stale board indefinitely.
        if (since > sequence.get()) return@withLock Resume.Restarted

        val oldest = history.firstOrNull()?.seqOrNull()
        if (oldest != null && since < oldest - 1) return@withLock Resume.TooOld

        Resume.Replay(history.filter { (it.seqOrNull() ?: 0L) > since })
    }

    suspend fun raise(request: RaiseIncidentRequest): Incident {
        val incident = mutex.withLock {
            val created = Incident(
                seq = sequence.incrementAndGet(),
                id = UUID.randomUUID().toString(),
                title = request.title,
                severity = request.severity,
                source = request.source,
                raisedAt = Instant.now().toString(),
            )
            incidents[created.id] = created
            created
        }
        publish(ServerMessage.Raised(incident))
        return incident
    }

    /**
     * Acknowledging is idempotent on purpose. Two operators clicking at the
     * same moment is normal, not an error: the first wins, the second is told
     * the current state rather than shown a failure it cannot act on.
     */
    suspend fun acknowledge(id: String, by: String): Incident? {
        val updated = mutex.withLock {
            val current = incidents[id] ?: return@withLock null
            if (current.acknowledgedBy != null) return@withLock current
            val next = current.copy(seq = sequence.incrementAndGet(), acknowledgedBy = by)
            incidents[id] = next
            next
        } ?: return null

        publish(ServerMessage.Acknowledged(updated))
        return updated
    }

    suspend fun publishPresence(viewers: Int) {
        _stream.emit(ServerMessage.Presence(viewers))
    }

    /**
     * Non-suspending presence broadcast, for the disconnect path.
     *
     * A socket's coroutine scope is already cancelled by the time its `finally`
     * block runs, so `launch { emit(...) }` there is silently dropped and the
     * remaining viewers never learn that someone left. tryEmit does not need a
     * live scope. It can return false if the buffer is full, which for a
     * presence count is the right trade: the next one supersedes it anyway.
     */
    fun publishPresenceNow(viewers: Int): Boolean =
        _stream.tryEmit(ServerMessage.Presence(viewers))

    private suspend fun publish(message: ServerMessage) {
        mutex.withLock {
            history.addLast(message)
            while (history.size > historySize) history.removeFirst()
        }
        _stream.emit(message)
    }

    private fun ServerMessage.seqOrNull(): Long? = when (this) {
        is ServerMessage.Raised -> incident.seq
        is ServerMessage.Acknowledged -> incident.seq
        else -> null
    }
}
