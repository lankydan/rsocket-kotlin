package dev.lankydan.inbound

import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.features.websocket.WebSockets
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.readText
import io.ktor.http.cio.websocket.send
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.webSocket
import io.rsocket.kotlin.RSocket
import io.rsocket.kotlin.emitOrClose
import io.rsocket.kotlin.payload.Payload
import io.rsocket.kotlin.payload.buildPayload
import io.rsocket.kotlin.payload.data
import io.rsocket.kotlin.transport.ktor.client.RSocketSupport
import io.rsocket.kotlin.transport.ktor.client.rSocket
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

@InternalCoroutinesApi
suspend fun main() {

    val client = HttpClient { //create and configure ktor client
        // imports are annoying since there many classes/functions with the same names and different packages
        install(WebSockets)
        install(RSocketSupport)
    }

//    val rSocket: RSocket = client.rSocket(path = "ping", port = 9000) // request stream
//    val stream: Flow<Payload> = rSocket.requestStream(buildPayload { data("Hello") }) // collect stream
//    stream.collect { payload: Payload ->
//        println("Received payload: '${payload.data.readText()}'")
//    }

    embeddedServer(Netty, port = 8000) { // create and configure ktor server and start it on localhost:9000
        install(io.ktor.websocket.WebSockets)
        routing {
            webSocket("ping") { // configure route 'localhost:9000/rsocket'
                val rSocket: RSocket = client.rSocket(path = "ping", port = 9000) // request stream
                val stream: Flow<Payload> = rSocket.requestStream(buildPayload { data("Hello") }) // collect stream
                // Seems to apply back pressure when used in conjunction with the rsocket receiving it
                // Disconnecting the websocket also terminates the rsocket port and therefore stops the backend
                // from emitting data
                incoming.receiveAsFlow().onEach { frame ->
                    println("Received frame: $frame")
                    if (frame is Frame.Text && frame.readText() == "stop") {
                        println("Stop requested, cancelling socket")
                        // DO NOT CALL CANCEL ON THE RSOCKET OR IT WILL NOT ACTUALLY CANCEL THE OTHER SIDE OF THE SOCKET
                        // it sends a close/cancel event when the web socket itself is closed
                        // [DefaultWebSocketSessionImpl.runOutgoingProcessor] cancels all coroutines created within the [webSocket] scope
                        // this includes the stream returned by [requestStream]
                        // the flow returned from [requestStream] will then send a close if it is cancelled to the other side of the socket
//                        rSocket.cancel()
//                        stream.cancellable()
                        close(CloseReason(CloseReason.Codes.NORMAL, "Client called 'stop'"))
                    }
                }.launchIn(this)
                stream.onCompletion { println("On completion") }.collect { payload: Payload ->
                    val data = payload.data.readText()
                    println("Received payload: '$data'")
                    delay(200)
                    send("Received payload: '$data'")
                }
//                rSocket.requestStream(buildPayload { data("Hello") }).onEach { payload ->
//                    val data = payload.data.readText()
//                    println("Received payload: '$data'")
//                    Thread.sleep(500)
//                    send("Received payload: '$data'")
//                }
            }
        }
//        routing {
//            webSocket("ping") {
//                val rSocket: RSocket = client.rSocket(path = "ping", port = 9000)
//
//                var stopped = false
//
//                val outbound = flow<Payload> {
//                    incoming.receiveAsFlow().onEach { frame ->
//                        println("Received frame: $frame")
//                        if (frame is Frame.Text && frame.readText() == "stop") {
//                            println("Stop requested, cancelling socket")
////                            emitOrClose(buildPayload { data("stop") })
////                            rSocket.cancel()
//                            this@webSocket.close(CloseReason(CloseReason.Codes.NORMAL, "Client called 'stop'"))
//                        }
//                    }.launchIn(this@webSocket)
//                }
//
////                val outbound = flow {
////                    emitOrClose(buildPayload { data("stop") })
////                }
//
//                val stream: Flow<Payload> = rSocket.requestChannel(buildPayload { data("Hello") }, outbound)
//                stream.onCompletion { println("On completion") }.collect { payload: Payload ->
//                    val data = payload.data.readText()
//                    println("Received payload: '$data'")
//                    delay(200)
//                    send("Received payload: '$data'")
//                }
//            }
//        }
    }.start(wait = true)
}

