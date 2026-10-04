package dev.rizwan.pulse

import dev.rizwan.pulse.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json

fun Application.configureRouting(board: IncidentBoard) {
    val json = Json { classDiscriminator = "type" }
    val viewers = AtomicInteger(0)

    routing {
        get("/health") {
            call.respond(Health(status = "UP", viewers = viewers.get()))
        }

        route("/api/incidents") {
            get {
                call.respond(board.snapshot())
            }

            post {
                val request = call.receive<RaiseIncidentRequest>()
                if (request.title.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("title must not be blank"))
                    return@post
                }
                call.respond(HttpStatusCode.Created, board.raise(request))
            }

            post("/{id}/ack") {
                val id = call.parameters["id"].orEmpty()
                val request = call.receive<AcknowledgeRequest>()
                val updated = board.acknowledge(id, request.by)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("no incident with id ${'$'}id"))
                } else {
                    call.respond(updated)
                }
            }
        }

        /**
         * The live feed.
         *
         * Connect with ?since=<seq> to resume. The server replays what was
         * missed, or says it could not - it never pretends a partial history is
         * complete.
         */
        webSocket("/ws") {
            val since = call.request.queryParameters["since"]?.toLongOrNull()
            val count = viewers.incrementAndGet()

            try {
                if (since == null) {
                    sendMessage(json, board.snapshot())
                } else {
                    when (val resume = board.replayAfter(since)) {
                        is IncidentBoard.Resume.Replay ->
                            resume.messages.forEach { sendMessage(json, it) }

                        IncidentBoard.Resume.TooOld -> {
                            val snapshot = board.snapshot()
                            sendMessage(json, ServerMessage.Lagged(
                                missed = snapshot.seq - since,
                                resumeFrom = snapshot.seq,
                            ))
                            sendMessage(json, snapshot)
                        }

                        // A restart is not a lag: the client missed nothing,
                        // the server lost everything. Telling it that it fell
                        // behind by a negative number of events would be worse
                        // than useless, so it just gets the new truth.
                        IncidentBoard.Resume.Restarted ->
                            sendMessage(json, board.snapshot())
                    }
                }

                // Tell this socket its own count directly. The shared flow has
                // replay = 0 and we are not collecting yet, so a client that
                // only relied on the broadcast would show 0 viewers until
                // somebody else happened to connect.
                sendMessage(json, ServerMessage.Presence(count))
                board.publishPresence(count)

                // Collecting a SharedFlow suspends until the socket closes, so
                // this coroutine is the connection's lifetime. Ktor cancels it
                // on disconnect; there is nothing to clean up by hand.
                board.stream.collect { message -> sendMessage(json, message) }
            } catch (_: ClosedReceiveChannelException) {
                // Normal disconnect.
            } finally {
                // Not launch{}: this scope is already cancelled here.
                board.publishPresenceNow(viewers.decrementAndGet())
            }
        }
    }
}

private suspend fun WebSocketSession.sendMessage(json: Json, message: ServerMessage) {
    send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message)))
}
