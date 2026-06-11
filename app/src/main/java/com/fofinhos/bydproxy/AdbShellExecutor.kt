package com.fofinhos.bydproxy

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles ADB shell command execution and connection management.
 */
class AdbShellExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AdbShellExecutor"
        private const val ADB_PORT = 5555
        private const val ADB_KEY_FILE = "adbkey"
        private const val ADB_PUB_KEY_FILE = "adbkey.pub"
        
        @Volatile
        private var cachedKeyPair: AdbKeyPair? = null
        private val keyPairLock = Object()
        
        @Volatile
        private var sharedDadb: Dadb? = null
        private val sharedDadbLock = Object()
        
        // Auth state tracking
        private val isAuthPending = AtomicBoolean(false)
        private val wasAuthGranted = AtomicBoolean(false)
        private val pollingStarted = AtomicBoolean(false)
        
        @Volatile
        private var authCallback: AdbAuthCallback? = null
        
        // Dedicated polling executor (separate from command executor)
        private val pollingExecutor = Executors.newSingleThreadExecutor()

        private val scriptSeq = AtomicLong(0)
        
        fun setAuthCallback(callback: AdbAuthCallback?) {
            authCallback = callback
        }
        
        fun checkAndClearAuthGranted(): Boolean {
            return wasAuthGranted.getAndSet(false)
        }
        
        fun isAuthPending(): Boolean = isAuthPending.get()
    }
    
    interface AdbAuthCallback {
        fun onAuthPending()
        fun onAuthGranted()
        fun onAuthFailed(error: String)
    }
    
    private val executor = Executors.newSingleThreadExecutor()
    
    interface ShellCallback {
        fun onSuccess(output: String)
        fun onError(error: String)
    }
    
    data class ShellResult(
        val exitCode: Int,
        val output: String
    )
    
    fun execute(command: String, callback: ShellCallback) {
        executor.execute {
            try {
                Log.d(TAG, "Executing async: $command")
                val dadb = getOrCreateConnection()
                val result = dadb.shell(command)
                
                if (result.exitCode == 0) {
                    callback.onSuccess(result.allOutput)
                } else {
                    callback.onError("Exit code ${result.exitCode}: ${result.allOutput}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command execution failed: $command", e)
                callback.onError("Execution failed: ${e.message}")
            }
        }
    }
    
    fun executeSync(command: String): ShellResult {
        Log.d(TAG, "Executing sync: $command")
        val dadb = getOrCreateConnection()
        val result = dadb.shell(command)
        return ShellResult(result.exitCode, result.allOutput)
    }

    /**
     * Run a script via a temp file rather than `sh -c "<script>"`.
     */
    fun executeScript(scriptBody: String, callback: ShellCallback) {
        executor.execute {
            val nonce = "${System.nanoTime()}_${scriptSeq.incrementAndGet()}"
            val scriptPath = "/data/local/tmp/.adb_script_${nonce}.sh"
            val eofMarker = "__ADB_SCRIPT_EOF_${nonce}__"
            try {
                Log.d(TAG, "Executing script via $scriptPath (${scriptBody.length} bytes)")
                val dadb = getOrCreateConnection()

                val writeCmd = "cat > $scriptPath <<'$eofMarker'\n" +
                        scriptBody +
                        "\n$eofMarker"
                val writeResult = dadb.shell(writeCmd)
                if (writeResult.exitCode != 0) {
                    try { dadb.shell("rm -f $scriptPath 2>/dev/null") } catch (ignored: Exception) {}
                    callback.onError("script-write failed: ${writeResult.allOutput}")
                    return@execute
                }

                val runResult = dadb.shell(
                    "trap 'rm -f $scriptPath' EXIT; sh $scriptPath"
                )

                if (runResult.exitCode == 0) {
                    callback.onSuccess(runResult.allOutput)
                } else {
                    callback.onError("Exit code ${runResult.exitCode}: ${runResult.allOutput}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Script execution failed", e)
                callback.onError("Execution failed: ${e.message}")
                try { getOrCreateConnection().shell("rm -f $scriptPath 2>/dev/null") } catch (ignored: Exception) {}
            }
        }
    }
    
    fun checkProcessRunning(processName: String): Int? {
        return try {
            val dadb = getOrCreateConnection()
            val result = dadb.shell("pgrep -f '$processName'")
            
            if (result.exitCode == 0 && result.allOutput.trim().isNotEmpty()) {
                result.allOutput.trim().lines().firstOrNull()?.toIntOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check process: $processName", e)
            null
        }
    }
    
    fun killProcess(processName: String): Boolean {
        return try {
            val dadb = getOrCreateConnection()
            val cmd = "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F '$processName' | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
                "echo done"
            val result = dadb.shell(cmd)
            result.exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill process: $processName", e)
            false
        }
    }
    
    fun getOrCreateConnection(): Dadb {
        synchronized(sharedDadbLock) {
            var dadb = sharedDadb
            if (dadb != null) {
                try {
                    val result = dadb.shell("echo ok")
                    if (result.exitCode == 0) {
                        if (isAuthPending.getAndSet(false)) {
                            wasAuthGranted.set(true)
                            Log.i(TAG, "ADB auth granted! Connection established.")
                            authCallback?.onAuthGranted()
                        }
                        return dadb
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Existing ADB connection dead, reconnecting...")
                }
                try { dadb.close() } catch (e: Exception) {}
                sharedDadb = null
            }
            
            if (!isAdbPortOpen()) {
                Log.w(TAG, "ADB port $ADB_PORT not open - ADB not enabled?")
                throw Exception("ADB port not open")
            }
            
            val adbKeyPair = getOrCreateAdbKeyPair()
            Log.i(TAG, "Attempting ADB connection...")
            
            if (!isAuthPending.get() && pollingStarted.compareAndSet(false, true)) {
                isAuthPending.set(true)
                authCallback?.onAuthPending()
                startAuthPollingInternal(adbKeyPair)
            }
            
            dadb = tryConnectWithTimeout(adbKeyPair, 2000)
            
            if (dadb != null) {
                sharedDadb = dadb
                isAuthPending.set(false)
                wasAuthGranted.set(true)
                pollingStarted.set(false)
                Log.i(TAG, "ADB connection established successfully")
                authCallback?.onAuthGranted()
                return dadb
            } else {
                throw Exception("ADB auth pending - waiting for user to accept")
            }
        }
    }
    
    private fun isAdbPortOpen(): Boolean {
        return try {
            Socket("127.0.0.1", ADB_PORT).use { true }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun tryConnectWithTimeout(keyPair: AdbKeyPair, timeoutMs: Long): Dadb? {
        var result: Dadb? = null
        var error: Exception? = null

        val connectThread = Thread({
            try {
                val dadb = Dadb.create("127.0.0.1", ADB_PORT, keyPair)
                val testResult = dadb.shell("echo ok")
                if (testResult.exitCode == 0) {
                    result = dadb
                } else {
                    dadb.close()
                }
            } catch (e: Exception) {
                error = e
            }
        }, "adb-connect-probe").apply {
            isDaemon = true
        }

        connectThread.start()
        connectThread.join(timeoutMs)

        if (connectThread.isAlive) {
            Log.d(TAG, "Connection timed out - auth likely pending; orphan probe thread will exit on socket timeout")
            connectThread.interrupt()
            return null
        }

        error?.let { throw it }
        return result
    }
    
    private fun startAuthPollingInternal(keyPair: AdbKeyPair) {
        pollingExecutor.execute {
            Log.i(TAG, "=== AUTH POLLING STARTED ===")
            var attempts = 0
            val maxAttempts = 60
            
            while (isAuthPending.get() && attempts < maxAttempts) {
                attempts++
                
                try {
                    Thread.sleep(3000)
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Polling interrupted")
                    break
                }
                
                if (!isAuthPending.get()) {
                    Log.d(TAG, "Auth no longer pending, stopping poll")
                    break
                }
                
                Log.i(TAG, "Auth poll attempt $attempts/$maxAttempts...")
                
                if (!isAdbPortOpen()) {
                    Log.d(TAG, "ADB port not open, skipping attempt")
                    continue
                }
                
                val testDadb = tryConnectWithTimeout(keyPair, 2000)
                
                if (testDadb != null) {
                    synchronized(sharedDadbLock) {
                        try { sharedDadb?.close() } catch (ignored: Exception) {}
                        sharedDadb = testDadb
                    }
                    isAuthPending.set(false)
                    wasAuthGranted.set(true)
                    pollingStarted.set(false)
                    Log.i(TAG, "=== AUTH GRANTED VIA POLLING ===")
                    authCallback?.onAuthGranted()
                    break
                }
            }
            
            if (isAuthPending.get() && attempts >= maxAttempts) {
                Log.w(TAG, "Auth polling timed out")
                isAuthPending.set(false)
                pollingStarted.set(false)
                authCallback?.onAuthFailed("Auth timeout - please grant ADB permission and restart app")
            }
        }
    }
    
    fun closeConnection() {
        synchronized(sharedDadbLock) {
            try {
                sharedDadb?.close()
                Log.i(TAG, "Closed ADB connection")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ADB connection", e)
            }
            sharedDadb = null
        }
    }

    fun shutdown() {
        try {
            executor.shutdownNow()
        } catch (e: Exception) {
            Log.w(TAG, "executor shutdown failed: ${e.message}")
        }
    }
    
    private fun getOrCreateAdbKeyPair(): AdbKeyPair {
        cachedKeyPair?.let { return it }
        
        synchronized(keyPairLock) {
            cachedKeyPair?.let { return it }
            
            val keyDir = context.filesDir
            val privateKeyFile = File(keyDir, ADB_KEY_FILE)
            val publicKeyFile = File(keyDir, ADB_PUB_KEY_FILE)
            
            val keyPair = if (privateKeyFile.exists() && publicKeyFile.exists()) {
                try {
                    AdbKeyPair.read(privateKeyFile, publicKeyFile)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read existing keys: ${e.message}")
                    generateAndSaveKeyPair(privateKeyFile, publicKeyFile)
                }
            } else {
                Log.i(TAG, "Generating new ADB key pair")
                generateAndSaveKeyPair(privateKeyFile, publicKeyFile)
            }
            
            cachedKeyPair = keyPair
            return keyPair
        }
    }
    
    private fun generateAndSaveKeyPair(privateKeyFile: File, publicKeyFile: File): AdbKeyPair {
        AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }
}
