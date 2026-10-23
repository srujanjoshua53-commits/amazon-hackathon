package com.firebridge.aiultra

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

sealed class RemoteCommand {
    data class Key(val code: String) : RemoteCommand()
    data class Swipe(val direction: String) : RemoteCommand()
    data class AiPrompt(val text: String) : RemoteCommand()
    data class LaunchApp(val packageName: String) : RemoteCommand()
}

class FireBridgeServer(
    private val port: Int = 8080,
    private val internalWsPort: Int = 8081,
    private val htmlContentSupplier: () -> String,
    private val onCommandReceived: (RemoteCommand) -> Unit,
    private val onClientCountChanged: (Int) -> Unit
) {

    @Volatile
    private var isRunning = false
    private var multiplexerSocket: ServerSocket? = null
    private var wsServer: InternalWebSocketServer? = null
    private val activeClientSockets = CopyOnWriteArraySet<Socket>()

    /**
     * Starts the dual HTTP and WebSocket server on port 8080.
     */
    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true

        // 1. Start the internal Java-WebSocket engine on localhost:8081
        val internalAddress = InetSocketAddress("127.0.0.1", internalWsPort)
        wsServer = InternalWebSocketServer(internalAddress)
        wsServer?.isReuseAddr = true
        wsServer?.start()

        // 2. Start the external Multiplexer ServerSocket on 0.0.0.0:8080
        thread(isDaemon = true, name = "FireBridgeMultiplexer") {
            try {
                multiplexerSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }

                while (isRunning) {
                    try {
                        val clientSocket = multiplexerSocket?.accept() ?: break
                        activeClientSockets.add(clientSocket)
                        handleIncomingConnection(clientSocket)
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Inspects the incoming connection:
     * - If standard HTTP GET, serves remote.html directly.
     * - If WebSocket handshake, pipes to the internal WebSocket engine on port 8081.
     */
    private fun handleIncomingConnection(clientSocket: Socket) {
        thread(isDaemon = true, name = "ClientDispatcher-${clientSocket.port}") {
            try {
                clientSocket.tcpNoDelay = true
                val inputStream = BufferedInputStream(clientSocket.getInputStream())
                val buffer = ByteArray(4096)
                
                // Read initial HTTP request lines
                val bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) {
                    closeSocket(clientSocket)
                    return@thread
                }

                val headerText = String(buffer, 0, bytesRead, Charsets.UTF_8)
                val isWebSocketUpgrade = headerText.contains("Upgrade: websocket", ignoreCase = true)

                if (isWebSocketUpgrade) {
                    // Pipe connection to the local WebSocket server
                    val wsSocket = Socket("127.0.0.1", internalWsPort).apply {
                        tcpNoDelay = true
                    }

                    // Forward the initial handshake bytes already buffered
                    wsSocket.getOutputStream().write(buffer, 0, bytesRead)
                    wsSocket.getOutputStream().flush()

                    // Establish bidirectional streaming pipes
                    thread(isDaemon = true, name = "Pipe-ClientToWs") {
                        pipeStream(inputStream, wsSocket.getOutputStream(), clientSocket, wsSocket)
                    }
                    thread(isDaemon = true, name = "Pipe-WsToClient") {
                        pipeStream(wsSocket.getInputStream(), clientSocket.getOutputStream(), wsSocket, clientSocket)
                    }
                } else {
                    // Standard HTTP request: Serve remote.html
                    serveHttpResponse(headerText, clientSocket.getOutputStream())
                    closeSocket(clientSocket)
                }
            } catch (e: Exception) {
                closeSocket(clientSocket)
            }
        }
    }

    /**
     * Zero-latency bidirectional streaming pipe between sockets.
     */
    private fun pipeStream(input: InputStream, output: OutputStream, sockA: Socket, sockB: Socket) {
        val transferBuffer = ByteArray(4096)
        try {
            var readLen: Int
            while (input.read(transferBuffer).also { readLen = it } != -1) {
                output.write(transferBuffer, 0, readLen)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            closeSocket(sockA)
            closeSocket(sockB)
        }
    }

    /**
     * Serves static remote.html or health ping over standard HTTP.
     */
    private fun serveHttpResponse(headerText: String, outputStream: OutputStream) {
        val firstLine = headerText.lines().firstOrNull() ?: ""
        
        if (firstLine.contains("/ping") || firstLine.contains("/health")) {
            val responseBody = "{\"status\":\"ok\",\"service\":\"FireBridge AI Ultra\"}\n"
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${responseBody.toByteArray(Charsets.UTF_8).size}\r\n" +
                    "Connection: close\r\n\r\n"
            outputStream.write(headers.toByteArray(Charsets.UTF_8))
            outputStream.write(responseBody.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            return
        }

        // Serve embedded remote.html
        val html = htmlContentSupplier()
        val htmlBytes = html.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${htmlBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Connection: close\r\n\r\n"

        outputStream.write(headers.toByteArray(Charsets.UTF_8))
        outputStream.write(htmlBytes)
        outputStream.flush()
    }

    /**
     * Broadcasts an AI response to all currently connected mobile clients.
     */
    fun broadcastAiResponse(prompt: String, response: String, model: String) {
        val json = JSONObject().apply {
            put("type", "AI_RESPONSE")
            put("prompt", prompt)
            put("response", response)
            put("model", model)
            put("timestamp", System.currentTimeMillis())
        }
        wsServer?.broadcast(json.toString())
    }

    /**
     * Broadcasts updated connected device count.
     */
    private fun broadcastClientCount(count: Int) {
        val json = JSONObject().apply {
            put("type", "CLIENT_COUNT")
            put("count", count)
        }
        wsServer?.broadcast(json.toString())
    }

    private fun closeSocket(socket: Socket?) {
        try {
            socket?.let {
                activeClientSockets.remove(it)
                if (!it.isClosed) it.close()
            }
        } catch (_: Exception) {}
    }

    /**
     * Stops the servers and cleanly cleans up all active socket connections.
     */
    @Synchronized
    fun stop() {
        isRunning = false
        try {
            multiplexerSocket?.close()
            multiplexerSocket = null
        } catch (_: Exception) {}

        try {
            wsServer?.stop(500)
            wsServer = null
        } catch (_: Exception) {}

        activeClientSockets.forEach { closeSocket(it) }
        activeClientSockets.clear()
    }

    /**
     * Internal Java-WebSocket Server handling RFC 6455 framing and message dispatching.
     */
    private inner class InternalWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {

        private val clientConnections = CopyOnWriteArraySet<WebSocket>()

        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            conn?.let {
                clientConnections.add(it)
                val count = clientConnections.size
                onClientCountChanged(count)
                broadcastClientCount(count)
            }
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            conn?.let {
                clientConnections.remove(it)
                val count = clientConnections.size
                onClientCountChanged(count)
                broadcastClientCount(count)
            }
        }

        override fun onMessage(conn: WebSocket?, message: String?) {
            if (message.isNullOrBlank()) return
            try {
                val json = JSONObject(message)
                val type = json.optString("type")

                when (type) {
                    "KEY" -> {
                        val key = json.optString("value")
                        if (key.isNotEmpty()) {
                            onCommandReceived(RemoteCommand.Key(key))
                        }
                    }
                    "SWIPE" -> {
                        val direction = json.optString("direction")
                        if (direction.isNotEmpty()) {
                            onCommandReceived(RemoteCommand.Swipe(direction))
                        }
                    }
                    "AI_PROMPT" -> {
                        val prompt = json.optString("value")
                        if (prompt.isNotEmpty()) {
                            onCommandReceived(RemoteCommand.AiPrompt(prompt))
                        }
                    }
                    "LAUNCH_APP" -> {
                        val pkg = json.optString("package")
                        if (pkg.isNotEmpty()) {
                            onCommandReceived(RemoteCommand.LaunchApp(pkg))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            ex?.printStackTrace()
        }

        override fun onStart() {
            // Server engine ready
        }
    }
}
