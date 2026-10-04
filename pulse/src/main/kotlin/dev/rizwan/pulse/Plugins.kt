package dev.rizwan.pulse

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            // Sealed-interface messages carry a "type" discriminator so the
            // TypeScript client can switch on it exhaustively.
            classDiscriminator = "type"
        })
    }

    install(CORS) {
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        val origins = System.getenv("ALLOWED_ORIGINS")?.split(",")
            ?: listOf("http://localhost:3000")
        origins.forEach { origin ->
            val url = java.net.URI(origin.trim())
            allowHost("${url.host}:${url.port}", schemes = listOf(url.scheme))
        }
    }
}

fun Application.configureSockets() {
    install(WebSockets) {
        // Ping the client regularly. Without it, a connection killed by an
        // intermediate proxy looks alive to both ends until the first write
        // fails, which can be minutes - and the UI shows stale data the whole
        // time while believing it is live.
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 64 * 1024
        masking = false
    }
}
