package com.example.callsaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class WhatsappServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            restartService(context)
        }
    }

    private fun restartService(context: Context) {
        val serviceIntent = Intent(context, WhatsAppNotificationService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}