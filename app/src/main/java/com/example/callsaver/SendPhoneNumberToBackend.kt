package com.example.callsaver

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException

fun sendPhoneNumberToBackend(phoneNumber: String, shopPhoneNumber: String) {
    val client = OkHttpClient()

    // Создаем JSON объект с данными
    val jsonObject = JSONObject()
    jsonObject.put("phone_number", phoneNumber)
    jsonObject.put("shop_phone_number", shopPhoneNumber)

    // Логируем JSON перед отправкой
    Log.d("Request", jsonObject.toString())

    // Преобразуем JSON в тело запроса
    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
    val body = RequestBody.create(mediaType, jsonObject.toString())

    // Формируем запрос
    val request = Request.Builder()
        .url("http://91.243.71.120/api/leads/")  // Ваш API endpoint
        .post(body)
        .build()

    // Выполняем запрос в асинхронном режиме
    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("Network", "Ошибка при отправке запроса", e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (response.isSuccessful) {
                Log.d("Network", "Данные успешно отправлены")
            } else {
                Log.e("Network", "Ошибка на сервере: ${response.message}")
                Log.e("Network", "Ответ сервера: ${response.body?.string()}")
            }
        }
    })
}