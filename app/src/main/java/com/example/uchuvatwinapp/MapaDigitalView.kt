package com.example.uchuvatwinapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat

class MapaDigitalView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var listaArbustos: List<ArbustoDigital> = emptyList()
    var uchuvaSeleccionada: NodoDigital? = null
    var onUchuvaSeleccionadaListener: ((NodoDigital?) -> Unit)? = null

    // --- ESCALAS INDEPENDIENTES PARA X E Y (Valores Óptimos) ---
    var pixelesPorMetroX = 604f
    var pixelesPorMetroY = 3000f

    // --- VARIABLES DE AJUSTE MODULAR DE SURCOS (Valores Óptimos) ---
    var distanciaEntreLinksMetros = 0.08f
    var escalaSVGModulos = 0.29f
    var offsetHeadMetros = -0.06f
    var recorteTailMetros = 0.14f

    private val paintGrilla = Paint().apply {
        color = Color.parseColor("#1C1917")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val paintSeleccionAnillo = Paint().apply {
        color = Color.parseColor("#FFF59D")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val paintTexto = Paint().apply {
        color = Color.parseColor("#BCAAA4")
        textSize = 32f
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    private val svgHead: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_surco_head1)
    private val svgTail1: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_surco_tail1)
    private val svgTail2: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_surco_tail2)

    private val svgLinks = listOf(
        ContextCompat.getDrawable(context, R.drawable.ic_surco_link1),
        ContextCompat.getDrawable(context, R.drawable.ic_surco_link2),
        ContextCompat.getDrawable(context, R.drawable.ic_surco_link3),
        ContextCompat.getDrawable(context, R.drawable.ic_surco_link4)
    )

    private val svgUchuvaMadura: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_uchuva_madura_pixel)
    private val svgUchuvaInmadura: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_uchuva_inmadura_pixel)

    private val matrizTransformacion = Matrix()
    private var factorEscala = 1.0f
    private val detectorZoom: ScaleGestureDetector
    private var ultimoTouchX = 0f
    private var ultimoTouchY = 0f
    private var esClicSimple = false

    init {
        detectorZoom = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                factorEscala *= detector.scaleFactor
                factorEscala = factorEscala.coerceIn(0.1f, 15.0f)
                matrizTransformacion.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
                invalidate()
                return true
            }
        })
    }

    fun cargarDatos(arbustos: List<ArbustoDigital>) {
        this.listaArbustos = arbustos
        this.uchuvaSeleccionada = null
        if (arbustos.isNotEmpty()) {
            centrarCamaraEnDatos()
        }
        invalidate()
    }

    private fun centrarCamaraEnDatos() {
        val todasLasUchuvas = listaArbustos.flatMap { it.uchuvas }
        if (todasLasUchuvas.isEmpty()) return

        val minXMetros = todasLasUchuvas.minOf { it.ejeXM }
        val maxXMetros = todasLasUchuvas.maxOf { it.ejeXM }
        val minYMetros = todasLasUchuvas.minOf { it.ejeYM }
        val maxYMetros = todasLasUchuvas.maxOf { it.ejeYM }

        val centroXPx = ((minXMetros + maxXMetros) / 2f) * pixelesPorMetroX
        val centroYPx = ((minYMetros + maxYMetros) / 2f) * pixelesPorMetroY

        matrizTransformacion.reset()
        factorEscala = 0.3f

        val centroPantallaX = if (width > 0) width / 2f else 500f
        val centroPantallaY = if (height > 0) height / 2f else 500f

        matrizTransformacion.postScale(factorEscala, factorEscala)
        matrizTransformacion.postTranslate(centroPantallaX - (centroXPx * factorEscala), centroPantallaY - (centroYPx * factorEscala))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detectorZoom.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ultimoTouchX = event.x
                ultimoTouchY = event.y
                esClicSimple = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.x - ultimoTouchX)
                val dy = Math.abs(event.y - ultimoTouchY)
                if (dx > 10f || dy > 10f) esClicSimple = false

                if (!detectorZoom.isInProgress) {
                    matrizTransformacion.postTranslate(event.x - ultimoTouchX, event.y - ultimoTouchY)
                    ultimoTouchX = event.x
                    ultimoTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (esClicSimple && !detectorZoom.isInProgress) procesarToqueEnMapa(event.x, event.y)
            }
        }
        return true
    }

    private fun procesarToqueEnMapa(touchX: Float, touchY: Float) {
        val matrizInversa = Matrix()
        if (!matrizTransformacion.invert(matrizInversa)) return

        val puntoPantalla = floatArrayOf(touchX, touchY)
        matrizInversa.mapPoints(puntoPantalla)
        val mapaX = puntoPantalla[0]
        val mapaY = puntoPantalla[1]

        val radioUchuvaPx = (30f / Math.sqrt(factorEscala.toDouble())).toFloat()
        var uchuvaMasCercana: NodoDigital? = null
        var menorDistancia = Float.MAX_VALUE

        for (arbusto in listaArbustos) {
            for (uchuva in arbusto.uchuvas) {
                val xPx = uchuva.ejeXM * pixelesPorMetroX
                val yPx = uchuva.ejeYM * pixelesPorMetroY
                val distancia = Math.hypot((mapaX - xPx).toDouble(), (mapaY - yPx).toDouble()).toFloat()

                if (distancia <= radioUchuvaPx && distancia < menorDistancia) {
                    menorDistancia = distancia
                    uchuvaMasCercana = uchuva
                }
            }
        }
        uchuvaSeleccionada = uchuvaMasCercana
        onUchuvaSeleccionadaListener?.invoke(uchuvaMasCercana)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(matrizTransformacion)

        dibujarGrillaMetrica(canvas)

        for (arbusto in listaArbustos) {
            dibujarSurcoModular(canvas, arbusto)
            dibujarUchuvas(canvas, arbusto)

            val centroXPx = arbusto.centroXM * pixelesPorMetroX
            val limiteSupYPx = (arbusto.limiteSuperiorYM + recorteTailMetros) * pixelesPorMetroY
            canvas.drawText("SURCO #${arbusto.idArbusto}", centroXPx, limiteSupYPx - 80f, paintTexto)
        }

        canvas.restore()
    }

    private fun dibujarSurcoModular(canvas: Canvas, arbusto: ArbustoDigital) {
        if (svgHead == null || svgTail1 == null || svgTail2 == null || svgLinks.any { it == null }) return

        val inicioOriginalY = arbusto.limiteInferiorYM
        val finOriginalY = arbusto.limiteSuperiorYM

        val posicionHeadY = inicioOriginalY + offsetHeadMetros

        val distanciaBruta = posicionHeadY - finOriginalY
        val distanciaEfectiva = Math.max(0.0, (distanciaBruta - recorteTailMetros).toDouble())

        var numLinksNecesarios = Math.ceil(distanciaEfectiva / distanciaEntreLinksMetros).toInt()
        if (numLinksNecesarios < 1) numLinksNecesarios = 0

        val esNumLinksImpar = numLinksNecesarios % 2 != 0
        val tailSvg = if (esNumLinksImpar) svgTail2 else svgTail1

        val centroXPx = arbusto.centroXM * pixelesPorMetroX

        // HEAD
        dibujarModulo(canvas, svgHead, centroXPx, posicionHeadY * pixelesPorMetroY)

        // LINKS
        for (i in 1..numLinksNecesarios) {
            val linkActual = svgLinks[(i - 1) % svgLinks.size]!!
            val yActualMetros = posicionHeadY - (i * distanciaEntreLinksMetros)
            dibujarModulo(canvas, linkActual, centroXPx, yActualMetros * pixelesPorMetroY)
        }

        // TAIL
        val yFinalMetros = posicionHeadY - ((numLinksNecesarios + 1) * distanciaEntreLinksMetros)
        dibujarModulo(canvas, tailSvg, centroXPx, yFinalMetros * pixelesPorMetroY)
    }

    private fun dibujarModulo(canvas: Canvas, drawable: Drawable, centerX: Float, centerY: Float) {
        val widthOriginal = drawable.intrinsicWidth.toFloat()
        val heightOriginal = drawable.intrinsicHeight.toFloat()

        val widthEscalado = widthOriginal * escalaSVGModulos
        val heightEscalado = heightOriginal * escalaSVGModulos

        val left = (centerX - (widthEscalado / 2f)).toInt()
        val top = (centerY - (heightEscalado / 2f)).toInt()
        val right = (centerX + (widthEscalado / 2f)).toInt()
        val bottom = (centerY + (heightEscalado / 2f)).toInt()

        drawable.setBounds(left, top, right, bottom)
        drawable.draw(canvas)
    }

    private fun dibujarUchuvas(canvas: Canvas, arbusto: ArbustoDigital) {
        val radioDibujo = (24f / Math.sqrt(factorEscala.toDouble())).toFloat().coerceIn(12f, 60f)

        for (uchuva in arbusto.uchuvas) {
            val xPx = uchuva.ejeXM * pixelesPorMetroX
            val yPx = uchuva.ejeYM * pixelesPorMetroY
            val svgActual = if (uchuva.estado == "Maduro") svgUchuvaMadura else svgUchuvaInmadura

            if (svgActual != null) {
                val left = (xPx - radioDibujo).toInt()
                val top = (yPx - radioDibujo).toInt()
                val right = (xPx + radioDibujo).toInt()
                val bottom = (yPx + radioDibujo).toInt()

                svgActual.setBounds(left, top, right, bottom)
                svgActual.draw(canvas)
            }

            if (uchuva == uchuvaSeleccionada) {
                canvas.drawCircle(xPx, yPx, radioDibujo + 8f, paintSeleccionAnillo)
            }
        }
    }

    private fun dibujarGrillaMetrica(canvas: Canvas) {
        val tamañoCeldaPxX = 2.0f * pixelesPorMetroX
        val tamañoCeldaPxY = 2.0f * pixelesPorMetroY
        val limite = 20000f

        var x = -limite
        while (x < limite) {
            canvas.drawLine(x, -limite, x, limite, paintGrilla)
            x += tamañoCeldaPxX
        }
        var y = -limite
        while (y < limite) {
            canvas.drawLine(-limite, y, limite, y, paintGrilla)
            y += tamañoCeldaPxY
        }
    }
}