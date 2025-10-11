package com.example.mycam.model

data class StreamState(
    val isStreaming: Boolean = false,
    val connectedClients: Int = 0,
    val ipAddress: String = "",
    val port: Int = 8080,
    val fps: Int = 0,
    val error: String? = null
)

