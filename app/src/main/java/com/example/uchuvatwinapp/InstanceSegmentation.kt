package com.example.uchuvatwinapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import com.example.uchuvatwinapp.ImageUtils.clone
import com.example.uchuvatwinapp.ImageUtils.scaleMask
import com.example.uchuvatwinapp.MetaData.extractNamesFromMetadata
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InstanceSegmentation(
    context: Context,
    modelPath: String,
    private val instanceSegmentationListener: InstanceSegmentationListener,
    private val message: (String) -> Unit
) {
    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0
    private var xPoints = 0
    private var yPoints = 0
    private var masksNum = 0

    private var isYolo26Output = false
    private var isNCHW = false
    private var detectionLogCounter = 0

    init {
        val options = Interpreter.Options()
        options.setNumThreads(4)

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        labels.addAll(extractNamesFromMetadata(model))
        if (labels.isEmpty()) {
            message("Model does not contain labels, using fallback class names")
            labels.addAll(MetaData.TEMP_CLASSES)
        }

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape0 = interpreter.getOutputTensor(0)?.shape()
        val outputShape1 = interpreter.getOutputTensor(1)?.shape()

        if (inputShape != null) {
            if (inputShape[1] == 3) {
                isNCHW = true
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            } else {
                isNCHW = false
                tensorWidth = inputShape[1]
                tensorHeight = inputShape[2]
            }
        }

        if (outputShape0 != null) {
            numChannel = outputShape0[1]
            numElements = outputShape0[2]
        }

        if (outputShape1 != null) {
            if (outputShape1[1] == 32) {
                masksNum = outputShape1[1]
                xPoints = outputShape1[2]
                yPoints = outputShape1[3]
            } else {
                xPoints = outputShape1[1]
                yPoints = outputShape1[2]
                masksNum = outputShape1[3]
            }
        }

        if (outputShape0 != null) {
            isYolo26Output = outputShape0.size == 3 &&
                    masksNum > 0 &&
                    outputShape0[2] == YOLO26_DETECTION_FIELDS + masksNum
        }
    }

    fun close() {
        interpreter.close()
    }

    fun invoke(frame: Bitmap) {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
            instanceSegmentationListener.onError("Interpreter not initialized properly")
            return
        }

        var preProcessTime = SystemClock.uptimeMillis()

        val imageBuffer = preProcess(frame)

        val coordinatesBuffer = TensorBuffer.createFixedSize(
            intArrayOf(1, numChannel, numElements),
            OUTPUT_IMAGE_TYPE
        )

        val outputBuffer = mapOf<Int, Any>(
            0 to coordinatesBuffer.buffer.rewind()
        )

        preProcessTime = SystemClock.uptimeMillis() - preProcessTime

        var interfaceTime = SystemClock.uptimeMillis()

        interpreter.runForMultipleInputsOutputs(arrayOf(imageBuffer), outputBuffer)

        interfaceTime = SystemClock.uptimeMillis() - interfaceTime

        var postProcessTime = SystemClock.uptimeMillis()

        val bestBoxes = bestBoxYolo26(coordinatesBuffer.floatArray, frame.width, frame.height) ?: run {
            instanceSegmentationListener.onEmpty()
            return
        }

        val segmentationResults = bestBoxes.map {
            SegmentationResult(box = it, mask = emptyArray())
        }

        postProcessTime = SystemClock.uptimeMillis() - postProcessTime

        // Entregamos las dimensiones exactas del frame para escalar perfectamente
        instanceSegmentationListener.onDetect(
            preProcessTime = preProcessTime,
            interfaceTime = interfaceTime,
            postProcessTime = postProcessTime,
            results = segmentationResults,
            frameWidth = frame.width,
            frameHeight = frame.height
        )
    }

    private fun preProcess(frame: Bitmap): ByteBuffer {
        // Letterboxing: Redimensionar manteniendo proporción exacta
        val scale = Math.min(tensorWidth.toFloat() / frame.width, tensorHeight.toFloat() / frame.height)
        val nw = (frame.width * scale).toInt()
        val nh = (frame.height * scale).toInt()

        val resizedBitmap = Bitmap.createScaledBitmap(frame, nw, nh, true)

        // Crear el fondo cuadrado relleno de gris (Color de padding estándar en Ultralytics)
        val paddedBitmap = Bitmap.createBitmap(tensorWidth, tensorHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))

        // Pegar la imagen reescalada en el centro
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
                for (i in intValues.indices) {
                    val pixel = intValues[i]
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
            for (i in intValues.indices) {
                val pixel = intValues[i]
                inputBuffer.putFloat(Color.red(pixel) / 255.0f)
                inputBuffer.putFloat(Color.green(pixel) / 255.0f)
                inputBuffer.putFloat(Color.blue(pixel) / 255.0f)
            }
        }

        return inputBuffer
    }

    private fun bestBoxYolo26(array: FloatArray, frameWidth: Int, frameHeight: Int): List<Output0>? {
        val output0List = mutableListOf<Output0>()
        var maxRealConfidence = 0f

        // Recalculamos los márgenes de Letterboxing para revertir el desplazamiento
        val scale = Math.min(tensorWidth.toFloat() / frameWidth, tensorHeight.toFloat() / frameHeight)
        val padW = (tensorWidth - (frameWidth * scale)) / 2f
        val padH = (tensorHeight - (frameHeight * scale)) / 2f

        for (detection in 0 until numChannel) {
            val rowOffset = detection * numElements

            val confidence = array[rowOffset + 4]
            if (confidence > maxRealConfidence) maxRealConfidence = confidence
            if (confidence <= CONFIDENCE_THRESHOLD) continue

            val cls = array[rowOffset + 5].toInt()

            val rawX1 = array[rowOffset]
            val rawY1 = array[rowOffset + 1]
            val rawX2 = array[rowOffset + 2]
            val rawY2 = array[rowOffset + 3]

            // Restar el padding y dividir por la escala para retornar a las coordenadas nativas
            val trueX1 = (rawX1 - padW) / scale
            val trueY1 = (rawY1 - padH) / scale
            val trueX2 = (rawX2 - padW) / scale
            val trueY2 = (rawY2 - padH) / scale

            // Normalizar de 0.0 a 1.0 según la resolución original de la cámara
            val normX1 = (trueX1 / frameWidth).coerceIn(0f, 1f)
            val normY1 = (trueY1 / frameHeight).coerceIn(0f, 1f)
            val normX2 = (trueX2 / frameWidth).coerceIn(0f, 1f)
            val normY2 = (trueY2 / frameHeight).coerceIn(0f, 1f)

            val w = normX2 - normX1
            val h = normY2 - normY1
            if (w <= 0F || h <= 0F) continue

            output0List.add(
                Output0(
                    x1 = normX1, y1 = normY1, x2 = normX2, y2 = normY2,
                    cx = normX1 + w / 2F, cy = normY1 + h / 2F, w = w, h = h,
                    cnf = confidence, cls = cls, clsName = labelFor(cls),
                    maskWeight = emptyList()
                )
            )
        }

        if (detectionLogCounter++ % 15 == 0) {
            android.util.Log.d("YOLO_LIVE", "Confianza real máxima: ${String.format("%.3f", maxRealConfidence)}")
        }

        if (output0List.isEmpty()) return null
        return output0List.sortedByDescending { it.cnf }.toMutableList()
    }

    private fun labelFor(index: Int): String {
        return labels.getOrElse(index) { "class${index + 1}" }
    }

    interface InstanceSegmentationListener {
        fun onError(error: String)
        fun onEmpty()
        fun onDetect(
            interfaceTime: Long,
            results: List<SegmentationResult>,
            preProcessTime: Long,
            postProcessTime: Long,
            frameWidth: Int,
            frameHeight: Int
        )
    }

    companion object {
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.70F
        private const val YOLO26_DETECTION_FIELDS = 6
    }
}