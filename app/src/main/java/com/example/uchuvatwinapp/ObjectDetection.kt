package com.example.uchuvatwinapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class ObjectDetection(
    context: Context,
    modelPath: String,
    private val listener: InstanceSegmentation.InstanceSegmentationListener,
    private val message: (String) -> Unit
) {
    private var interpreter: Interpreter
    private var labels = listOf("Uchuva")

    private var tensorWidth = 640
    private var tensorHeight = 640
    private var isNCHW = false

    private var isEnd2End = false
    private var isTransposed = false
    private var numBoxes = 0
    private var numChannels = 0
    private var shapeArray = intArrayOf()
    private var logCounter = 0

    init {
        try {
            val options = Interpreter.Options().apply { setNumThreads(4) }
            val model = FileUtil.loadMappedFile(context, modelPath)
            interpreter = Interpreter(model, options)

            val inputShape = interpreter.getInputTensor(0)?.shape()
            if (inputShape != null && inputShape.size >= 3) {
                if (inputShape[1] == 3) {
                    isNCHW = true
                    tensorWidth = inputShape[2]
                    tensorHeight = inputShape[3]
                } else {
                    tensorWidth = inputShape[1]
                    tensorHeight = inputShape[2]
                }
            }

            val outputShape = interpreter.getOutputTensor(0)?.shape()
            if (outputShape != null && outputShape.size == 3) {
                shapeArray = outputShape
                if (outputShape[2] == 6 || outputShape[2] == 7) {
                    isEnd2End = true
                    numBoxes = outputShape[1]
                    numChannels = outputShape[2]
                } else if (outputShape[1] == 5 || outputShape[1] == 6) {
                    isTransposed = true
                    numChannels = outputShape[1]
                    numBoxes = outputShape[2]
                } else if (outputShape[2] == 5 || outputShape[2] == 6) {
                    isTransposed = false
                    numChannels = outputShape[2]
                    numBoxes = outputShape[1]
                }
            }
        } catch (e: Exception) {
            Log.e("UchuvaVision", "Fallo severo al iniciar ObjectDetection", e)
            throw e
        }
    }

    fun invoke(frame: Bitmap) {
        if (shapeArray.isEmpty()) return

        var preProcessTime = SystemClock.uptimeMillis()
        val imageBuffer = preProcess(frame)

        val outputBuffer = TensorBuffer.createFixedSize(shapeArray, DataType.FLOAT32)

        preProcessTime = SystemClock.uptimeMillis() - preProcessTime
        var interfaceTime = SystemClock.uptimeMillis()

        interpreter.run(imageBuffer, outputBuffer.buffer.rewind())

        interfaceTime = SystemClock.uptimeMillis() - interfaceTime
        var postProcessTime = SystemClock.uptimeMillis()

        val bestBoxes = extractBoxes(outputBuffer.floatArray, frame.width, frame.height) ?: run {
            listener.onEmpty()
            return
        }

        val segmentationResults = bestBoxes.map {
            SegmentationResult(box = it, mask = emptyArray())
        }

        postProcessTime = SystemClock.uptimeMillis() - postProcessTime
        listener.onDetect(interfaceTime, segmentationResults, preProcessTime, postProcessTime, frame.width, frame.height)
    }

    private fun preProcess(frame: Bitmap): ByteBuffer {
        val scale = min(tensorWidth.toFloat() / frame.width, tensorHeight.toFloat() / frame.height)
        val nw = (frame.width * scale).toInt()
        val nh = (frame.height * scale).toInt()

        val resizedBitmap = Bitmap.createScaledBitmap(frame, nw, nh, true)
        val paddedBitmap = Bitmap.createBitmap(tensorWidth, tensorHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val leftPad = (tensorWidth - nw) / 2f
        val topPad = (tensorHeight - nh) / 2f
        canvas.drawBitmap(resizedBitmap, leftPad, topPad, null)

        val inputBuffer = ByteBuffer.allocateDirect(1 * tensorWidth * tensorHeight * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        val intValues = IntArray(tensorWidth * tensorHeight)
        paddedBitmap.getPixels(intValues, 0, tensorWidth, 0, 0, tensorWidth, tensorHeight)

        if (isNCHW) {
            for (c in 0 until 3) {
                for (pixel in intValues) {
                    val value = when (c) {
                        0 -> Color.red(pixel)
                        1 -> Color.green(pixel)
                        2 -> Color.blue(pixel)
                        else -> 0
                    }
                    inputBuffer.putFloat(value / 255.0f)
                }
            }
        } else {
            for (pixel in intValues) {
                inputBuffer.putFloat(Color.red(pixel) / 255.0f)
                inputBuffer.putFloat(Color.green(pixel) / 255.0f)
                inputBuffer.putFloat(Color.blue(pixel) / 255.0f)
            }
        }
        return inputBuffer
    }

    private fun extractBoxes(array: FloatArray, frameWidth: Int, frameHeight: Int): List<Output0>? {
        val rawBoxes = mutableListOf<Output0>()
        val scale = min(tensorWidth.toFloat() / frameWidth, tensorHeight.toFloat() / frameHeight)
        val padW = (tensorWidth - (frameWidth * scale)) / 2f
        val padH = (tensorHeight - (frameHeight * scale)) / 2f

        // 1. UMBRAL REDUCIDO A 15% PARA PERMITIR MODELOS DEBILMENTE ENTRENADOS
        val threshold = 0.15f

        // Variables para telemetría
        var maxGlobalConf = -1f
        var bestRawCx = 0f
        var bestRawCy = 0f

        if (!isEnd2End) {
            for (i in 0 until numBoxes) {
                val conf = if (isTransposed) array[4 * numBoxes + i] else array[i * numChannels + 4]

                // Rastrear la máxima confianza global de la imagen
                if (conf > maxGlobalConf) {
                    maxGlobalConf = conf
                    bestRawCx = if (isTransposed) array[0 * numBoxes + i] else array[i * numChannels + 0]
                    bestRawCy = if (isTransposed) array[1 * numBoxes + i] else array[i * numChannels + 1]
                }

                if (conf > threshold) {
                    val cxRaw = if (isTransposed) array[0 * numBoxes + i] else array[i * numChannels + 0]
                    val cyRaw = if (isTransposed) array[1 * numBoxes + i] else array[i * numChannels + 1]
                    val wRaw = if (isTransposed) array[2 * numBoxes + i] else array[i * numChannels + 2]
                    val hRaw = if (isTransposed) array[3 * numBoxes + i] else array[i * numChannels + 3]

                    // 2. AUTO-RESCALADO: Si las coordenadas son menores a 2.0, el modelo está exportando en modo Normalizado (0-1).
                    val cx = if (cxRaw <= 2f && wRaw <= 2f) cxRaw * tensorWidth else cxRaw
                    val cy = if (cyRaw <= 2f && hRaw <= 2f) cyRaw * tensorHeight else cyRaw
                    val w = if (wRaw <= 2f && hRaw <= 2f) wRaw * tensorWidth else wRaw
                    val h = if (hRaw <= 2f && hRaw <= 2f) hRaw * tensorHeight else hRaw

                    val trueCx = (cx - padW) / scale
                    val trueCy = (cy - padH) / scale
                    val trueW = w / scale
                    val trueH = h / scale

                    val normX1 = ((trueCx - trueW / 2f) / frameWidth).coerceIn(0f, 1f)
                    val normY1 = ((trueCy - trueH / 2f) / frameHeight).coerceIn(0f, 1f)
                    val normX2 = ((trueCx + trueW / 2f) / frameWidth).coerceIn(0f, 1f)
                    val normY2 = ((trueCy + trueH / 2f) / frameHeight).coerceIn(0f, 1f)

                    val normW = normX2 - normX1
                    val normH = normY2 - normY1

                    if (normW > 0 && normH > 0) {
                        rawBoxes.add(Output0(
                            x1 = normX1, y1 = normY1, x2 = normX2, y2 = normY2,
                            cx = normX1 + normW / 2f, cy = normY1 + normH / 2f,
                            w = normW, h = normH, cnf = conf, cls = 0,
                            clsName = labels[0], maskWeight = emptyList()
                        ))
                    }
                }
            }
        }

        // 3. IMPRESIÓN DE TELEMETRÍA CADA 30 FOTOGRAMAS
        if (logCounter++ % 30 == 0) {
            Log.d("UchuvaVision", "--- MEJOR CAJA EN PANTALLA ---")
            Log.d("UchuvaVision", "Confianza IA: ${maxGlobalConf * 100}%")
            Log.d("UchuvaVision", "Coordenadas Raw (CentroX, CentroY): ($bestRawCx, $bestRawCy)")
        }

        if (rawBoxes.isEmpty()) return null
        return applyNMS(rawBoxes)
    }

    private fun applyNMS(boxes: List<Output0>, iouThresh: Float = 0.45f): List<Output0> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selected = mutableListOf<Output0>()

        while (sortedBoxes.isNotEmpty()) {
            val best = sortedBoxes.removeAt(0)
            selected.add(best)
            sortedBoxes.removeAll { iou(best, it) > iouThresh }
        }
        return selected
    }

    private fun iou(a: Output0, b: Output0): Float {
        val xMin = max(a.x1, b.x1)
        val yMin = max(a.y1, b.y1)
        val xMax = min(a.x2, b.x2)
        val yMax = min(a.y2, b.y2)
        val interArea = max(0f, xMax - xMin) * max(0f, yMax - yMin)
        val aArea = (a.x2 - a.x1) * (a.y2 - a.y1)
        val bArea = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = aArea + bArea - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    fun close() { interpreter.close() }
}