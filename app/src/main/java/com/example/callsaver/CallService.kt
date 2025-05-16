package com.example.callsaver

import android.app.*
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.example.callsaver.ui.CallListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CallService : Service() {
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var callListener: CallListener
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()

        // Получаем сохранённый номер телефона
        val sharedPreferences = getSharedPreferences("call_logs", Context.MODE_PRIVATE)
        val shopPhoneNumber = sharedPreferences.getString("shop_phone_number", null)

        startRepeatingRequest(shopPhoneNumber)

        Log.d("CallService", "Сервис создан")
        callListener = CallListener(this)
        startForegroundService()
    }

    private fun startRepeatingRequest(phoneNumber: String?) {
        serviceScope.launch {
            while (isActive) {
                if (phoneNumber != null) {
                    sendPostRequest(phoneNumber)
                } else {
                    Log.e("CallService", "shop_phone_number не найден в SharedPreferences")
                }
                delay(15_000) // 15 секунд
            }
        }
    }

    private fun sendPostRequest(phoneNumber: String) {
        val url = "http://91.243.71.120/api/recent-leads/"
        val json = JSONObject().apply {
            put("shop_phone_number", phoneNumber)
        }

        val body = RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

// 🔍 Логируем запрос
        Log.d("CallService", "Отправка запроса:")
        Log.d("CallService", "URL: $url")
        Log.d("CallService", "Тело запроса: ${json.toString()}")
        Log.d("CallService", "Заголовки: ${request.headers}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("CallService", "Ошибка запроса: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    Log.d("CallService", "Ответ от сервера: $responseData")

                    try {
                        val jsonArray = JSONArray(responseData)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val number = obj.getString("phone_number")
                            Log.e("CallService", number)
                            // Пропускаем номер магазина
                            if (number != phoneNumber) {
                                Log.e("CallService", "Метод $number")
                                saveNumber(applicationContext, number, "Whatsapp")
                                saveContact(applicationContext, number)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CallService", "Ошибка разбора JSON: ${e.message}")
                    }
                } else {
                    Log.e("CallService", "Ошибка ответа: ${response.code}")
                }
            }
        })
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CallService", "Call tracking service started")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(callListener, PhoneStateListener.LISTEN_CALL_STATE)

        return START_STICKY // Позволяет сервису перезапускаться после остановки
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("CallService", "Сервис уничтожен, перезапускаем...")
        Toast.makeText(baseContext, "Сервис уничтожен, перезапускаем...", Toast.LENGTH_LONG).show()
        // Остановить слушателя
        telephonyManager.listen(callListener, PhoneStateListener.LISTEN_NONE)

        // Перезапуск сервиса
        restartService()
        restartServiceWithAlarm()
        val workRequest = PeriodicWorkRequest.Builder(
            RestartServiceWorker::class.java,
            15, TimeUnit.MINUTES // Интервал перезапуска
        ).build()

        WorkManager.getInstance(baseContext).enqueue(workRequest)
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

    private fun restartServiceWithAlarm() {
        val intent = Intent(this, CallService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = System.currentTimeMillis() + 10 * 1000 // Через 10 секунд

        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
    }

    private fun saveNumber(context: Context, number: String, type: String) {
        val sharedPrefs = context.getSharedPreferences("call_logs", Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()

        val calls = sharedPrefs.getString("calls", "") ?: ""
        val currentId = sharedPrefs.getInt("call_count", 0)

        // Проверка: если номер уже есть в списке — не добавляем
        if (calls.contains(number)) {
            Log.d("CallService", "Номер $number уже сохранён, пропускаем.")
            return
        }

        val newName = "Номер ${currentId + 1}"
        val newCall = "$newName: $number ($type)"
        val updatedCalls = "$calls\n$newCall"

        editor.putString("calls", updatedCalls)
        editor.putInt("call_count", currentId + 1)
        editor.apply()

        Log.d("CallService", "Сохранён новый номер: $newCall")
    }

    fun getNextClientIndex(context: Context): Int {
        val sharedPreferences = context.getSharedPreferences("call_logs", Context.MODE_PRIVATE)
        val lastIndex = sharedPreferences.getInt("last_client_index", 0) + 1
        sharedPreferences.edit().putInt("last_client_index", lastIndex).apply()
        return lastIndex
    }

    fun saveContact(context: Context, phoneNumber: String) {
        if (phoneNumber.isBlank()) return // Если номер пустой - выходим
        if (isContactExists(applicationContext, phoneNumber)) return // Если номер пустой - выходим

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

    override fun onBind(intent: Intent?): IBinder? = null
}
