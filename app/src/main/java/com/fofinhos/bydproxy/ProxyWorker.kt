package com.fofinhos.bydproxy

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

class ProxyWorker(private val clientSocket: Socket) : Runnable {

    override fun run() {
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // Usamos BufferedReader para ler a primeira linha da requisição HTTP de forma limpa
            val reader = BufferedReader(InputStreamReader(clientIn))
            val firstLine = reader.readLine() ?: return

            if (firstLine.isEmpty()) return

            // Divide a linha por espaços (Ex: "CONNECT google.com:443 HTTP/1.1")
            val parts = firstLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0] // CONNECT, GET, POST, etc.
            val url = parts[1]    // Ex: google.com:443 ou http://google.com/

            var host = url
            var port = 80

            // Trata requisições HTTPS (método CONNECT) ou HTTP com porta explícita
            if (host.contains(":")) {
                val hostParts = host.split(":")
                host = hostParts[0]
                port = hostParts[1].toInt()
            } else if (method == "CONNECT") {
                port = 443
            } else if (host.startsWith("http://")) {
                // Se for um GET HTTP normal com a URL completa, limpa o prefixo
                host = host.substring(7)
                if (host.contains("/")) {
                    host = host.substring(0, host.indexOf("/"))
                }
            }

            // Abre a conexão socket de saída em User Space em direção à internet do BYD
            val targetSocket = Socket(host, port)
            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            if (method == "CONNECT") {
                // Se for HTTPS, responde ao cliente que o túnel TCP local foi estabelecido
                val response = "HTTP/1.1 200 Connection Established\r\nProxy-Agent: BYDProxy/1.1\r\n\r\n"
                clientOut.write(response.toByteArray())
                clientOut.flush()
            } else {
                // Se for HTTP normal, reconstrói e encaminha a primeira linha para o servidor alvo
                val reconstructedLine = "$method $url HTTP/1.1\r\n"
                targetOut.write(reconstructedLine.toByteArray())
                targetOut.flush()
            }

            // Inicia o pipe bidirecional assíncrono entre o dispositivo e o servidor web
            val clientToTarget = Thread { pipe(clientIn, targetOut) }
            val targetToClient = Thread { pipe(targetIn, clientOut) }

            clientToTarget.start()
            targetToClient.start()

            clientToTarget.join()
            targetToClient.join()

        } catch (e: Exception) {
            // Silencia exceções de conexões abortadas pelos clientes
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16384)
        var bytesRead: Int
        try {
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: Exception) {
            // Fim da transmissão ou socket fechado de forma abrupta
        } finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }
}