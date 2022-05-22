package dev.lankydan.backend

import io.ktor.application.install
import io.ktor.routing.Routing
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.utils.io.CancellationException
import io.ktor.websocket.WebSockets
import io.rsocket.kotlin.RSocketRequestHandler
import io.rsocket.kotlin.emitOrClose
import io.rsocket.kotlin.payload.Payload
import io.rsocket.kotlin.payload.buildPayload
import io.rsocket.kotlin.payload.data
import io.rsocket.kotlin.transport.ktor.server.RSocketSupport
import io.rsocket.kotlin.transport.ktor.server.rSocket
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Backend")

fun main() {
    embeddedServer(Netty, port = 9000) { // create and configure ktor server and start it on localhost:9000
        install(WebSockets)
        // web sockets must be installed first or it throws an error
        install(RSocketSupport)
        routing {
            requestSteam()
            requestChannel()
            requestResponse()
            fireAndForget()
        }
    }.start(wait = true)
}

// seems like this doesn't cancel properly, since after shutting down the inbound application, the backend app threw a connection error
// other people experiencing the same error (due to logging?) - https://github.com/rsocket/rsocket-kotlin/issues/211
// cancelling the [coroutineContext] manually fixes the error
fun Routing.requestSteam() {
    rSocket("requestStream") { // configure route 'localhost:9000/rsocket'
        RSocketRequestHandler { // create simple request handler
            requestStream { request: Payload -> // register request/stream handler
                log.info("Received request (stream): ${request.data.readText()}")
                flow {
                    try {
                        var i = 0
                        while (true) {
                            log.info("Emitting $i")
                            emitOrClose(buildPayload { data("data: $i") })
                            i += 1
                            delay(200)
                        }
                    } catch (e: Exception) {
                        log.info("Cancellation error?", e)
                    } finally {
                        log.info("Broken out of while loop")
                    }
                }/*.cancellable()*/.onCompletion {
                    log.info("Completed", it)
                    currentCoroutineContext().cancel()
//                    coroutineContext.cancel()
                }
            }
        }
    }
}

fun Routing.requestChannel() {
    rSocket("requestChannel") { // configure route 'localhost:9000/rsocket'
        RSocketRequestHandler { // create simple request handler
            requestChannel { request: Payload, payloads: Flow<Payload> ->
                var prefix = request.data.readText()
                log.info("Received request: '$prefix'")
                payloads.onEach { payload ->
                    prefix = payload.data.readText()
                    log.info("Received extra payload, changed emitted values to include prefix: '$prefix")
                }.launchIn(this)
                flow {
//                channelFlow<Payload> {
                    try {
                        var i = 0
                        while (true) {
                            val data = "data: ${if (prefix.isBlank()) "" else "($prefix) "}$i"
                            log.info("Emitting $data")
                            emitOrClose(buildPayload { data(data) })
                            i += 1
                            delay(200)
                        }
                    } catch (e: Exception) {
                        log.info("Cancellation error?", e)
                    } finally {
                        log.info("Broken out of while loop")
                    }
                }.onCompletion {
                    log.info("Completed", it)
                    currentCoroutineContext().cancel()
                }
            }
        }
    }
}

fun Routing.requestResponse() {
    rSocket("requestResponse") {
        RSocketRequestHandler {
            requestResponse { request: Payload ->
                val text = request.data.readText()
                log.info("Received request (request/response): '$text' ")
                delay(200)
                buildPayload { data("Received: '$text' - Returning: 'some data'") }
            }
        }
    }
}

fun Routing.fireAndForget() {
    rSocket("fireAndForget") {
        RSocketRequestHandler {
            fireAndForget { request: Payload ->
                val text = request.data.readText()
                log.info("Received request (fire and forget): '$text' ")
            }
        }
    }
}
