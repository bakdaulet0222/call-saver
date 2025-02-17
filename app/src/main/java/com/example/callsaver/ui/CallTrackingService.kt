package com.example.callsaver.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class CallTrackingService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "call_tracking_service"
            val channel = NotificationChannel(
                channelId,
                "Call Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val notification: Notification = Notification.Builder(this, channelId)
                .setContentTitle("Отслеживание звонков")
                .setContentText("Приложение отслеживает звонки.")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .build()

            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Логика отслеживания звонков
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}