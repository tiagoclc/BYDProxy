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

    /**
     * Executes a shell command synchronously and returns the output.
     * MUST be called from a background thread.
     */
    fun executeShellCommand(command: String): String {
        val result = StringBuilder()
        try {
            Log.v("AdbLoopback", "Executando: $command")
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 3000)
                socket.soTimeout = 5000
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()

                // 1. Connect
                sendAdbMessage(outputStream, A_CNXN, 0x01000000, 4096, "host::\u0000")

                val connectResponse = readMessage(inputStream) ?: return "Error: No response to CNXN"
                
                if (connectResponse.command != A_CNXN) {
                    Log.w("AdbLoopback", "Esperado CNXN, recebido ${Integer.toHexString(connectResponse.command)}")
                }

                // 2. Open Shell
                val destination = "shell:$command\u0000"
                sendAdbMessage(outputStream, A_OPEN, 1, 0, destination)

                // 3. Read loop
                while (true) {
                    val msg = readMessage(inputStream) ?: break

                    if (msg.command == A_WRTE) {
                        result.append(String(msg.payload, Charsets.UTF_8))
                        // Acknowledge WRTE
                        sendAdbMessage(outputStream, A_OKAY, msg.arg1, msg.arg0, "")
                    } else if (msg.command == A_CLSE) {
                        // Enviar OKAY para fechar o stream do nosso lado também
                        sendAdbMessage(outputStream, A_CLSE, msg.arg1, msg.arg0, "")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AdbLoopback", "Erro comando '$command': ${e.message}")
            return "Error: ${e.message}"
        }
        return result.toString()
    }

    private fun readMessage(inputStream: InputStream): AdbResponse? {
        val header = ByteArray(24)
        var totalRead = 0
        while (totalRead < 24) {
            val read = inputStream.read(header, totalRead, 24 - totalRead)
            if (read == -1) return null
            totalRead += read
        }

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.getInt(0)
        val arg0 = buffer.getInt(4)
        val arg1 = buffer.getInt(8)
        val payloadLen = buffer.getInt(12)

        // Sanity check para evitar OOM se o protocolo dessincronizar
        if (payloadLen < 0 || payloadLen > 1024 * 1024 * 16) { // 16MB limit para shell output
            Log.e("AdbLoopback", "Payload muito grande ou inválido: $payloadLen")
            return null
        }

        val payload = if (payloadLen > 0) {
            val p = ByteArray(payloadLen)
            var pRead = 0
            while (pRead < payloadLen) {
                val read = inputStream.read(p, pRead, payloadLen - pRead)
                if (read == -1) break
                pRead += read
            }
            p
        } else {
            ByteArray(0)
        }

        return AdbResponse(command, arg0, arg1, payload)
    }

    private data class AdbResponse(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

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