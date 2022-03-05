package dev.lankydan.backend

import io.ktor.application.install
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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
import kotlinx.coroutines.flow.cancel
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

fun main() {
    embeddedServer(Netty, port = 9000) { // create and configure ktor server and start it on localhost:9000
        install(WebSockets)
        // web sockets must be installed first or it throws an error
        install(RSocketSupport)
        routing {
            rSocket("ping") { // configure route 'localhost:9000/rsocket'
                RSocketRequestHandler { // create simple request handler
                    requestStream { request: Payload -> // register request/stream handler
                        println("Received request: '${request.data.readText()}'")
                        flow {
                            try {
//                            repeat(10) { i -> emit(buildPayload { data("data: $i") }) }
                                var i = 0
                                repeat (100) {
//                                while (true) {
                                    println("Emitting $i")
                                    emitOrClose(buildPayload { data("data: $i") })
                                    i += 1
                                }
                            } finally {
                                println("done?")
                            }
                        }.cancellable().onCompletion { println("On completion") }
                    }
                }
            }
//            rSocket("ping") { // configure route 'localhost:9000/rsocket'
//                RSocketRequestHandler { // create simple request handler
//                    requestChannel { request, payloads ->
//                        println("Received request: '${request.data.readText()}'")
////                        var stopped = false
////                        payloads.collect { payload ->
////                            println("Received extra payload from client")
////                            if (payload.data.readText() == "stop") {
////                                println("Received stop request")
////                                stopped = true
////                            }
////                        }
//                        flow {
//                            try {
//                                var i = 0
//                                while (true) {
////                                    if (stopped) {
////                                        println("STOPPED -> CANCELLING")
////                                        currentCoroutineContext().cancel()
////                                    }
//                                    println("Emitting $i")
//                                    emitOrClose(buildPayload { data("data: $i") })
//                                    i += 1
//                                }
//                            } finally {
//                                println("done?")
//                            }
//                            // rsocket is handling a cancel
//                            // why does it work with the requestChannel and not the requestStream? is this a bug?
//                            // what is triggering the cancel, probably just exitting the web socket method on the client side is closing it?
//                            // seems this way [recieveOrCancel] called in requestChannel/requestResponse
//                        }.cancellable().onCompletion { cause ->
//                            println("On completion, e: $cause")
//                        }
//                    }
//                }
//            }
        }
    }.start(wait = true)
}

