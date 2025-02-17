package com.example.callsaver.ui

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import com.example.callsaver.sendPhoneNumberToBackend

class CallListener(private val context: Context) : PhoneStateListener() {
    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
        super.onCallStateChanged(state, incomingNumber)

        if (state == TelephonyManager.CALL_STATE_RINGING && incomingNumber != null) {
            Log.d("CallListener", "Incoming call: $incomingNumber")
            saveNumber(context, incomingNumber, "Входящий")
            if (!incomingNumber.isNullOrBlank()) {
                saveContact(context, incomingNumber)
            }
        }
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