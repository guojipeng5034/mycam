package com.example.mycam.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

// Minimal single-client RTSP over TCP (interleaved RTP) video-only server.
class RtspServer(private val port: Int = 8554, private val path: String = "/live") {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientOutput: OutputStream? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.getAndSet(true)) return
        scope.launch {
            serverSocket = ServerSocket(port)
            while (running.get()) {
                val s = serverSocket?.accept() ?: break
                clientSocket = s
                clientOutput = s.getOutputStream()
                handleClient(s)
            }
        }
    }

    fun stop() {
        running.set(false)
        try { clientSocket?.close() } catch (_: Throwable) {}
        try { serverSocket?.close() } catch (_: Throwable) {}
        scope.cancel()
    }

    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null

    fun setCodecConfig(spsBytes: ByteArray?, ppsBytes: ByteArray?) {
        sps = spsBytes
        pps = ppsBytes
    }

    fun sendH264(nal: ByteArray) {
        // For brevity, this is a placeholder: proper RTP packetization is needed in production.
        // Here we do nothing until full packetization is implemented.
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var line: String?
            var cseq = "1"
            while (reader.readLine().also { line = it } != null) {
                val req = line ?: break
                if (req.startsWith("OPTIONS")) {
                    respond(socket, cseq, 200, "Public: DESCRIBE, SETUP, TEARDOWN, PLAY\r\n")
                } else if (req.startsWith("DESCRIBE")) {
                    val spsLocal = sps
                    val ppsLocal = pps
                    val sdp = buildSdp(spsLocal, ppsLocal)
                    respond(socket, cseq, 200, "Content-Type: application/sdp\r\nContent-Length: ${sdp.toByteArray().size}\r\n\r\n$sdp")
                } else if (req.startsWith("SETUP")) {
                    respond(socket, cseq, 200, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nSession: 12345678\r\n")
                } else if (req.startsWith("PLAY")) {
                    respond(socket, cseq, 200, "Range: npt=0.000-\r\nSession: 12345678\r\n")
                } else if (req.startsWith("TEARDOWN")) {
                    respond(socket, cseq, 200, "Session: 12345678\r\n")
                    break
                } else if (req.startsWith("CSeq:")) {
                    cseq = req.substringAfter(":").trim()
                } else if (req.isEmpty()) {
                    // end of headers
                }
            }
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun respond(socket: Socket, cseq: String, code: Int, extraHeaders: String) {
        val status = when (code) {
            200 -> "200 OK"
            else -> "$code Error"
        }
        val resp = "RTSP/1.0 $status\r\nCSeq: $cseq\r\n$extraHeaders\r\n"
        socket.getOutputStream().write(resp.toByteArray())
        socket.getOutputStream().flush()
    }

    private fun buildSdp(sps: ByteArray?, pps: ByteArray?): String {
        // Very simplified SDP
        val sprop = if (sps != null && pps != null) {
            val b64sps = android.util.Base64.encodeToString(sps, android.util.Base64.NO_WRAP)
            val b64pps = android.util.Base64.encodeToString(pps, android.util.Base64.NO_WRAP)
            "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=$b64sps,$b64pps\r\n"
        } else ""
        return """
            v=0
            o=- 0 0 IN IP4 0.0.0.0
            s=MyCam
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            $sprop
        """.trimIndent()
    }
}


