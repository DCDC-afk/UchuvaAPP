package com.example.uchuvatwinapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteTrackTest {

    @Test
    fun pruebaDeFuego_OcultamientoPorHoja() {
        println("=== INICIANDO TEST FINAL: SIMULACIÓN DE OCULTAMIENTO (BYTETRACK) ===")
        val tracker = ByteTracker(trackHighThresh = 0.5f, trackLowThresh = 0.1f)

        // --- FRAME 1: Uchuva perfectamente visible ---
        val detFrame1 = listOf(
            STrack(floatArrayOf(10f, 10f, 50f, 50f), 0.85f, "Maduro")
        )
        val resultFrame1 = tracker.update(detFrame1)

        assertEquals("Debería haber 1 track activo", 1, resultFrame1.size)
        val idAsignado = resultFrame1[0].id
        println("Frame 1: Uchuva detectada y asignada con ID: $idAsignado")

        // --- FRAME 2: La uchuva se movió un poco y una hoja la tapa (Confianza 0.25) ---
        // (En el algoritmo anterior de GestorProcesamiento, habría sido ignorada)
        val detFrame2 = listOf(
            STrack(floatArrayOf(15f, 15f, 55f, 55f), 0.25f, "Maduro")
        )
        val resultFrame2 = tracker.update(detFrame2)

        assertEquals("El track debe sobrevivir por Asociación Secundaria", 1, resultFrame2.size)
        assertEquals("Debe conservar exactamente el mismo ID", idAsignado, resultFrame2[0].id)
        println("Frame 2: ¡Éxito! La uchuva sobrevivió al ocultamiento manteniendo su ID: ${resultFrame2[0].id}")

        // --- FRAME 3: La uchuva desapareció de la pantalla por completo ---
        val detFrame3 = emptyList<STrack>()
        val resultFrame3 = tracker.update(detFrame3)

        assertEquals("Ya no se debe renderizar (no está visible)", 0, resultFrame3.size)
        assertEquals("Pero el tracker debe guardarla en memoria", 1, tracker.tracksActivos.size)
        assertEquals("Y debe estar en estado LOST", TrackState.Lost, tracker.tracksActivos[0].estado)
        println("Frame 3: ¡Éxito! La uchuva está en estado Perdido (Memoria Activa).")

        println("✅ LA PRUEBA DE FUEGO FUE SUPERADA CON ÉXITO.")
    }
}