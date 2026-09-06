package com.example.uchuvatwinapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

class DrawImages(private val context: Context) {

    private val colorMaduro = Color.parseColor("#AB47BC")
    private val colorInmaduro = Color.parseColor("#4CAF50")
    private val colorArucoVerde = Color.parseColor("#B5EC73") // Verde pastel vibrante
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
            dibujarCaja(canvas, frameWidth, frameHeight, result.box, colorCaja)
        }
        return combined
    }

    private fun dibujarCaja(
        canvas: Canvas,
        width: Int,
        height: Int,
        box: Output0,
        colorCaja: Int
    ) {
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

        // Usar texto negro si el color de la caja es el verde claro de Aruco
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
        val labelText = "${box.clsName} $porcentajeConfianza%"

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
}