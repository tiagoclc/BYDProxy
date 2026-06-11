package com.fofinhos.bydproxy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProxyService : Service() {

    private var pacServerSocket: ServerSocket? = null
    private var executorService: ExecutorService? = null
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    private val adbExecutor = AdbShellExecutor(this)
    private var isRunning = false

    private val SINGBOX_PORT = 8888
    private val SINGBOX_HTTP_PORT = 8887
    private val PAC_PORT = 8880
    var hostIp = "192.168.43.1" // IP base por defeito do Hotspot Android

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ProxyService", "Service onCreate")
        startForegroundServiceNotification()

        executorService = Executors.newFixedThreadPool(4)

        executorService?.execute {
            try {
                adbExecutor.executeSync("dumpsys deviceidle whitelist +com.fofinhos.bydproxy")

                // Forçar sincronização de relógio para evitar rejeição de certificados TLS
                adbExecutor.executeSync("settings put global auto_time 1")
                adbExecutor.executeSync("settings put global auto_time_zone 1")
            } catch (e: Exception) {
                Log.e("ProxyService", "Erro ao executar comandos ADB iniciais", e)
            }
        }

        startProxySystem()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun cleanResources() {
        val cleanCmd = """
            # Encontra e mata qualquer processo chamado sing-box ou singbox_byd
            for pid in ${'$'}(ps -A | grep -E 'sing-box|singbox_byd|libsingbox' | awk '{print ${'$'}2}'); do
                kill -9 ${'$'}pid 2>/dev/null
            done
            
            pkill -9 -f singbox_byd 2>/dev/null
        """.trimIndent()

        adbExecutor.executeSync(cleanCmd)
    }

    private fun startProxySystem() {
        if (isRunning) return
        isRunning = true

        cleanupExecutor.execute {
            Log.d("ProxyService", "Limpando recursos e portas anteriores...")
            cleanResources()

            Thread.sleep(1500)

            executorService?.execute {
                startSingBoxEngine()
            }

            executorService?.execute {
                startPacServer()
            }
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun startSingBoxEngine() {
        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val singBoxBin = File(nativeDir, "libsingbox.so")
            val logFile = "/data/local/tmp/singbox.log"

            Log.i("ProxyService", "Iniciando motor sing-box...")

            // Identificar IP dinamicamente
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val name = networkInterface.name.lowercase()
                    if (name.contains("wlan") || name.contains("ap") || name.contains("p2p")) {
                        val addresses = networkInterface.inetAddresses
                        while (addresses.hasMoreElements()) {
                            val addr = addresses.nextElement()
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                val detectedIp = addr.hostAddress
                                if (!detectedIp.isNullOrEmpty() && detectedIp != "0.0.0.0") {
                                    hostIp = detectedIp
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ProxyService", "Erro ao detetar IP dinâmico", e)
            }
            Log.i("ProxyService", "IP Mapeado para o Servidor PAC: $hostIp")

            val tmpBin = "/data/local/tmp/singbox_byd"
            val tmpConfig = "/data/local/tmp/singbox_config.json"

            // Lógica de cópia
            val tamanhoOriginal = singBoxBin.length()
            val sizeOutput = adbExecutor.executeSync("wc -c $tmpBin 2>/dev/null").output.trim()
            val tamanhoNoDestino = sizeOutput.split(Regex("\\s+"))[0].toLongOrNull() ?: 0L

            if (tamanhoNoDestino != tamanhoOriginal) {
                Log.w("ProxyService", "Binário em /tmp ausente ou incompleto. A iniciar cópia...")
                adbExecutor.executeSync("rm -f $tmpBin")
                adbExecutor.executeSync("cat ${singBoxBin.absolutePath} > $tmpBin")
                adbExecutor.executeSync("chmod 777 $tmpBin")
            }

            // Configuração
            val config = """
            {
              "log": { 
                "level": "error", 
                "timestamp": true, 
                "output": "$logFile" 
              },
              "dns": {
                "servers": [
                  { "type": "udp", "tag": "google-dns", "server": "8.8.8.8" },
                  { "type": "udp", "tag": "cloudflare-dns", "server": "1.1.1.1" }
                ],
                "final": "google-dns",
                "strategy": "ipv4_only"
              },
              "inbounds": [
                {
                  "type": "socks",
                  "tag": "socks-in",
                  "listen": "0.0.0.0",
                  "listen_port": $SINGBOX_PORT,
                  "sniff": false
                },
                {
                  "type": "http",
                  "tag": "http-in",
                  "listen": "0.0.0.0",
                  "listen_port": $SINGBOX_HTTP_PORT,
                  "sniff": false
                }
              ],
              "outbounds": [
                { "type": "direct", "tag": "direct-out" }
              ],
              "route": {
                "auto_detect_interface": true,
                "final": "direct-out"
              }
            }
            """.trimIndent()

            val base64Config = android.util.Base64.encodeToString(config.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            adbExecutor.executeSync("printf '%s' '$base64Config' | base64 -d > $tmpConfig")
            adbExecutor.executeSync("chmod 666 $tmpConfig")

            val cmdTmp = "export HOME=/data/local/tmp; nohup $tmpBin run -c $tmpConfig > $logFile 2>&1 &"
            adbExecutor.executeSync(cmdTmp)

            // Monitorização
            executorService?.execute {
                var lastStateOnline = false
                var consecutiveFailures = 0
                
                Log.d("ProxyService", "Iniciando monitorização do Sing-Box...")

                while (isRunning) {
                    val isAlive = checkSingBoxStatus()
                    
                    if (isAlive) {
                        if (!lastStateOnline) {
                            Log.i("ProxyService", "Sing-Box está ONLINE e operacional.")
                            lastStateOnline = true
                        }
                        consecutiveFailures = 0
                        Thread.sleep(30000)
                    } else {
                        consecutiveFailures++
                        
                        if (!lastStateOnline) {
                            Log.v("ProxyService", "Aguardando motor subir ($consecutiveFailures/30)...")
                            if (consecutiveFailures >= 30) {
                                Log.e("ProxyService", "Falha crítica: Sing-Box não iniciou após 30 tentativas.")
                                consecutiveFailures = 0 // Reinicia contador para tentar novamente
                            }
                            Thread.sleep(2000)
                        } else {
                            if (consecutiveFailures >= 3) {
                                Log.w("ProxyService", "Sing-Box inativo. Reiniciando...")
                                cleanResources()
                                Thread.sleep(3000)
                                adbExecutor.executeSync(cmdTmp)
                                consecutiveFailures = 0
                                lastStateOnline = false
                            }
                            Thread.sleep(5000)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("ProxyService", "Falha no motor sing-box", e)
        }
    }

    private fun checkSingBoxStatus(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", SINGBOX_PORT), 1500)
                val out = socket.getOutputStream()
                val ins = socket.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val response = ByteArray(2)
                val read = ins.read(response)
                read == 2 && response[0] == 0x05.toByte() && response[1] == 0x00.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun startPacServer() {
        Log.d("ProxyService", "Iniciando servidor PAC na porta $PAC_PORT")
        try {
            pacServerSocket = ServerSocket(PAC_PORT)
            while (isRunning) {
                try {
                    val clientSocket = pacServerSocket?.accept() ?: break
                    executorService?.execute { handlePacRequest(clientSocket) }
                } catch (_: IOException) {}
            }
        } catch (e: IOException) {
            Log.e("ProxyService", "Erro no servidor PAC", e)
        }
    }

    private fun handlePacRequest(socket: Socket) {
        socket.use { clientSocket ->
            try {
                val input = clientSocket.getInputStream().bufferedReader()
                val firstLine = input.readLine() ?: return
                if (firstLine.contains("proxy.pac") || firstLine.contains("pac")) {
                    val pacContent = """
                    function FindProxyForURL(url, host) {
                        if (isPlainHostName(host) || shExpMatch(host, "*.local") || shExpMatch(host, "127.0.0.1")) {
                            return "DIRECT";
                        }
                        return "SOCKS5 $hostIp:$SINGBOX_PORT; HTTP $hostIp:$SINGBOX_HTTP_PORT; DIRECT";
                    }
                    """.trimIndent()

                    val output = clientSocket.getOutputStream().bufferedWriter()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-ns-proxy-autoconfig\r\nConnection: close\r\n\r\n$pacContent")
                    output.flush()
                }
            } catch (_: Exception) {
                Log.e("ProxyService", "Erro ao servir PAC")
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "proxy_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Proxy Status", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Proxy Ativo")
            .setContentText("Sing-Box rodando")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        isRunning = false
        cleanupExecutor.execute { cleanResources() }
        try { pacServerSocket?.close() } catch (_: IOException) {}
        executorService?.shutdownNow()
        adbExecutor.shutdown()
        adbExecutor.closeConnection()
        cleanupExecutor.shutdown()
        super.onDestroy()
    }
}
