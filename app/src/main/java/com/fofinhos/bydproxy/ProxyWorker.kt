package com.fofinhos.bydproxy

import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class ProxyWorker(private val clientSocket: Socket) : Runnable {

    override fun run() {
        try {
            val clientInRaw = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            
            val pushbackIn = PushbackInputStream(clientInRaw, 1)
            val firstByte = pushbackIn.read()
            
            if (firstByte == 0x05) {
                handleSocks5(pushbackIn, clientOut)
            } else if (firstByte != -1) {
                pushbackIn.unread(firstByte)
                handleHttp(pushbackIn, clientOut)
            }

        } catch (e: Exception) {
            Log.e("ProxyWorker", "Error in ProxyWorker: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun handleSocks5(clientIn: InputStream, clientOut: OutputStream) {
        // 1. Handshake (Method Selection)
        val nMethods = clientIn.read()
        if (nMethods < 1) return
        val methods = ByteArray(nMethods)
        clientIn.read(methods)

        // Responde com No Authentication (0x00)
        clientOut.write(byteArrayOf(0x05, 0x00))
        clientOut.flush()

        // 2. Request
        val version = clientIn.read()
        if (version != 0x05) return
        val cmd = clientIn.read()
        clientIn.read() // Skip RSV
        val atyp = clientIn.read()

        if (cmd == 0x03) { // UDP ASSOCIATE
            when (atyp) {
                0x01 -> clientIn.read(ByteArray(4))
                0x03 -> { val length = clientIn.read(); clientIn.read(ByteArray(length)) }
                0x04 -> clientIn.read(ByteArray(16))
            }
            clientIn.read(); clientIn.read() // Skip Port
            handleUdpAssociate(clientIn, clientOut)
            return
        }

        if (cmd != 0x01) { // Apenas CONNECT suportado
            clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Command not supported
            return
        }

        val host: String
        when (atyp) {
            0x01 -> { // IPv4
                val addr = ByteArray(4)
                clientIn.read(addr)
                host = InetAddress.getByAddress(addr).hostAddress ?: ""
            }
            0x03 -> { // Domain name
                val length = clientIn.read()
                val addr = ByteArray(length)
                clientIn.read(addr)
                host = String(addr)
            }
            0x04 -> { // IPv6
                val addr = ByteArray(16)
                clientIn.read(addr)
                host = InetAddress.getByAddress(addr).hostAddress ?: ""
            }
            else -> return
        }

        val port = ((clientIn.read() and 0xFF) shl 8) or (clientIn.read() and 0xFF)

        Log.d("ProxyWorker", "SOCKS5 Connecting to $host:$port")
        val targetSocket = Socket(host, port)
        val targetIn = targetSocket.getInputStream()
        val targetOut = targetSocket.getOutputStream()

        // 3. Resposta de conexão bem-sucedida
        val response = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        clientOut.write(response)
        clientOut.flush()

        // 4. Relay bidirecional
        relay(clientIn, clientOut, targetIn, targetOut)
    }

    private fun handleHttp(clientIn: InputStream, clientOut: OutputStream) {
        val reader = BufferedReader(InputStreamReader(clientIn))
        val firstLine = reader.readLine() ?: return
        if (firstLine.isEmpty()) return

        val parts = firstLine.split(" ")
        if (parts.size < 2) return

        val method = parts[0]
        val url = parts[1]


        // Servir o arquivo PAC se solicitado
        if (url.endsWith("/proxy.pac") || url == "proxy.pac") {
            val localAddress = clientSocket.localAddress.hostAddress ?: "127.0.0.1"
            val localPort = clientSocket.localPort

            val pacContent = """
                function FindProxyForURL(url, host) {
                    if (isPlainHostName(host) ||
                        shExpMatch(host, "*.local") ||
                        shExpMatch(host, "192.168.*") ||
                        shExpMatch(host, "127.0.0.1") ||
                        shExpMatch(host, "localhost")) {
                        return "DIRECT";
                    }
                    return "SOCKS5 $localAddress:$localPort; SOCKS $localAddress:$localPort; DIRECT";
                }
            """.trimIndent()

            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/x-ns-proxy-autoconfig\r\n" +
                    "Content-Length: ${pacContent.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    pacContent

            clientOut.write(response.toByteArray())
            clientOut.flush()
            return
        }

        var host = url
        var port = 80

        if (host.contains(":")) {
            val hostParts = host.split(":")
            host = hostParts[0]
            port = hostParts[1].toInt()
        } else if (method == "CONNECT") {
            port = 443
        } else if (host.startsWith("http://")) {
            host = host.substring(7)
            if (host.contains("/")) {
                host = host.substring(0, host.indexOf("/"))
            }
        }

        Log.d("ProxyWorker", "HTTP $method $host:$port")
        val targetSocket = Socket(host, port)
        val targetIn = targetSocket.getInputStream()
        val targetOut = targetSocket.getOutputStream()

        if (method == "CONNECT") {
            val response = "HTTP/1.1 200 Connection Established\r\nProxy-Agent: BYDProxy/1.1\r\n\r\n"
            clientOut.write(response.toByteArray())
            clientOut.flush()
        } else {
            val reconstructedLine = "$method $url HTTP/1.1\r\n"
            targetOut.write(reconstructedLine.toByteArray())
            targetOut.flush()
        }

        relay(clientIn, clientOut, targetIn, targetOut)
    }

    private fun relay(clientIn: InputStream, clientOut: OutputStream, targetIn: InputStream, targetOut: OutputStream) {
        val clientToTarget = Thread { pipe(clientIn, targetOut) }
        val targetToClient = Thread { pipe(targetIn, clientOut) }

        clientToTarget.start()
        targetToClient.start()

        try {
            clientToTarget.join()
            targetToClient.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32768)
        var bytesRead: Int
        try {
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: Exception) {
            // Fim da transmissão
        } finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }

    private fun handleUdpAssociate(clientIn: InputStream, clientOut: OutputStream) {
        val serverUdpSocket = DatagramSocket(0)
        val serverUdpPort = serverUdpSocket.localPort
        val serverLocalAddr = clientSocket.localAddress

        Log.d("ProxyWorker", "UDP Associate requested. Listening on UDP port $serverUdpPort")

        // Resposta SUCCESS (0x00) com o endereço e porta UDP do servidor
        val response = ByteArrayOutputStream()
        response.write(byteArrayOf(0x05, 0x00, 0x00))
        val addrBytes = serverLocalAddr.address
        if (addrBytes.size == 4) {
            response.write(0x01) // IPv4
        } else {
            response.write(0x04) // IPv6
        }
        response.write(addrBytes)
        response.write((serverUdpPort shr 8) and 0xFF)
        response.write(serverUdpPort and 0xFF)

        clientOut.write(response.toByteArray())
        clientOut.flush()

        val clientAddr = clientSocket.inetAddress
        val learnedClientUdpPort = AtomicInteger(-1)

        val targetUdpSocket = DatagramSocket()

        val clientToTarget = Thread {
            val buffer = ByteArray(65535)
            try {
                while (!clientSocket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    serverUdpSocket.receive(packet)

                    if (packet.address != clientAddr) {
                        Log.w("ProxyWorker", "UDP packet from unknown source: ${packet.address}")
                        continue
                    }
                    learnedClientUdpPort.set(packet.port)

                    val data = packet.data
                    // SOCKS5 UDP Header: RSV(2), FRAG(1), ATYP(1), DST.ADDR, DST.PORT
                    if (data[2].toInt() != 0) continue // Ignorar fragmentação

                    val atyp = data[3].toInt()
                    var pos = 4
                    val targetHost: String
                    when (atyp) {
                        0x01 -> {
                            val addr = ByteArray(4)
                            System.arraycopy(data, pos, addr, 0, 4)
                            targetHost = InetAddress.getByAddress(addr).hostAddress ?: ""
                            pos += 4
                        }
                        0x03 -> {
                            val len = data[pos].toInt() and 0xFF
                            targetHost = String(data, pos + 1, len)
                            pos += 1 + len
                        }
                        0x04 -> {
                            val addr = ByteArray(16)
                            System.arraycopy(data, pos, addr, 0, 16)
                            targetHost = InetAddress.getByAddress(addr).hostAddress ?: ""
                            pos += 16
                        }
                        else -> continue
                    }
                    val targetPort = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2

                    val payloadLen = packet.length - pos
                    val targetPacket = DatagramPacket(data, pos, payloadLen, InetAddress.getByName(targetHost), targetPort)
                    targetUdpSocket.send(targetPacket)
                }
            } catch (e: Exception) {
                // Fim da associação
            }
        }

        val targetToClient = Thread {
            val buffer = ByteArray(65535)
            try {
                while (!clientSocket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    targetUdpSocket.receive(packet)

                    val port = learnedClientUdpPort.get()
                    if (port == -1) continue

                    val out = ByteArrayOutputStream()
                    out.write(byteArrayOf(0, 0, 0)) // RSV, FRAG
                    val senderAddr = packet.address.address
                    if (senderAddr.size == 4) {
                        out.write(0x01)
                    } else {
                        out.write(0x04)
                    }
                    out.write(senderAddr)
                    out.write((packet.port shr 8) and 0xFF)
                    out.write(packet.port and 0xFF)
                    out.write(packet.data, packet.offset, packet.length)

                    val relayPacket = DatagramPacket(out.toByteArray(), out.size(), clientAddr, port)
                    serverUdpSocket.send(relayPacket)
                }
            } catch (e: Exception) {
                // Fim da associação
            }
        }

        clientToTarget.start()
        targetToClient.start()

        try {
            // A associação UDP deve durar enquanto a conexão TCP estiver aberta
            while (clientIn.read() != -1) {}
        } catch (e: Exception) {} finally {
            serverUdpSocket.close()
            targetUdpSocket.close()
            Log.d("ProxyWorker", "UDP Associate finished")
        }
    }
}
