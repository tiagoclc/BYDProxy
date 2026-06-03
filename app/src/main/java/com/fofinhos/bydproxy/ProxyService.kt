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
    private var singBoxProcess: Process? = null
    private var isRunning = false

    private val SINGBOX_PORT = 8888
    private val PAC_PORT = 8880

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ProxyService", "Service onCreate")
        startForegroundServiceNotification()

        // Mantido o teu bypass de Doze mode via AdbLoopback nativo
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

        // 1. Iniciar o Core Nativo do Sing-Box
        executorService?.execute {
            startSingBoxEngine()
        }

        // 2. Iniciar o Servidor do Arquivo PAC em Kotlin
        executorService?.execute {
            startPacServer()
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun startSingBoxEngine() {
        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val singBoxBin = File(nativeDir, "libsingbox.so")
//            val configFile = File(nativeDir, "singbox_config.json")
            val logFile = "/data/local/tmp/singbox.log"

//            logFile.setReadable(true, false) // Ensure the config file is readable
//            val logFile2 = File(logFile)

//            copyAssetFile("singbox_config.json", configFile)

            Log.i("ProxyService", "Iniciando Diagnóstico ADB para DiLink 3...")
            val adb = AdbLoopbackClient()

            // 1. Diagnóstico: Verificar se o shell vê o ficheiro e as suas permissões
//            adb.executeShellCommand("ls -l ${singBoxBin.absolutePath}")
            adb.executeShellCommand("id") // Verificar qual o utilizador que está a correr no ADB

            // 2. Limpeza agressiva
            adb.executeShellCommand("pkill -9 -f libsingbox.so")
            adb.executeShellCommand("pkill -9 -f sing-box")

            val tmpBin = "/data/local/tmp/singbox_byd"
            val tmpConfig = "/data/local/tmp/singbox_config.json"
//            Log.d("ProxyService", "Verificando binário em /data/local/tmp...")
//
//            // Usamos o comando 'test' do shell para verificar se o arquivo já existe.
//            // Se NÃO existir (! -f), então copiamos (cp).
//            adb.executeShellCommand("[ ! -f $tmpBin ] && cp ${singBoxBin.absolutePath} $tmpBin")
//
//            adb.executeShellCommand("chmod 777 $tmpBin")

            val config = """
{
"log": { "level": "warn", "timestamp": true, "output": "$logFile" },
  "dns": {
    "servers": [
      {
        "type": "udp",
        "tag": "google",
        "server": "8.8.8.8"
      }
    ],
    "final": "google",
    "strategy": "ipv4_only"
  },
  "inbounds": [
    {
      "type": "mixed",
      "tag": "mixed-in",
      "listen": "127.0.0.1",
      "listen_port": 8888,
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
      {
        "protocol": "dns",
        "action": "hijack-dns"
      }
    ],
    "final": "direct-out"
  }
}
""".trimIndent()


            // Write config via shell
            val escapedConfig = config.replace("\"", "\\\"").replace("\n", "\\n")
            adb.executeShellCommand("echo '$config' > $tmpConfig &")
            adb.executeShellCommand("chmod 666 $tmpConfig")
            adb.executeShellCommand("chmod 777 $logFile")



            // 4. Execução Tentativa A: Pasta Original
            //val cmdOriginal = "nohup ${singBoxBin.absolutePath} run -c ${configFile.absolutePath} > ${logFile.absolutePath} 2>&1 &"

            // 5. Execução Tentativa B: Pasta TMP (mais permissiva)
            val cmdTmp = "${singBoxBin.absolutePath} run -c $tmpConfig > $logFile 2>&1 &"

            Log.d("ProxyService", "Enviando comandos de execução...")
           // adb.executeShellCommand(cmdOriginal)
            adb.executeShellCommand(cmdTmp)

            // 6. Monitorização
            executorService?.execute {
                while (isRunning) {
                    val isAlive = checkSingBoxStatus()
                    if (!isAlive) {
                        Log.w("ProxyService", "Sing-Box não detectado na porta $SINGBOX_PORT! Tentando reiniciar...")
                        adb.executeShellCommand(cmdTmp)
                    } else {
                        Log.d("ProxyService", "Sing-Box está ONLINE.")
                        // Lê o ficheiro de log do sing-box via ADB para ver se há conexões
                        adb.executeShellCommand("cat $logFile")
                    }
                    Thread.sleep(10000) // Verifica a cada 10 segundos
                }
            }

        } catch (e: Exception) {
            Log.e("ProxyService", "Falha no Diagnóstico ADB: ${e.message}", e)
        }
    }

    private fun checkSingBoxStatus(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", SINGBOX_PORT), 2000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun startPacServer() {
        Log.d("ProxyService", "Starting PAC Server on port $PAC_PORT")
        try {
            pacServerSocket = ServerSocket(PAC_PORT)
            pacServerSocket?.soTimeout = 5000 // Timeout para checar isRunning ciclicamente

            while (isRunning) {
                try {
                    val clientSocket = pacServerSocket?.accept() ?: break
                    clientSocket.soTimeout = 10000 // 10s para entrega do arquivo de rota
                    executorService?.execute { handlePacRequest(clientSocket) }
                } catch (_: IOException) {
                    // Timeout do accept, continua o loop se isRunning for verdadeiro
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

            // Servir o arquivo PAC se solicitado
            if (url.endsWith("/proxy.pac") || url == "proxy.pac") {
                val localAddress = socket.localAddress.hostAddress ?: "127.0.0.1"
                val localPort = socket.localPort

                val pacContent = """
                    function FindProxyForURL(url, host) {
                        if (
                            isPlainHostName(host) ||
                            shExpMatch(host, "*.local") ||
                            shExpMatch(host, "192.168.*") ||
                            shExpMatch(host, "127.0.0.1") ||
                            shExpMatch(host, "localhost")
                        ) {
                            return "DIRECT";
                        }
                        return "SOCKS5 $localAddress:$SINGBOX_PORT; SOCKS $localAddress:$SINGBOX_PORT; DIRECT";
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
                Log.d("ProxyService", "PAC servido com sucesso para ${socket.inetAddress.hostAddress} (Porta: $localPort)")
            }
        } catch (e: Exception) {
            Log.e("ProxyService", "Erro ao servir o arquivo PAC", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun copyAssetFile(assetName: String, destination: File) {
        assets.open(assetName).use { inputStream ->
            FileOutputStream(destination).use { outputStream ->
                val buffer = ByteArray(4096)
                var length: Int
                while (inputStream.read(buffer).also { length = it } > 0) {
                    outputStream.write(buffer, 0, length)
                }
                outputStream.flush()
            }
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
            .setContentText("Sing-Box ativo na porta $SINGBOX_PORT | PAC na porta $PAC_PORT")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        startForeground(1, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleServiceRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false

        // Finaliza o processo nativo do sing-box de forma segura
        try { singBoxProcess?.destroy() } catch (_: Exception) {}

        // Se foi iniciado via ADB, tenta matar o processo remotamente
        try {
            val adb = AdbLoopbackClient()
            adb.executeShellCommand("pkill -f sing-box")
        } catch (_: Exception) {}

        // Fecha o canal do arquivo PAC
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
        val restartDelayMs = 2000L
        val triggerAtMillis = SystemClock.elapsedRealtime() + restartDelayMs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}