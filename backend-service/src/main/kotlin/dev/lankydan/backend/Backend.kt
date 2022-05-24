package dev.lankydan.backend

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.utils.io.CancellationException
import io.rsocket.kotlin.RSocketRequestHandler
import io.rsocket.kotlin.emitOrClose
import io.rsocket.kotlin.ktor.server.RSocketSupport
import io.rsocket.kotlin.ktor.server.rSocket
import io.rsocket.kotlin.payload.Payload
import io.rsocket.kotlin.payload.buildPayload
import io.rsocket.kotlin.payload.data
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
            fireAndForget()
            requestResponse()
            requestStream()
            requestChannel()
        }
    }.start(wait = true)
}

private fun Routing.fireAndForget() {
    rSocket("fireAndForget") {
        RSocketRequestHandler {
            fireAndForget { request: Payload ->
                val text = request.data.readText()
                log.info("Received request (fire and forget): '$text' ")
            }
        }
    }
}

private fun Routing.requestResponse() {
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

// seems like this doesn't cancel properly, since after shutting down the inbound application, the backend app threw a connection error
// other people experiencing the same error (due to logging?) - https://github.com/rsocket/rsocket-kotlin/issues/211
// cancelling the [coroutineContext] manually fixes the error
private fun Routing.requestStream() {
    rSocket("requestStream") { // configure route 'localhost:9000/rsocket'
        RSocketRequestHandler { // create simple request handler
            requestStream { request: Payload -> // register request/stream handler

                val prefix = Prefix(request.data.readText())

                log.info("Received request (stream): '${prefix.value}'")

                flow {
                    emitDataContinuously(prefix)
                }.onCompletion { throwable ->
                    if (throwable is CancellationException) {
                        log.info("Connection terminated")
                        currentCoroutineContext().cancel()
                    }
                }
            }
        }
    }
}

private fun Routing.requestChannel() {
    rSocket("requestChannel") { // configure route 'localhost:9000/rsocket'
        RSocketRequestHandler { // create simple request handler
            requestChannel { request: Payload, payloads: Flow<Payload> ->

                val prefix = Prefix(request.data.readText())

                log.info("Received request (channel): '${prefix.value}'")

                payloads.onEach { payload ->
                    prefix.value = payload.data.readText()
                    log.info("Received extra payload, changed emitted values to include prefix: '${prefix.value}'")
                }.launchIn(this) // `launchIn` is needed to start the flow in a new coroutine (basically a new thread) so that it does not
                // block the rest of the code, like it would if `collect` was called

                flow {
                    emitDataContinuously(prefix)
                }.onCompletion { throwable ->
                    if (throwable is CancellationException) {
                        log.info("Connection terminated")
                        currentCoroutineContext().cancel()
                    }
                }
            }
        }
    }
}

private suspend fun FlowCollector<Payload>.emitDataContinuously(prefix: Prefix) {
    var i = 0
    while (true) {
        val data = "data: ${if (prefix.value.isBlank()) "" else "(${prefix.value}) "}$i"
        log.info("Emitting $data")
        emitOrClose(buildPayload { data(data) })
        i += 1
        delay(200)
    }
}

private class Prefix(var value: String)
