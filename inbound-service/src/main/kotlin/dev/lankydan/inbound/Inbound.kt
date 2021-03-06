package dev.lankydan.inbound

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.rsocket.kotlin.RSocket
import io.rsocket.kotlin.emitOrClose
import io.rsocket.kotlin.ktor.client.RSocketSupport
import io.rsocket.kotlin.ktor.client.rSocket
import io.rsocket.kotlin.payload.Payload
import io.rsocket.kotlin.payload.buildPayload
import io.rsocket.kotlin.payload.data
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Inbound")

fun main() {

    val client = HttpClient { //create and configure ktor client
        // imports are annoying since there many classes/functions with the same names and different packages
        install(WebSockets)
        install(RSocketSupport)
    }

    embeddedServer(Netty, port = 8000) { // create and configure ktor server and start it on localhost:9000
        // real pain to get these imports...
        install(io.ktor.server.websocket.WebSockets)
        routing {
            fireAndForget(client)
            requestResponse(client)
            requestStream(client)
            requestChannel(client)
        }
    }.start(wait = true)
}

private fun Routing.fireAndForget(client: HttpClient) {
    get("fireAndForget") {
        val rSocket: RSocket = client.rSocket(path = "fireAndForget", port = 9000) // request stream
        rSocket.fireAndForget(buildPayload { data("Hello") })

        log.info("Completed fire and forget request")

        call.respondText { "Completed" }
    }
}

private fun Routing.requestResponse(client: HttpClient) {
    get("requestResponse") {
        val rSocket: RSocket = client.rSocket(path = "requestResponse", port = 9000) // request stream
        val response: Payload = rSocket.requestResponse(buildPayload { data("Hello") })
        val text = response.data.readText()

        log.info("Received response from backend: '$text'")

        call.respondText { text }
    }
}

private fun Routing.requestStream(client: HttpClient) {
    webSocket("requestStream") { // configure route 'localhost:9000/rsocket'
        val rSocket: RSocket = client.rSocket(path = "requestStream", port = 9000) // request stream
        val stream: Flow<Payload> = rSocket.requestStream(buildPayload { data("Hello") }) // collect stream
        // Seems to apply back pressure when used in conjunction with the rsocket receiving it
        // Disconnecting the websocket also terminates the rsocket port and therefore stops the backend
        // from emitting data
        incoming.receiveAsFlow().onEach { frame ->
            log.info("Received frame: $frame")
            if (frame is Frame.Text && frame.readText() == "stop") {
                log.info("Stop requested, cancelling socket")
                // DO NOT CALL CANCEL ON THE RSOCKET OR IT WILL NOT ACTUALLY CANCEL THE OTHER SIDE OF THE SOCKET
                // it sends a close/cancel event when the web socket itself is closed
                // [DefaultWebSocketSessionImpl.runOutgoingProcessor] cancels all coroutines created within the [webSocket] scope
                // this includes the stream returned by [requestStream]
                // the flow returned from [requestStream] will then send a close if it is cancelled to the other side of the socket
                this@webSocket.close(CloseReason(CloseReason.Codes.NORMAL, "Client called 'stop'"))
            }
        }.launchIn(this) // `launchIn` is needed to start the flow in a new coroutine (basically a new thread) so that it does not
        // block the rest of the code, like it would if `collect` was called
        stream.onCompletion {
            log.info("Connection terminated")
        }.collect { payload: Payload ->
            val data = payload.data.readText()
            log.info("Received payload: '$data'")
            delay(500)
            send(Frame.Text("Received payload: '$data'"))
        }
    }
}

private fun Routing.requestChannel(client: HttpClient) {
    webSocket("requestChannel") { // configure route 'localhost:9000/rsocket'
        val rSocket: RSocket = client.rSocket(path = "requestChannel", port = 9000) // request channel
        val payloads: Flow<Payload> = incoming.receiveAsFlow().transform { frame ->
            if (frame is Frame.Text) {
                val text = frame.readText()
                log.info("Received text: $text")
                if (text == "stop") {
                    log.info("Stop requested, cancelling socket")
                    this@webSocket.close(CloseReason(CloseReason.Codes.NORMAL, "Client called 'stop'"))
                } else {
                    emitOrClose(buildPayload { data(text) })
                }
            }
        }

        val stream: Flow<Payload> = rSocket.requestChannel(buildPayload { data("Hello") }, payloads)

        stream.onCompletion {
            log.info("Connection terminated")
        }.collect { payload: Payload ->
            val data = payload.data.readText()
            log.info("Received payload: '$data'")
            delay(500)
            send(Frame.Text("Received payload: '$data'"))
        }
    }
}