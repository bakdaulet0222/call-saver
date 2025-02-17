package com.example.callsaver

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.callsaver.MainActivity.Companion.CHANNEL_ID
import com.example.callsaver.ui.CallListener

class CallService : Service() {
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var callListener: CallListener

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d("CallService", "Сервис создан")
        callListener = CallListener(this)
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CallService", "Call tracking service started")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(callListener, PhoneStateListener.LISTEN_CALL_STATE)

        return START_STICKY // Позволяет сервису перезапускаться после остановки
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("CallService", "Сервис уничтожен, перезапускаем...")

        // Остановить слушателя
        telephonyManager.listen(callListener, PhoneStateListener.LISTEN_NONE)

        // Перезапуск сервиса
        restartService()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundService() {
        val channelId = "call_tracking_service"
        val channelName = "Call Tracking"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Мир Телевизоров")
            .setContentText("Наблюдение за входящими звонками...")
            .setSmallIcon(R.drawable.ic_television)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true) // Нельзя удалить свайпом
            .build()

        startForeground(1, notification)
    }

    private fun restartService() {
        val restartIntent = Intent(this, ServiceRestartReceiver::class.java)
        sendBroadcast(restartIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
