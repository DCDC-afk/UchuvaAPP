package com.example.uchuvatwinapp

import android.content.Context
import android.os.Environment
import java.io.File

object GestorLotes {

    private const val CARPETA_RAIZ = "UchuvaTwin_Lotes"

    /**
     * Obtiene o crea la carpeta de lotes en el almacenamiento privado seguro de la app.
     */
    fun obtenerCarpetaRaizLotes(context: Context): File {
        val directorioSeguro = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val carpetaLotes = File(directorioSeguro, CARPETA_RAIZ)

        if (!carpetaLotes.exists()) {
            carpetaLotes.mkdirs()
        }
        return carpetaLotes
    }

    fun crearSubcarpetaLote(context: Context, nombreLote: String): File {
        val carpetaRaiz = obtenerCarpetaRaizLotes(context)
        val nuevaSubcarpeta = File(carpetaRaiz, nombreLote)

        if (!nuevaSubcarpeta.exists()) {
            nuevaSubcarpeta.mkdirs()
        }
        return nuevaSubcarpeta
    }

    fun listarLotesExistentes(context: Context): List<File> {
        val carpetaRaiz = obtenerCarpetaRaizLotes(context)
        return carpetaRaiz.listFiles { file -> file.isDirectory }?.toList() ?: emptyList()
    }
}