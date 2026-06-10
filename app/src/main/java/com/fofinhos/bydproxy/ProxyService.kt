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
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProxyService : Service() {

    private var pacServerSocket: ServerSocket? = null
    private var executorService: ExecutorService? = null
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    private val adbClient = AdbLoopbackClient()
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
                adbClient.executeShellCommand("dumpsys deviceidle whitelist +com.fofinhos.bydproxy")

                // Forçar sincronização de relógio para evitar rejeição de certificados TLS
                // Isso é CRÍTICO para evitar "Security Error"


                // Remover sysctls que podem causar fragmentação e erro de SSL/TLS em emuladores
//                adbClient.executeShellCommand("sysctl -w net.ipv4.tcp_window_scaling=1")
            } catch (e: Exception) {
                Log.e("ProxyService", "Erro ao executar comandos ADB iniciais", e)
            }
        }

        executorService = Executors.newFixedThreadPool(4)
        startProxySystem()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun cleanResources() {
        val cleanCmd = """
            # Encontra e mata qualquer processo chamado sing-box ou singbox_byd
            # Em Android, o PID é geralmente a segunda coluna no output do ps
            for pid in ${'$'}(ps -A | grep -E 'sing-box|singbox_byd|libsingbox' | awk '{print ${'$'}2}'); do
                kill -9 ${'$'}pid 2>/dev/null
            done
            
            # Tenta pkill por via das dúvidas
            pkill -9 -f singbox_byd 2>/dev/null
        """.trimIndent()

        adbClient.executeShellCommand(cleanCmd)
    }

    private fun startProxySystem() {
        if (isRunning) return
        isRunning = true

        // Executamos a limpeza de forma assíncrona antes de iniciar as threads principais
        cleanupExecutor.execute {
            Log.d("ProxyService", "Limpando recursos e portas anteriores...")
            cleanResources()

            // Aguarda o kernel libertar o socket TIME_WAIT antes de bindar novamente
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

            Log.i("ProxyService", "Iniciando Diagnóstico ADB para DiLink...")

            // 1. Identificar dinamicamente o IP atribuído à rede local do Hotspot
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

            // 2. Lógica de cópia
            val tamanhoOriginal = singBoxBin.length()
            val sizeOutput = adbClient.executeShellCommand("wc -c $tmpBin 2>/dev/null").trim()
            val tamanhoNoDestino = sizeOutput.split(Regex("\\s+"))[0].toLongOrNull() ?: 0L

            if (tamanhoNoDestino != tamanhoOriginal) {
                Log.w("ProxyService", "Binário em /tmp ausente ou incompleto. A iniciar cópia...")
                adbClient.executeShellCommand("rm -f $tmpBin")
                val localTmpFile = File(cacheDir, "singbox_full.tmp")

                try {
                    singBoxBin.inputStream().use { input ->
                        localTmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    adbClient.executeShellCommand("cat ${singBoxBin.absolutePath} > $tmpBin")

                    var verificadoSucesso = false
                    for (i in 1..5) {
                        Thread.sleep(1000)
                        val checkSizeStr = adbClient.executeShellCommand("wc -c $tmpBin 2>/dev/null").trim().split(Regex("\\s+"))[0]
                        val tamanhoAtualDestino = checkSizeStr.toLongOrNull() ?: 0L
                        if (tamanhoAtualDestino == tamanhoOriginal) {
                            verificadoSucesso = true
                            break
                        }
                    }

                    if (verificadoSucesso) {
                        Log.i("ProxyService", "Sucesso! Binário copiado.")
                    } else {
                        Log.e("ProxyService", "ERRO CRÍTICO na cópia do binário.")
                    }
                } catch (e: Exception) {
                    Log.e("ProxyService", "Falha na transferência", e)
                } finally {
                    if (localTmpFile.exists()) localTmpFile.delete()
                }
            } else {
                Log.i("ProxyService", "Binário já existe em /tmp com tamanho correto.")
            }

            adbClient.executeShellCommand("chmod 777 $tmpBin")

            // 3. Configuração ajustada (Removido reuse_port incompatível)
            val config = """
            {
              "log": { 
                "level": "error", 
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
                "final": "google-dns",
                "strategy": "ipv4_only"
              },
              "inbounds": [
                {
                  "type": "socks",
                  "tag": "socks-in",
                  "listen": "0.0.0.0",
                  "listen_port": $SINGBOX_PORT,
                  "sniff": true,
                  "sniff_timeout": "3000ms",
                  "domain_strategy": "ipv4_only"
                },
                {
                  "type": "http",
                  "tag": "http-in",
                  "listen": "0.0.0.0",
                  "listen_port": $SINGBOX_HTTP_PORT,
                  "sniff": true,
                  "sniff_timeout": "3000ms",
                  "domain_strategy": "ipv4_only"
                }
              ],
              "outbounds": [
                { 
                  "type": "direct", 
                  "tag": "direct-out",
                  "domain_strategy": "ipv4_only",
                  "udp_fragment": true
                }
              ],
              "route": {
                "rules": [
                  { "protocol": "dns", "action": "hijack-dns" }
                ],
                "auto_detect_interface": true,
                "final": "direct-out"
              }
            }
            """.trimIndent()

            // 4. Escrita de Configuração via ADB
            try {
                val base64Config = android.util.Base64.encodeToString(config.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                adbClient.executeShellCommand("printf '%s' '$base64Config' | base64 -d > $tmpConfig")
            } catch (e: Exception) {
                Log.e("ProxyService", "Falha ao injetar configuração", e)
            }

            adbClient.executeShellCommand("chmod 666 $tmpConfig")
            adbClient.executeShellCommand("rm -f $logFile")
            adbClient.executeShellCommand("touch $logFile")
            adbClient.executeShellCommand("chmod 777 $logFile")

            // Uso do chrt -f 99 para prioridade máxima no Kernel
            // Adicionado export HOME para evitar problemas com sing-box a tentar escrever em dirs restritos
            val cmdTmp = "export HOME=/data/local/tmp; nohup $tmpBin run -c $tmpConfig > $logFile 2>&1 &"
            Log.d("ProxyService", "Iniciando motor com prioridade e limpeza de RAM...")
            adbClient.executeShellCommand(cmdTmp)

            // 5. Monitorização contínua
            executorService?.execute {
                var lastStateOnline = false
                var consecutiveFailures = 0

                Thread.sleep(5000)

                while (isRunning) {
                    val isAlive = checkSingBoxStatus()

                    if (isAlive) {
                        if (!lastStateOnline) {
                            Log.i("ProxyService", "Sing-Box está ONLINE e operacional.")
                            lastStateOnline = true
                        }
                        consecutiveFailures = 0
                        Thread.sleep(30000) // Verifica a cada 30 segundos
                    } else {
                        consecutiveFailures++

                        if (!lastStateOnline && consecutiveFailures < 30) {
                            Log.v("ProxyService", "Aguardando motor subir ($consecutiveFailures/30)...")
                            Thread.sleep(2000)
                        } else if (consecutiveFailures < 3 && lastStateOnline) {
                            Log.v("ProxyService", "Sing-Box falhou esporadicamente ($consecutiveFailures/3).")
                            Thread.sleep(7000)
                        } else {
                            Log.w("ProxyService", "Sing-Box inativo. Limpando recursos e reiniciando processo...")
                            cleanResources()
                            Thread.sleep(3000)
                            adbClient.executeShellCommand(cmdTmp)

                            lastStateOnline = false
                            consecutiveFailures = 0
                            Thread.sleep(5000)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("ProxyService", "Falha no processo principal: ${e.message}", e)
        }
    }

    private fun checkSingBoxStatus(): Boolean {
        // Verificação Funcional: O que está na porta 8888 fala o protocolo SOCKS5?
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", SINGBOX_PORT), 1500)
                val out = socket.getOutputStream()
                val ins = socket.getInputStream()
                
                // Envia Saudação SOCKS5: Versão 5, 1 Metodo, Sem Autenticação
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                
                val response = ByteArray(2)
                val read = ins.read(response)
                
                // Valida se a resposta é 05 00 (SOCKS5 + No Auth)
                read == 2 && response[0] == 0x05.toByte() && response[1] == 0x00.toByte()
            }
        } catch (e: Exception) {
            false
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
            Log.e("ProxyService", "Erro no servidor PAC", e)
        }
    }

    private fun handlePacRequest(socket: Socket) {
        socket.use { clientSocket ->
            try {
                val input = clientSocket.getInputStream().bufferedReader()
                val firstLine = input.readLine() ?: return

                val parts = firstLine.split(" ")
                if (parts.size < 2) return
                val url = parts[1]

                if (url.endsWith("/proxy.pac") || url == "proxy.pac" || url.contains("pac")) {
                    val clientIp = clientSocket.inetAddress.hostAddress
                    val proxyAddress = if (clientIp == "127.0.0.1") "127.0.0.1" else hostIp

                    // Lógica de Failover no PAC para evitar falhas abruptas
                    val pacContent = """
                    function FindProxyForURL(url, host) {
                        if (isPlainHostName(host) || shExpMatch(host, "*.local") || shExpMatch(host, "127.0.0.1")) {
                            return "DIRECT";
                        }
                        return "SOCKS5 $proxyAddress:$SINGBOX_PORT; SOCKS5 $proxyAddress:$SINGBOX_HTTP_PORT; HTTP $proxyAddress:$SINGBOX_HTTP_PORT; DIRECT";
                    }
                    """.trimIndent()

                    val output = clientSocket.getOutputStream().bufferedWriter()
                    val response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/x-ns-proxy-autoconfig\r\n" +
                            "Content-Length: ${pacContent.toByteArray(Charsets.UTF_8).size}\r\n" +
                            "Connection: close\r\n\r\n" +
                            pacContent

                    output.write(response)
                    output.flush()
                }
            } catch (e: Exception) {
                Log.e("ProxyService", "Erro ao servir PAC: ${e.message}")
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "tether_bypass_channel"
        val channelName = "Proxy Status"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bypass Ativo")
            .setContentText("Sing-Box: $SINGBOX_PORT/$SINGBOX_HTTP_PORT | PAC: $PAC_PORT")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleServiceRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d("ProxyService", "Service onDestroy")
        isRunning = false
        
        cleanupExecutor.execute {
            cleanResources()
        }

        try { pacServerSocket?.close() } catch (_: IOException) {}
        executorService?.shutdownNow()
        cleanupExecutor.shutdown()
        
        // Apenas agenda restart se não foi uma paragem manual (opcional, mas seguro)
        // scheduleServiceRestart()
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