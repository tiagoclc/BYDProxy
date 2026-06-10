package com.fofinhos.bydproxy

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbLoopbackClient {

    private val A_CNXN = 0x4e584e43
    private val A_OPEN = 0x4e45504f
    private val A_OKAY = 0x59414b4f
    private val A_WRTE = 0x45545257
    private val A_CLSE = 0x45534c43
    private val A_AUTH = 0x48545541

    /**
     * Executes a shell command synchronously and returns the output.
     * MUST be called from a background thread.
     */
    fun executeShellCommand(command: String): String {
        var socket: Socket? = null
        val result = StringBuilder()
        try {
            Log.d("AdbLoopback", "Tentando conectar ao ADB (127.0.0.1:5555)...")
            socket = Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 5000)
            val outputStream = socket.getOutputStream()
            val inputStream = socket.getInputStream()
            socket.soTimeout = 5000

            // 1. Connect
            sendAdbMessage(outputStream, A_CNXN, 0x01000000, 4096, "host::\u0000")

            val header = ByteArray(24)
            readExactly(inputStream, header, 24)

            val cnxnBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cnxnCmd = cnxnBuffer.getInt(0)
            val cnxnPayloadLen = cnxnBuffer.getInt(12)

            if (cnxnCmd == A_AUTH) {
                Log.w("AdbLoopback", "ADB requer autenticação. Comandos podem falhar.")
            }

            // Consumir payload do CNXN (ou AUTH se houver)
            if (cnxnPayloadLen > 0) {
                if (cnxnPayloadLen > 1024 * 1024) {
                    throw Exception("Payload de conexão muito grande: $cnxnPayloadLen")
                }
                val p = ByteArray(cnxnPayloadLen)
                readExactly(inputStream, p, cnxnPayloadLen)
                Log.d("AdbLoopback", "Handshake inicial recebido (cmd=$cnxnCmd, len=$cnxnPayloadLen)")
            }

            // 2. Open Shell
            Log.d("AdbLoopback", "Conexão estabelecida! Enviando comando: $command")
            val destination = "shell:$command\u0000"
            sendAdbMessage(outputStream, A_OPEN, 1, 0, destination)

            // 3. Read loop
            while (true) {
                try {
                    readExactly(inputStream, header, 24)
                } catch (_: Exception) {
                    break // Stream closed or timeout
                }

                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val responseCmd = buffer.getInt(0)
                val remoteId = buffer.getInt(4)
                val localId = buffer.getInt(8)
                val payloadLen = buffer.getInt(12)

                if (payloadLen > 0) {
                    if (payloadLen > 1024 * 1024) {
                        Log.e("AdbLoopback", "Payload muito grande ignorado: $payloadLen")
                        // Tentar consumir para não quebrar o stream, ou apenas fechar
                        throw Exception("Payload muito grande: $payloadLen")
                    }
                    val payload = ByteArray(payloadLen)
                    readExactly(inputStream, payload, payloadLen)

                    if (responseCmd == A_WRTE) {
                        result.append(String(payload, Charsets.UTF_8))
                        // Acknowledge WRTE
                        sendAdbMessage(outputStream, A_OKAY, localId, remoteId, "")
                    }
                }

                if (responseCmd == A_CLSE) {
                    break
                }
            }

        } catch (e: Exception) {
            Log.e("AdbLoopback", "Erro na conexão ADB: ${e.message}")
            return "Error: ${e.message}"
        } finally {
            try { socket?.close() } catch (e: Exception) {}
        }
        return result.toString()
    }

    private fun readExactly(inputStream: InputStream, buffer: ByteArray, length: Int) {
        var totalRead = 0
        while (totalRead < length) {
            val read = inputStream.read(buffer, totalRead, length - totalRead)
            if (read == -1) throw Exception("Stream closed")
            totalRead += read
        }
    }

    private fun sendAdbMessage(output: OutputStream, command: Int, arg0: Int, arg1: Int, payload: String) {
        val payloadBytes = if (payload.isEmpty()) ByteArray(0) else payload.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(24 + payloadBytes.size).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(payloadBytes.size)
        buffer.putInt(calculateChecksum(payloadBytes))
        buffer.putInt(command xor -0x1)

        if (payloadBytes.isNotEmpty()) {
            buffer.put(payloadBytes)
        }

        output.write(buffer.array())
        output.flush()
    }

    private fun calculateChecksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) {
            sum += b.toInt() and 0xFF
        }
        return sum
    }
}