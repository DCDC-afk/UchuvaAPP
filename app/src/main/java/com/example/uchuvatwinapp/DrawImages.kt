package com.example.uchuvatwinapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.util.Locale

class DrawImages(private val context: Context) {

    private val colorMaduro = Color.parseColor("#AB47BC")
    private val colorInmaduro = Color.parseColor("#4CAF50")
    private val colorArucoVerde = Color.parseColor("#B5EC73")
    private val colorDefault = Color.parseColor("#D36D42")

    fun invoke(results: List<SegmentationResult>, frameWidth: Int, frameHeight: Int): Bitmap {
        val combined = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)

        results.forEach { result ->
            val colorCaja = when (result.box.clsName.lowercase()) {
                "maduro" -> colorMaduro
                "inmaduro" -> colorInmaduro
                "uchuva", "capacho" -> colorArucoVerde
                else -> colorDefault
            }
            dibujarCaja(canvas, frameWidth, frameHeight, result, colorCaja)
        }
        return combined
    }

    private fun dibujarCaja(
        canvas: Canvas,
        width: Int,
        height: Int,
        result: SegmentationResult,
        colorCaja: Int
    ) {
        val box = result.box
        val boxPaint = Paint().apply {
            color = colorCaja
            strokeWidth = 2.5F
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val left = box.x1 * width
        val top = box.y1 * height
        val right = box.x2 * width
        val bottom = box.y2 * height

        canvas.drawRect(left, top, right, bottom, boxPaint)

        val textColor = if (colorCaja == colorArucoVerde) Color.BLACK else Color.WHITE
        val textPaint = Paint().apply {
            color = textColor
            style = Paint.Style.FILL
            textSize = 13f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val textBackgroundPaint = Paint().apply {
            color = colorCaja
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val porcentajeConfianza = (box.cnf * 100).toInt()
        var labelText = "${box.clsName} $porcentajeConfianza%"

        // Si el motor calculó la posición absoluta mediante el ArUco, la mostramos
        if (result.distanciaAbsolutaM != null) {
            val distStr = String.format(Locale.US, "%.2fm", result.distanciaAbsolutaM)
            labelText += " | $distStr"
        }

        val bounds = Rect()
        textPaint.getTextBounds(labelText, 0, labelText.length, bounds)

        val textWidth = bounds.width()
        val textHeight = bounds.height()
        val padH = 4f
        val padV = 2f

        val badgeLeft = left
        val badgeBottom = top
        val badgeTop = top - textHeight - (padV * 2)
        val badgeRight = left + textWidth + (padH * 2)

        canvas.drawRect(badgeLeft, badgeTop, badgeRight, badgeBottom, textBackgroundPaint)
        canvas.drawText(labelText, badgeLeft + padH, badgeBottom - padV - 1f, textPaint)
    }

    fun dibujarClasificacionGlobal(clase: String, confianza: Float, width: Int, height: Int): Bitmap {
        val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        val colorFondo = when (clase.lowercase()) {
            "maduro" -> colorMaduro
            "inmaduro" -> colorInmaduro
            else -> colorDefault
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textSize = 60f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val porcentajeConfianza = (confianza * 100).toInt()
        val text = "$clase: $porcentajeConfianza%"
        val bgPaint = Paint().apply {
            color = Color.argb(180, Color.red(colorFondo), Color.green(colorFondo), Color.blue(colorFondo))
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val centerX = width / 2f
        val topY = 150f
        canvas.drawRect(centerX - 350f, topY - 80f, centerX + 350f, topY + 40f, bgPaint)
        canvas.drawText(text, centerX, topY, textPaint)
        return combined
    }
}