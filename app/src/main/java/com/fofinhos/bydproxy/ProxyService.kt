package com.fofinhos.bydproxy

import android.annotation.SuppressLint
import android.app.AlarmManager
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
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProxyService : Service() {

    private var pacServerSocket: ServerSocket? = null
    private var executorService: ExecutorService? = null
    private var isRunning = false

    private val SINGBOX_PORT = 8888
    private val SINGBOX_HTTP_PORT = 8887        // Porta HTTP dedicada
    private val PAC_PORT = 8880
    var hostIp = "192.168.43.1" // IP base por defeito do Hotspot Android

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ProxyService", "Service onCreate")
        startForegroundServiceNotification()

        try {
            val adb = AdbLoopbackClient()
            adb.executeShellCommand("dumpsys deviceidle whitelist +com.fofinhos.bydproxy")
        } catch (e: Exception) {
            Log.e("ProxyService", "Erro ao executar comando ADB Loopback", e)
        }

        executorService = Executors.newCachedThreadPool()
        startProxySystem()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startProxySystem() {
        if (isRunning) return
        isRunning = true

        executorService?.execute {
            startSingBoxEngine()
        }

        executorService?.execute {
            startPacServer()
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun startSingBoxEngine() {
        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val singBoxBin = File(nativeDir, "libsingbox.so")
            val logFile = "/data/local/tmp/singbox.log"

            Log.i("ProxyService", "Iniciando Diagnóstico ADB para DiLink...")
            val adb = AdbLoopbackClient()

            // 1. Identificar dinamicamente o IP atribuído à rede local do Hotspot (Usado no PAC)
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

            // 2. Limpeza profunda de processos órfãos antigos
            adb.executeShellCommand("pkill -9 -f libsingbox.so")
            adb.executeShellCommand("pkill -9 -f sing-box")
            adb.executeShellCommand("pkill -9 -f singbox_byd")

            val tmpBin = "/data/local/tmp/singbox_byd"
            val tmpConfig = "/data/local/tmp/singbox_config.json"

            // --- INÍCIO DA SUA LÓGICA DE CÓPIA (INTACTA) ---
            val tamanhoOriginal = singBoxBin.length()
            Log.d("ProxyService", "Tamanho esperado do binário: $tamanhoOriginal bytes")

            val sizeOutput = adb.executeShellCommand("wc -c $tmpBin 2>/dev/null").toString().trim()
            val tamanhoNoDestino = sizeOutput.split(" ")[0].toLongOrNull() ?: 0L

            if (tamanhoNoDestino != tamanhoOriginal) {
                Log.w("ProxyService", "Binário em /tmp ausente ou incompleto ($tamanhoNoDestino bytes). A iniciar cópia síncrona segura...")

                adb.executeShellCommand("rm -f $tmpBin")

                val localTmpFile = File(cacheDir, "singbox_full.tmp")

                try {
                    singBoxBin.inputStream().use { input ->
                        localTmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    Log.d("ProxyService", "Cópia interna concluída para o cache (${localTmpFile.length()} bytes). A transferir para /tmp...")

                    adb.executeShellCommand("cat ${singBoxBin.absolutePath} > $tmpBin")

                    var verificadoSucesso = false
                    for (i in 1..5) {
                        Thread.sleep(1000)
                        val checkSizeStr = adb.executeShellCommand("wc -c $tmpBin 2>/dev/null").toString().trim().split(" ")[0]
                        val tamanhoAtualDestino = File(tmpBin).length()
                        Log.d("ProxyService", "Tentativa de verificação $i: $tamanhoAtualDestino de $tamanhoOriginal bytes")

                        if (tamanhoAtualDestino == tamanhoOriginal) {
                            verificadoSucesso = true
                            break
                        }
                    }

                    if (verificadoSucesso) {
                        Log.i("ProxyService", "Sucesso! O binário foi copiado por inteiro e validado.")
                    } else {
                        Log.e("ProxyService", "ERRO CRÍTICO: O tamanho do binário em /tmp divergiu após a cópia.")
                    }

                } catch (e: Exception) {
                    Log.e("ProxyService", "Falha catastrófica na transferência do binário", e)
                } finally {
                    if (localTmpFile.exists()) localTmpFile.delete()
                }
            } else {
                Log.i("ProxyService", "O binário já existe em /tmp com o tamanho correto ($tamanhoNoDestino bytes). Cópia ignorada.")
            }
            // --- FIM DA SUA LÓGICA DE CÓPIA ---

            adb.executeShellCommand("chmod 777 $tmpBin")

            // 3. Configuração Corrigida com Sintaxe Standard de Rotas
            val config = """
            {
              "log": { 
                "level": "warn", 
                "timestamp": true, 
                "output": "$logFile" 
              },
              "dns": {
                "servers": [
                  { 
                    "type": "udp", 
                    "tag": "google-dns", 
                    "server": "8.8.8.8",
                    "server_port": 53
                  },
                  { 
                    "type": "udp", 
                    "tag": "cloudflare-dns", 
                    "server": "1.1.1.1",
                    "server_port": 53
                  }
                ],
                "final": "google-dns"
              },
              "inbounds": [
                {
                  "type": "socks",
                  "tag": "socks-in",
                  "listen": "0.0.0.0",
                  "listen_port": $SINGBOX_PORT,
                  "sniff": true
                },
                {
                  "type": "http",
                  "tag": "http-in",
                  "listen": "0.0.0.0",
                  "listen_port": $SINGBOX_HTTP_PORT,
                  "sniff": true
                }
              ],
              "outbounds": [
                { 
                  "type": "direct", 
                  "tag": "direct-out"
                }
              ],
              "route": {
                "rules": [
                  { "protocol": "dns", "action": "hijack-dns" }
                ],
                "auto_detect_interface": false,
                "final": "direct-out"
              }
            }
            """.trimIndent()

            // 4. Escrita Direta via Java API no Destino (Previne corrupção de caracteres via terminal)
            try {
                val destinationFile = File(tmpConfig)
                destinationFile.outputStream().use { output ->
                    output.write(config.toByteArray(Charsets.UTF_8))
                }
                Log.d("ProxyService", "Configuração JSON injetada diretamente no ambiente nativo.")
            } catch (e: Exception) {
                Log.e("ProxyService", "Escrita nativa falhou, a aplicar contingência Base64...", e)
                val base64Config = android.util.Base64.encodeToString(config.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                adb.executeShellCommand("printf '%s' '$base64Config' | base64 -d > $tmpConfig")
            }

            adb.executeShellCommand("chmod 666 $tmpConfig")
            adb.executeShellCommand("echo '' > $logFile")
            adb.executeShellCommand("chmod 777 $logFile")

            val cmdTmp = "$tmpBin run -c $tmpConfig > $logFile 2>&1 &"
            Log.d("ProxyService", "A iniciar processo do Motor...")
            adb.executeShellCommand(cmdTmp)

            // 5. Dump inicial pós-arranque para validação rápida no logcat
            executorService?.execute {
                Thread.sleep(3000)
                val coreLogs = adb.executeShellCommand("tail -n 20 $logFile")
                if (coreLogs.toString().isNotBlank()) {
                    Log.d("SingBoxCore", "\n--- LOGS DE ARRANQUE ---\n$coreLogs------------------")
                }
            }

        } catch (e: Exception) {
            Log.e("ProxyService", "Falha no Diagnóstico ADB: ${e.message}", e)
        }
    }

    private fun startPacServer() {
        Log.d("ProxyService", "Starting PAC Server on port $PAC_PORT")
        try {
            pacServerSocket = ServerSocket(PAC_PORT)
            pacServerSocket?.soTimeout = 5000

            while (isRunning) {
                try {
                    val clientSocket = pacServerSocket?.accept() ?: break
                    clientSocket.soTimeout = 10000
                    executorService?.execute { handlePacRequest(clientSocket) }
                } catch (_: IOException) {
                }
            }
        } catch (e: IOException) {
            Log.e("ProxyService", "Erro no servidor de arquivo PAC", e)
        }
    }

    private fun handlePacRequest(socket: Socket) {
        try {
            val input = socket.getInputStream().bufferedReader()
            val firstLine = input.readLine() ?: return

            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val url = parts[1]

            if (url.endsWith("/proxy.pac") || url == "proxy.pac" || url.contains("pac")) {
// Verifica se a ligação vem do próprio Mac (local) ou de um dispositivo externo
                val clientIp = socket.inetAddress.hostAddress
                val proxyAddress = if (clientIp == "127.0.0.1") "127.0.0.1" else hostIp

                val pacContent = """
            function FindProxyForURL(url, host) {
                if (isPlainHostName(host) || shExpMatch(host, "*.local") || shExpMatch(host, "127.0.0.1")) {
                    return "DIRECT";
                }
                // Usa o endereço dinâmico dependendo de quem pede
                return "SOCKS5 $proxyAddress:$SINGBOX_PORT; HTTP $proxyAddress:$SINGBOX_HTTP_PORT; DIRECT";
            }
        """.trimIndent()

                val output = socket.getOutputStream().bufferedWriter()
                val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-ns-proxy-autoconfig\r\n" +
                        "Content-Length: ${pacContent.toByteArray(Charsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n\r\n" +
                        pacContent

                output.write(response)
                output.flush()
                Log.d("ProxyService", "PAC distribuído com sucesso para o IP exterior: ${socket.inetAddress.hostAddress}")
            }
        } catch (e: Exception) {
            Log.e("ProxyService", "Erro ao servir o arquivo PAC", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "tether_bypass_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Proxy Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bypass Ativo")
            .setContentText("Sing-Box ativo nas portas $SINGBOX_PORT/$SINGBOX_HTTP_PORT | PAC na porta $PAC_PORT")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()

        startForeground(1, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleServiceRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        try {
            val adb = AdbLoopbackClient()
            adb.executeShellCommand("pkill -9 -f singbox_byd")
        } catch (_: Exception) {}

        try { pacServerSocket?.close() } catch (_: IOException) {}
        executorService?.shutdownNow()
        scheduleServiceRestart()
        super.onDestroy()
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleServiceRestart() {
        val restartServiceIntent = Intent(applicationContext, RestartReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            1,
            restartServiceIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = SystemClock.elapsedRealtime() + 2000L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}