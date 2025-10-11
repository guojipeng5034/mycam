package com.example.mycam.server

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MjpegHttpServer(private val port: Int = 8080) {
    private var server: ApplicationEngine? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Latest JPEG frame broadcast channel
    data class Frame(val data: ByteArray, val length: Int)
    private val frameChannel = Channel<Frame>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    suspend fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port) {
            routing {
                get("/") {
                    call.respondText(
                        text = """
                            <html>
                              <body>
                                <img src=\"/mjpeg\" />
                              </body>
                            </html>
                        """.trimIndent(),
                        contentType = ContentType.Text.Html
                    )
                }
                get("/mjpeg") {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                    call.respondBytesWriter(
                        contentType = ContentType.parse("multipart/x-mixed-replace; boundary=frame")
                    ) {
                        val boundary = "--frame\r\n"
                        while (coroutineContext.isActive) {
                            val frame = frameChannel.receive()
                            writeStringUtf8(boundary)
                            writeStringUtf8("Content-Type: image/jpeg\r\n")
                            writeStringUtf8("Content-Length: ${frame.length}\r\n\r\n")
                            writeFully(frame.data, 0, frame.length)
                            writeStringUtf8("\r\n")
                            flush()
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    suspend fun stop() {
        val s = server ?: return
        server = null
        s.stop()
    }

    fun offerFrame(bytes: ByteArray, length: Int) {
        frameChannel.trySend(Frame(bytes, length))
    }
}


