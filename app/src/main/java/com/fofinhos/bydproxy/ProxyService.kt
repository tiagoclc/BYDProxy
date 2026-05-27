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
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProxyService : Service() {

    private var serverSocket: ServerSocket? = null
    private var executorService: ExecutorService? = null
    private var isRunning = false
    private val PROXY_PORT = 8888

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ProxyService", "Service onCreate")
        startForegroundServiceNotification()

        val adb = AdbLoopbackClient()
        adb.executeShellCommand("dumpsys deviceidle whitelist +com.fofinhos.bydproxy")

        executorService = Executors.newCachedThreadPool()
        startProxyServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startProxyServer() {
        if (isRunning) return
        isRunning = true
        Log.d("ProxyService", "Starting Proxy Server on port $PROXY_PORT")
        executorService?.execute {
            try {
                serverSocket = ServerSocket(PROXY_PORT)
                serverSocket?.soTimeout = 5000 // Timeout para o accept() poder checar isRunning
                Log.d("ProxyService", "ServerSocket opened successfully")
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        clientSocket.soTimeout = 30000 // 30s timeout para operações iniciais
                        Log.d("ProxyService", "Accepted new connection")
                        executorService?.execute(ProxyWorker(clientSocket, executorService!!))
                    } catch (_: IOException) {
                        // Provavelmente timeout do accept, apenas continua se isRunning
                    }
                }
            } catch (e: IOException) {
                Log.e("ProxyService", "Error in Proxy Server", e)
            } finally {
                isRunning = false
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
            .setContentText("Proxy HTTP a rodar na porta $PROXY_PORT")
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
        try { serverSocket?.close() } catch (e: IOException) {}
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