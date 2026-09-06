package com.example.uchuvatwinapp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class LoteRemotoDTO(
    val nombreLote: String,
    val videos: List<String>,
    val fechaOriginal: String = "" // NUEVO: Captura la fecha del dron
)

data class TelemetriaDTO(
    val bateria: String,
    val almacenamiento: String
)

object ClienteDronHTTP {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    var ultimoErrorDiagnostico: String = ""

    fun obtenerTelemetria(ipDron: String, puerto: Int = 8080): TelemetriaDTO? {
        val url = "http://$ipDron:$puerto/api/telemetria"
        val request = Request.Builder().url(url).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) return null
                val jsonString = response.body!!.string()
                gson.fromJson(jsonString, TelemetriaDTO::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun obtenerEstructuraLotesRemotos(ipDron: String, puerto: Int = 8080): List<LoteRemotoDTO> {
        val url = "http://$ipDron:$puerto/api/lotes"
        val request = Request.Builder().url(url).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    ultimoErrorDiagnostico = "HTTP Error Code: ${response.code}"
                    return emptyList()
                }
                val jsonString = response.body!!.string()
                val listType = object : TypeToken<List<LoteRemotoDTO>>() {}.type
                gson.fromJson(jsonString, listType) ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ultimoErrorDiagnostico = "${e.javaClass.simpleName}: ${e.message}"
            emptyList()
        }
    }

    fun descargarVideoRemoto(
        ipDron: String,
        puerto: Int = 8080,
        nombreLote: String,
        nombreArchivo: String,
        archivoDestinoLocal: File
    ): Boolean {
        if (archivoDestinoLocal.exists() && archivoDestinoLocal.length() > 0) {
            return true
        }

        val url = "http://$ipDron:$puerto/api/descargar?lote=$nombreLote&file=$nombreArchivo"
        val request = Request.Builder().url(url).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    Log.e("ClienteDronHTTP", "Fallo descarga HTTP: ${response.code} para $nombreArchivo")
                    return false
                }

                if (archivoDestinoLocal.exists()) {
                    archivoDestinoLocal.delete()
                }

                val inputStream = response.body!!.byteStream()
                val outputStream = FileOutputStream(archivoDestinoLocal)
                inputStream.use { input -> outputStream.use { output -> input.copyTo(output) } }

                Log.i("ClienteDronHTTP", "¡Video descargado con éxito: $nombreArchivo -> ${archivoDestinoLocal.absolutePath}")
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ClienteDronHTTP", "Excepción crítica descargando $nombreArchivo: ${e.message}")
            false
        }
    }
}