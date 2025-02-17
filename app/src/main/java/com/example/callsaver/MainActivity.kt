package com.example.callsaver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.app.AlertDialog
import android.content.ContentProviderOperation
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {
    companion object {
        const val CHANNEL_ID = "call_saver_channel"
        const val REQUEST_CODE_FOREGROUND_SERVICE = 103
    }

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)

            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    Log.d("CallService", "Телефон не в звонке")
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    Log.d("CallService", "Входящий звонок от: $phoneNumber")
                    if (!phoneNumber.isNullOrBlank()) {
                        saveContact(this@MainActivity, phoneNumber)
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    Log.d("CallService", "Телефон в режиме разговора")
                }
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.READ_PHONE_STATE] == true) {
                // Разрешение на чтение состояния телефона получено
                initializePhoneStateListener()
            } else {
                Log.e("CallService", "Разрешение на доступ к состоянию звонков не предоставлено")
            }

            if (permissions[Manifest.permission.READ_CALL_LOG] == true) {
                // Разрешение на чтение истории звонков получено
                // Ваш код для работы с журналом звонков, если нужно
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запрос разрешений для чтения состояния телефона и истории звонков
        requestPermissionsIfNeeded()
        checkAndRequestPermissions(this)
        // Запуск фонового сервиса
        startCallService()
        createNotificationChannel()
        setContent {
            CallLoggerApp(context = this)
        }
        checkAndRequestBatteryOptimization(this)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.MANAGE_OWN_CALLS
        )

        val permissionsToRequest = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            // Разрешения уже предоставлены, запускаем слушатель
            initializePhoneStateListener()
        }
    }

    private fun initializePhoneStateListener() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } else {
            Log.e("CallService", "Нет разрешения на прослушивание состояния звонков")
        }
    }

    fun checkAndRequestPermissions(activity: Activity) {
        val permissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missingPermissions.toTypedArray(), 1001)
        }
    }

    private fun startCallService() {
        val serviceIntent = Intent(this, CallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Call Saver Channel"
            val descriptionText = "Channel for call saver app"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }
}

@Composable
fun CallLoggerApp(context: Context) {
    val sharedPreferences = context.getSharedPreferences("call_logs", Context.MODE_PRIVATE)
    var phoneNumber by remember { mutableStateOf("") }
    var shopPhoneNumber by remember {
        mutableStateOf(
            sharedPreferences.getString(
                "shop_phone_number",
                ""
            ) ?: ""
        )
    }
    val (calls, setCalls) = remember { mutableStateOf<List<String>>(emptyList()) }

    // Обновление списка звонков
    fun updateCallLogs() {
        val savedCalls = sharedPreferences.getString("calls", "Нет данных") ?: "Нет данных"
        setCalls(savedCalls.split("\n").filter { it.isNotEmpty() })
    }

    fun saveShopPhoneNumber(phone: String) {
        sharedPreferences.edit().putString("shop_phone_number", phone).apply()
        shopPhoneNumber = phone // Обновляем номер в UI
        Toast.makeText(context, "Номер успешно сохранён!", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        updateCallLogs()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Журнал вызовов",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Отображение номера телефона магазина
                if (shopPhoneNumber.isNotEmpty()) {
                    Text(
                        text = "Номер магазина: $shopPhoneNumber",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    Text(
                        text = "Номер магазина не установлен",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                // Поле для ввода номера телефона магазина
                TextField(
                    value = shopPhoneNumber,
                    onValueChange = { newValue ->
                        shopPhoneNumber = newValue
                    },
                    label = { Text("Номер телефона магазина") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        saveShopPhoneNumber(shopPhoneNumber) // Сохраняем номер и показываем `Toast`
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сохранить номер магазина")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { updateCallLogs() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Обновить список")
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (calls.isEmpty()) {
                    Text(
                        text = "Нет записанных звонков.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn {
                        items(calls) { call ->
                            CallLogItem(number = call)
                        }
                    }
                }
            }
        }
    }
}

fun checkAndRequestBatteryOptimization(activity: Activity) {
    val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    val packageName = activity.packageName

    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
        // Оптимизация включена → предлагаем отключить
        AlertDialog.Builder(activity) // Используем activity, а не applicationContext
            .setTitle("Отключение оптимизации батареи")
            .setMessage("Для стабильной работы приложения отключите оптимизацию батареи.")
            .setPositiveButton("ОК") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}

@Composable
fun CallLogItem(number: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        elevation = CardDefaults.elevatedCardElevation(4.dp)
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp)
        )
    }
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

    if (cursor != null) {
        try {
            if (cursor.moveToFirst()) {
                Log.d("CallService", "Контакт уже существует: $phoneNumber")
                return
            }
        } finally {
            cursor.close()
        }
    }

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
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            )
            .withValue(
                ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                "Клиент $clientIndex"
            )
            .build()
    )

    contactOperations.add(
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            )
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
            .withValue(
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            )
            .build()
    )

    try {
        resolver.applyBatch(ContactsContract.AUTHORITY, contactOperations)
        Log.d("CallService", "Контакт сохранен: Клиент $clientIndex ($phoneNumber)")
    } catch (e: Exception) {
        Log.e("CallService", "Ошибка сохранения контакта", e)
    }
}
