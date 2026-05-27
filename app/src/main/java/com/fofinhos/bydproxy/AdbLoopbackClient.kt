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

    fun executeShellCommand(command: String) {
        Thread {
            var socket: Socket? = null
            try {
                Log.d("AdbLoopback", "Tentando conectar ao ADB (127.0.0.1:5555)...")
                socket = Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 5000)
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()

                sendAdbMessage(outputStream, A_CNXN, 0x01000000, 4096, "host::\u0000")

                val header = ByteArray(24)
                socket.soTimeout = 5000
                if (inputStream.read(header) == -1) {
                    Log.e("AdbLoopback", "Falha ao ler cabeçalho de resposta")
                    return@Thread
                }

                Log.d("AdbLoopback", "Conexão estabelecida! Enviando comando: $command")
                val destination = "shell:$command\u0000"
                sendAdbMessage(outputStream, A_OPEN, 1, 0, destination)
                Log.d("AdbLoopback", "Comando enviado com sucesso!")

            } catch (e: Exception) {
                Log.e("AdbLoopback", "Erro na conexão ADB: ${e.message}. A porta 5555 está aberta?")
            } finally {
                try { socket?.close() } catch (e: Exception) {}
            }
        }.start()
    }

    private fun sendAdbMessage(output: OutputStream, command: Int, arg0: Int, arg1: Int, payload: String) {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
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