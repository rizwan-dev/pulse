package dev.rizwan.pulse

import dev.rizwan.pulse.model.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlin.test.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * End-to-end over a real WebSocket, through the real routing and serialisation.
 *
 * These are the tests that would catch a broken contract: a renamed field, a
 * missing discriminator, a message that never arrives.
 */
class LiveFeedTest {

    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    private suspend fun DefaultClientWebSocketSession.nextMessage(): ServerMessage =
        withTimeout(5_000) {
            val frame = incoming.receive() as Frame.Text
            json.decodeFromString(ServerMessage.serializer(), frame.readText())
        }

    @Test
    fun `the browser origin the web client runs on is actually allowed`() = testApplication {
        application { module() }

        // The CORS allowlist is built by string interpolation, and an escaping
        // slip once made it register the literal text "${url.host}:${url.port}"
        // - a host no browser can ever match. Every unit test still passed,
        // because none of them sent an Origin header. The web client simply
        // could not reach the API. This asserts the header that proves it can.
        val response = client.post("/api/incidents") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"title":"From the browser","severity":"INFO","source":"web"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(
            "http://localhost:3000",
            response.headers[HttpHeaders.AccessControlAllowOrigin],
            "without this header the browser discards the response",
        )
    }

    @Test
    fun `a new client is sent the current board on connect`() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        client.post("/api/incidents") {
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Already happening","severity":"CRITICAL"}""")
        }

        client.webSocket("/ws") {
            val first = nextMessage()
            assertIs<ServerMessage.Snapshot>(first, "the first frame must be a snapshot")
            assertEquals(1, first.incidents.size)
            assertEquals("Already happening", first.incidents.first().title)
        }
    }

    @Test
    fun `an incident raised over HTTP reaches a connected socket`() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            assertIs<ServerMessage.Snapshot>(nextMessage())

            client.post("/api/incidents") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Pager going off","severity":"CRITICAL"}""")
            }

            // Presence may arrive first; take messages until the one we want.
            var raised: ServerMessage.Raised? = null
            while (raised == null) {
                when (val message = nextMessage()) {
                    is ServerMessage.Raised -> raised = message
                    else -> {} // presence and friends are not what this test is about
                }
            }

            assertEquals("Pager going off", raised.incident.title)
            assertEquals(Severity.CRITICAL, raised.incident.severity)
        }
    }

    @Test
    fun `reconnecting with a cursor replays the gap instead of the whole board`() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        // Raise one, note where we got to, then raise two more while "offline".
        client.post("/api/incidents") {
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Before the drop"}""")
        }

        val cursor = client.webSocketReturning("/ws") {
            val snapshot = nextMessage() as ServerMessage.Snapshot
            snapshot.seq
        }

        repeat(2) { i ->
            client.post("/api/incidents") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"While disconnected $i"}""")
            }
        }

        client.webSocket("/ws?since=$cursor") {
            val first = nextMessage()
            assertIs<ServerMessage.Raised>(
                first,
                "a resuming client gets the missed events, not another full snapshot",
            )
            assertEquals("While disconnected 0", first.incident.title)
        }
    }

    @Test
    fun `a blank title is rejected`() = testApplication {
        application { module() }

        val response = client.post("/api/incidents") {
            contentType(ContentType.Application.Json)
            setBody("""{"title":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `acknowledging an unknown incident is a 404`() = testApplication {
        application { module() }

        val response = client.post("/api/incidents/nope/ack") {
            contentType(ContentType.Application.Json)
            setBody("""{"by":"rizwan"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}

/**
 * Ktor's own webSocket{} returns Unit, so a value computed inside the session
 * cannot escape. This captures one. It is deliberately NOT named webSocket:
 * an extension with the same signature shadows Ktor's and recurses into
 * itself, which shows up as a StackOverflowError rather than anything that
 * points at the cause.
 */
private suspend fun <T> io.ktor.client.HttpClient.webSocketReturning(
    path: String,
    block: suspend DefaultClientWebSocketSession.() -> T,
): T {
    var result: T? = null
    this.webSocket(path) { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
