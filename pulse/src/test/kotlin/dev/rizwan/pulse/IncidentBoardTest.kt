package dev.rizwan.pulse

import dev.rizwan.pulse.model.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class IncidentBoardTest {

    @Test
    fun `sequence numbers are strictly increasing across every change`() = runTest {
        val board = IncidentBoard()

        val first = board.raise(RaiseIncidentRequest("Disk filling", Severity.WARNING))
        val second = board.raise(RaiseIncidentRequest("Latency spike", Severity.CRITICAL))
        val acked = board.acknowledge(first.id, "rizwan")

        assertTrue(second.seq > first.seq, "a later incident must have a higher seq")
        assertNotNull(acked)
        assertTrue(acked.seq > second.seq, "an acknowledgement advances the sequence too")
    }

    @Test
    fun `acknowledging is idempotent and keeps the first operator`() = runTest {
        val board = IncidentBoard()
        val incident = board.raise(RaiseIncidentRequest("Queue backing up"))

        val first = board.acknowledge(incident.id, "asha")
        val second = board.acknowledge(incident.id, "rizwan")

        assertEquals("asha", first?.acknowledgedBy)
        assertEquals("asha", second?.acknowledgedBy, "the second ack must not steal the first")
        assertEquals(first?.seq, second?.seq, "a no-op ack must not burn a sequence number")
    }

    @Test
    fun `acknowledging something that does not exist returns null rather than throwing`() = runTest {
        val board = IncidentBoard()
        assertNull(board.acknowledge("not-a-real-id", "rizwan"))
    }

    @Test
    fun `a client that reconnects is replayed exactly what it missed`() = runTest {
        val board = IncidentBoard()

        val seen = board.raise(RaiseIncidentRequest("Seen before the drop")).seq
        val missedA = board.raise(RaiseIncidentRequest("Missed one"))
        val missedB = board.raise(RaiseIncidentRequest("Missed two"))

        val replay = board.replayAfter(seen)

        assertNotNull(replay)
        assertEquals(2, replay.size, "exactly the two events after the cursor")
        val ids = replay.filterIsInstance<ServerMessage.Raised>().map { it.incident.id }
        assertEquals(listOf(missedA.id, missedB.id), ids, "and in the order they happened")
    }

    @Test
    fun `a client gone longer than the buffer is told it lagged rather than given a partial history`() =
        runTest {
            // A deliberately tiny buffer so the overflow is reachable in a test
            // rather than only under production load.
            val board = IncidentBoard(historySize = 3)

            repeat(10) { board.raise(RaiseIncidentRequest("Event $it")) }

            assertNull(
                board.replayAfter(0),
                "asking from before the buffer starts must return null, not a silently truncated list",
            )
            assertNotNull(
                board.replayAfter(9),
                "a cursor still inside the buffer is replayable",
            )
        }

    @Test
    fun `the snapshot reports the sequence the stream is up to`() = runTest {
        val board = IncidentBoard()
        board.raise(RaiseIncidentRequest("One"))
        val latest = board.raise(RaiseIncidentRequest("Two"))

        val snapshot = board.snapshot()

        assertEquals(2, snapshot.incidents.size)
        assertEquals(latest.seq, snapshot.seq, "a reconnecting client can resume from here")
    }
}
