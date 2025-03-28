package com.example.callsaver

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class WhatsAppNotificationService : NotificationListenerService() {
    private val TAG = "WhatsAppNotification"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Сервис WhatsApp Notification запущен")
        startForegroundService() // Делаем сервис неубиваемым
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName == "com.whatsapp") {
            val notificationText = sbn.notification.extras.getString("android.text")
            val sender = sbn.notification.extras.getString("android.title")
            if (notificationText != null && sender != null) {
                Log.d(TAG, "Новое сообщение от $sender: $notificationText")
                saveContactIfNeeded(sender, applicationContext)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Можно обработать удаление уведомлений, если нужно
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Сервис уничтожен, перезапускаем...")
        restartService()
        restartServiceWithAlarm() // Перезапуск при закрытии
        val workRequest = PeriodicWorkRequest.Builder(
            RestartWhatsAppServiceWorker::class.java,
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(applicationContext).enqueue(workRequest)
    }

    private fun restartService() {
        val restartIntent = Intent(this, ServiceRestartReceiver::class.java)
        sendBroadcast(restartIntent)
    }

    private fun startForegroundService() {
        val channelId = "whatsapp_listener_service"
        val channelName = "WhatsApp Listener"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Мониторинг WhatsApp")
            .setContentText("Обнаружение неизвестных номеров...")
            .setSmallIcon(R.drawable.ic_whatsapp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Нельзя удалить свайпом
            .build()

        startForeground(2, notification)
    }


    private fun restartServiceWithAlarm() {
        val intent = Intent(this, WhatsAppNotificationService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = System.currentTimeMillis() + 10 * 1000 // Через 10 секунд

        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
    }

    private fun saveContactIfNeeded(contactName: String, context: Context) {
        if (isValidPhoneNumber(contactName)) { // Проверяем, что это номер
            if (isContactExists(context, contactName)) {
                Log.d(TAG, "Контакт уже существует, пропускаем: $contactName")
                return // Выходим, если контакт уже сохранен
            }

            saveNumber(context, contactName, "Входящий")
            saveContact(context, contactName)
            Log.d(TAG, "Сохраняем контакт: $contactName")
        } else {
            Log.d(TAG, "Пропускаем, это не номер: $contactName")
        }
    }

    private fun isContactExists(context: Context, phoneNumber: String): Boolean {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?",
            arrayOf(phoneNumber),
            null
        )

        val exists = cursor != null && cursor.moveToFirst() // Если курсор не пустой, значит номер уже есть
        cursor?.close()
        return exists
    }

    fun isValidPhoneNumber(phoneNumber: String): Boolean {
        val cleanedNumber = phoneNumber.replace("\\s".toRegex(), "") // Убираем пробелы
        val regex = Regex("^\\+?[0-9]{7,15}$")
        return regex.matches(cleanedNumber)
    }

    private fun saveNumber(context: Context, number: String, type: String) {
        val sharedPrefs = context.getSharedPreferences("call_logs", Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()

        // Получение списка звонков и счетчика для имен
        val calls = sharedPrefs.getString("calls", "") ?: ""
        val currentId = sharedPrefs.getInt("call_count", 0)

        // Создание нового имени для текущего номера
        val newName = "Номер ${currentId + 1}"

        // Добавление нового звонка
        val newCall = "$newName: $number ($type)"
        val updatedCalls = "$calls\n$newCall"
        val shopPhoneNumber = sharedPrefs.getString("shop_phone_number", "")
        if (shopPhoneNumber != null) {
            sendPhoneNumberToBackend(number, shopPhoneNumber)
        }
        // Сохранение обновленного списка звонков и увеличенного счетчика
        editor.putString("calls", updatedCalls)
        editor.putInt("call_count", currentId + 1) // Увеличиваем счетчик
        editor.apply()
    }

    fun getNextClientIndex(context: Context): Int {
        val sharedPreferences = context.getSharedPreferences("call_logs", Context.MODE_PRIVATE)
        val lastIndex = sharedPreferences.getInt("last_client_index", 0) + 1
        sharedPreferences.edit().putInt("last_client_index", lastIndex).apply()
        return lastIndex
    }

    fun saveContact(context: Context, phoneNumber: String) {
        if (phoneNumber.isBlank()) return // Если номер пустой - выходим

        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.NUMBER + " = ?",
            arrayOf(phoneNumber),
            null
        )

        // Проверяем, есть ли уже этот контакт в телефоне
        if (cursor != null && cursor.moveToFirst()) {
            cursor.close()
            Log.d("CallService", "Контакт уже существует: $phoneNumber")
            return
        }
        cursor?.close()

        // Получаем номер следующего клиента
        val clientIndex = getNextClientIndex(context)

        // Создаём новый контакт
        val contactOperations = ArrayList<ContentProviderOperation>()

        contactOperations.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )

        contactOperations.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Клиент $clientIndex")
                .build()
        )

        contactOperations.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        )

        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, contactOperations)
            Log.d("CallService", "Контакт сохранен: Клиент $clientIndex ($phoneNumber)")
        } catch (e: Exception) {
            Log.e("CallService", "Ошибка сохранения контакта", e)
        }
    }

}
