package dev.rizwan.pulse.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Severity { INFO, WARNING, CRITICAL }

/**
 * One incident on the board.
 *
 * Every event carries a monotonically increasing [seq]. That number is the
 * whole reason a client can drop its connection and come back without losing
 * anything: it reconnects with `?since=<last seq it saw>` and the server
 * replays the gap. Without it, "realtime" quietly means "realtime unless your
 * train went into a tunnel".
 */
@Serializable
data class Incident(
    val seq: Long,
    val id: String,
    val title: String,
    val severity: Severity,
    val source: String,
    val raisedAt: String,
    val acknowledgedBy: String? = null,
)

/** Everything the server pushes down a socket, as a closed set. */
@Serializable
sealed interface ServerMessage {

    /** Sent once on connect: the current board, plus where the stream is up to. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val incidents: List<Incident>, val seq: Long) : ServerMessage

    @Serializable
    @SerialName("raised")
    data class Raised(val incident: Incident) : ServerMessage

    @Serializable
    @SerialName("acknowledged")
    data class Acknowledged(val incident: Incident) : ServerMessage

    /** Who else is watching. Presence is the cheapest way to make a console feel live. */
    @Serializable
    @SerialName("presence")
    data class Presence(val viewers: Int) : ServerMessage

    /**
     * The server could not keep this client up to date and dropped messages
     * rather than buffering without limit. Told honestly so the client can
     * resynchronise instead of silently showing a stale board.
     */
    @Serializable
    @SerialName("lagged")
    data class Lagged(val missed: Long, val resumeFrom: Long) : ServerMessage
}

@Serializable
data class RaiseIncidentRequest(
    val title: String,
    val severity: Severity = Severity.INFO,
    val source: String = "manual",
)

@Serializable
data class AcknowledgeRequest(val by: String)

@Serializable
data class ApiError(val error: String, val detail: String? = null)

/**
 * The healthcheck payload.
 *
 * A plain mapOf("status" to "UP", "viewers" to 3) looks harmless and fails at
 * runtime: kotlinx.serialization has no serializer for a Map with mixed value
 * types, so the endpoint 500s. Docker's HEALTHCHECK then never passes and
 * compose never starts anything that depends on this service.
 */
@Serializable
data class Health(val status: String, val viewers: Int)
