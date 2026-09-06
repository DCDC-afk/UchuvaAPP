package com.example.uchuvatwinapp

import kotlin.math.max
import kotlin.math.min

// 1. ESTADOS DEL RASTREO
enum class TrackState { New, Tracked, Lost, Removed }

// 2. ESTRUCTURA DE UNA UCHUVA RASTREADA
class STrack(
    var caja: FloatArray,
    var confianza: Float,
    var clase: String
) {
    var id: Int = -1
    var estado = TrackState.New
    var framesPerdido = 0
    var historialClases = mutableListOf<String>()
    var historialX = mutableListOf<Float>()
    var historialY = mutableListOf<Float>()
    var confianzas = mutableListOf<Float>()

    val centroX: Float get() = (caja[0] + caja[2]) / 2f
    val centroY: Float get() = (caja[1] + caja[3]) / 2f

    init {
        historialClases.add(clase)
        historialX.add(centroX)
        historialY.add(centroY)
        confianzas.add(confianza)
    }
}

// 3. MOTOR BYTETRACK
class ByteTracker(
    private val trackHighThresh: Float = 0.5f,
    private val trackLowThresh: Float = 0.1f,
    private val newTrackThresh: Float = 0.6f,
    // Reducido a 15. Sin Kalman, una coordenada vieja genera IDs fantasmas muy rápido si el dron se mueve.
    private val trackBuffer: Int = 15
) {
    val tracksActivos = mutableListOf<STrack>()
    private var trackIdCount = 1

    fun separarDetecciones(deteccionesYolo: List<STrack>): Pair<List<STrack>, List<STrack>> {
        val altas = mutableListOf<STrack>()
        val bajas = mutableListOf<STrack>()
        for (det in deteccionesYolo) {
            if (det.confianza >= trackHighThresh) altas.add(det)
            else if (det.confianza >= trackLowThresh) bajas.add(det)
        }
        return Pair(altas, bajas)
    }

    fun calcularMatrizCostoDistancia(tracks: List<STrack>, detecciones: List<STrack>): Array<FloatArray> {
        val matriz = Array(tracks.size) { FloatArray(detecciones.size) }
        val maxDist = 120f // Reducido para que uchuvas de otras hojas no roben IDs lejanos

        for (i in tracks.indices) {
            for (j in detecciones.indices) {
                val dx = tracks[i].centroX - detecciones[j].centroX
                val dy = tracks[i].centroY - detecciones[j].centroY
                val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                matriz[i][j] = dist / maxDist
            }
        }
        return matriz
    }

    fun asignacionLineal(
        costMatrix: Array<FloatArray>,
        trackIndices: List<Int>,
        detIndices: List<Int>,
        umbralMatch: Float = 1.0f
    ): Triple<List<Pair<Int, Int>>, List<Int>, List<Int>> {
        val matches = mutableListOf<Pair<Int, Int>>()
        val unmatchedTracks = mutableListOf<Int>()
        val unmatchedDetections = mutableListOf<Int>()

        if (costMatrix.isEmpty() || costMatrix[0].isEmpty()) return Triple(matches, trackIndices, detIndices)

        val flatCosts = mutableListOf<Triple<Int, Int, Float>>()
        for (r in costMatrix.indices) {
            for (c in costMatrix[r].indices) {
                flatCosts.add(Triple(r, c, costMatrix[r][c]))
            }
        }

        flatCosts.sortBy { it.third }

        val usedRows = BooleanArray(costMatrix.size)
        val usedCols = BooleanArray(costMatrix[0].size)

        for ((r, c, cost) in flatCosts) {
            if (usedRows[r] || usedCols[c]) continue
            if (cost > umbralMatch) continue

            matches.add(Pair(trackIndices[r], detIndices[c]))
            usedRows[r] = true
            usedCols[c] = true
        }

        for (r in costMatrix.indices) if (!usedRows[r]) unmatchedTracks.add(trackIndices[r])
        for (c in costMatrix[0].indices) if (!usedCols[c]) unmatchedDetections.add(detIndices[c])

        return Triple(matches, unmatchedTracks, unmatchedDetections)
    }

    fun update(deteccionesFrame: List<STrack>): List<STrack> {
        val (detAltas, detBajas) = separarDetecciones(deteccionesFrame)

        // 1. MATCH Racha Alta
        val costMatrixAlta = calcularMatrizCostoDistancia(tracksActivos, detAltas)
        val (matchesAlta, uTrackAlta, uDetAlta) = asignacionLineal(
            costMatrixAlta,
            trackIndices = tracksActivos.indices.toList(),
            detIndices = detAltas.indices.toList(),
            umbralMatch = 1.0f
        )

        for ((tIdxGlobal, dIdx) in matchesAlta) {
            actualizarTrack(tracksActivos[tIdxGlobal], detAltas[dIdx])
        }

        // 2. MATCH Racha Baja
        // Permitimos que los NEW participen si perdieron un poco de confianza
        val costMatrixBaja = calcularMatrizCostoDistancia(uTrackAlta.map { tracksActivos[it] }, detBajas)
        val (matchesBaja, uTrackBajaLocal, _) = asignacionLineal(
            costMatrixBaja,
            trackIndices = uTrackAlta,
            detIndices = detBajas.indices.toList(),
            umbralMatch = 0.5f
        )

        for ((tIdxGlobal, dIdx) in matchesBaja) {
            actualizarTrack(tracksActivos[tIdxGlobal], detBajas[dIdx])
        }

        // 3. CASTIGAR A LOS PERDIDOS
        for (tIdxGlobal in uTrackBajaLocal) {
            val track = tracksActivos[tIdxGlobal]
            if (track.estado == TrackState.New) {
                track.estado = TrackState.Removed // Muere al instante si era falso
            } else {
                track.estado = TrackState.Lost
                track.framesPerdido++
                if (track.framesPerdido > trackBuffer) {
                    track.estado = TrackState.Removed
                }
            }
        }

        // 4. CREAR NUEVOS TRACKS
        for (dIdx in uDetAlta) {
            val det = detAltas[dIdx]
            if (det.confianza >= newTrackThresh) {
                det.id = trackIdCount++
                det.estado = TrackState.New
                tracksActivos.add(det)
            }
        }

        tracksActivos.removeAll { it.estado == TrackState.Removed }

        return tracksActivos.filter { it.framesPerdido == 0 }
    }

    private fun actualizarTrack(trackViejo: STrack, detNueva: STrack) {
        trackViejo.caja = detNueva.caja
        trackViejo.confianza = detNueva.confianza
        trackViejo.historialClases.add(detNueva.clase)
        trackViejo.historialX.add(detNueva.centroX)
        trackViejo.historialY.add(detNueva.centroY)
        trackViejo.confianzas.add(detNueva.confianza)
        trackViejo.framesPerdido = 0
        trackViejo.estado = TrackState.Tracked
    }
}