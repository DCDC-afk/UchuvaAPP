package com.example.uchuvatwinapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat

class DrawImages(private val context: Context) {

    private val boxColors = listOf(
        R.color.overlay_orange,
        R.color.overlay_blue,
        R.color.overlay_green,
        R.color.overlay_red,
        R.color.overlay_pink,
        R.color.overlay_cyan,
        R.color.overlay_purple,
        R.color.overlay_gray,
        R.color.overlay_teal,
        R.color.overlay_yellow,
    )

    fun invoke(results: List<SegmentationResult>) : Bitmap {
        // Obtenemos el ancho y alto a partir de la resolución de la pantalla de la tablet (el frame original)
        // Ya no leemos desde la máscara
        val width = 640 // Si necesitas que el overlay escale a pantalla completa dinámicamente,
        val height = 480 // este canvas será ajustado por el ScaleType del ImageView

        val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)

        results.forEach { result ->
            val colorResId = boxColors[result.box.cls % 10]
            dibujarCaja(context, canvas, width, height, result.box, colorResId)
        }
        return combined
    }

    private fun dibujarCaja(context: Context, canvas: Canvas, width: Int, height: Int, box: Output0, overlayColorResId: Int) {
        val overlayColor = ContextCompat.getColor(context, overlayColorResId)

        val boxPaint = Paint().apply {
            color = overlayColor
            strokeWidth = 4F
            style = Paint.Style.STROKE
        }

        val left = (box.x1 * width).toInt()
        val top = (box.y1 * height).toInt()
        val right = (box.x2 * width).toInt()
        val bottom = (box.y2 * height).toInt()

        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), boxPaint)

        val textBackgroundPaint = Paint().apply {
            color = overlayColor
            style = Paint.Style.FILL
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textSize = 14f
            isAntiAlias = true
        }

        // Formatear el texto de etiqueta + confianza (Ej: "Maduro 89%")
        val porcentajeConfianza = (box.cnf * 100).toInt()
        val labelText = "${box.clsName} $porcentajeConfianza%"

        val bounds = android.graphics.Rect()
        textPaint.getTextBounds(labelText, 0, labelText.length, bounds)

        val textWidth = bounds.width()
        val textHeight = bounds.height()
        val padding = 4

        canvas.drawRect(
            left.toFloat() - 2, // Ajuste para que se solape perfecto con el borde izquierdo
            top.toFloat() - textHeight - 2 * padding,
            left.toFloat() + textWidth + 2 * padding.toFloat(),
            top.toFloat(),
            textBackgroundPaint
        )

        canvas.drawText(labelText, left.toFloat() + padding, top.toFloat() - padding.toFloat(), textPaint)
    }
}