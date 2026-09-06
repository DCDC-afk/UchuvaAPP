package com.example.uchuvatwinapp

import com.google.gson.annotations.SerializedName

data class ReporteUchuva(
    @SerializedName("id_tracking") val idTracking: Int,
    @SerializedName("id_orden_ordenado_y") val idOrdenY: Int,
    @SerializedName("estado") val estado: String,
    @SerializedName("confianza_clasificacion") val confianza: Float,
    @SerializedName("coordenada_y_px") val coordenadaYPx: Int,
    @SerializedName("coordenada_y_m") val coordenadaYM: Float,
    @SerializedName("frame_cruce") val frameCruce: Int
)

data class NodoDigital(
    val id: Int,
    val estado: String,
    val ejeXM: Float,
    val ejeYM: Float,
    val confianza: Float,
    val frameCruce: Int
)

data class ArbustoDigital(
    val idArbusto: Int,
    val uchuvas: List<NodoDigital>,
    val limiteSuperiorYM: Float,
    val limiteInferiorYM: Float,
    val centroXM: Float
)