package com.example.callsaver

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.Worker
import androidx.work.WorkerParameters

class RestartWhatsAppServiceWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun doWork(): Result {
        val serviceIntent = Intent(applicationContext, WhatsAppNotificationService::class.java)
        applicationContext.startForegroundService(serviceIntent)
        return Result.success()
    }
}