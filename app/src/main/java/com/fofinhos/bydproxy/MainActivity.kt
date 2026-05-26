package com.fofinhos.bydproxy

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inicia o serviço
        val serviceIntent = Intent(this, ProxyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        // Fecha a activity imediatamente para não mostrar interface
        finish()
    }
}