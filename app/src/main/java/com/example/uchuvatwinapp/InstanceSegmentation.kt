package com.example.uchuvatwinapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.example.uchuvatwinapp.MetaData.extractNamesFromMetadata
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer

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

    private var isNCHW = false

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

        // Ignoramos el tensor de salida 1 (las máscaras) para optimizar memoria
        val outputBuffer = mapOf<Int, Any>(
            0 to coordinatesBuffer.buffer.rewind()
        )

        preProcessTime = SystemClock.uptimeMillis() - preProcessTime

        var interfaceTime = SystemClock.uptimeMillis()

        interpreter.runForMultipleInputsOutputs(arrayOf(imageBuffer), outputBuffer)

        interfaceTime = SystemClock.uptimeMillis() - interfaceTime

        var postProcessTime = SystemClock.uptimeMillis()

        val bestBoxes = bestBoxYolo26(coordinatesBuffer.floatArray) ?: run {
            instanceSegmentationListener.onEmpty()
            return
        }

        // Ya no calculamos la máscara pesada
        val segmentationResults = bestBoxes.map {
            SegmentationResult(
                box = it,
                mask = emptyArray() // Array vacío porque ya no pintaremos la segmentación
            )
        }

        postProcessTime = SystemClock.uptimeMillis() - postProcessTime

        instanceSegmentationListener.onDetect(
            preProcessTime = preProcessTime,
            interfaceTime = interfaceTime,
            postProcessTime = postProcessTime,
            results = segmentationResults
        )
    }

    private fun preProcess(frame: Bitmap): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

        val inputBuffer = ByteBuffer.allocateDirect(1 * tensorWidth * tensorHeight * 3 * 4).apply {
            order(java.nio.ByteOrder.nativeOrder())
        }

        val intValues = IntArray(tensorWidth * tensorHeight)
        resizedBitmap.getPixels(intValues, 0, tensorWidth, 0, 0, tensorWidth, tensorHeight)

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

    private fun bestBoxYolo26(array: FloatArray): List<Output0>? {
        val output0List = mutableListOf<Output0>()

        for (detection in 0 until numChannel) {
            val rowOffset = detection * numElements

            val confidence = array[rowOffset + 4]
            if (confidence <= CONFIDENCE_THRESHOLD) continue

            val cls = array[rowOffset + 5].toInt()

            val x1 = (array[rowOffset] / tensorWidth.toFloat()).coerceIn(0F, 1F)
            val y1 = (array[rowOffset + 1] / tensorHeight.toFloat()).coerceIn(0F, 1F)
            val x2 = (array[rowOffset + 2] / tensorWidth.toFloat()).coerceIn(0F, 1F)
            val y2 = (array[rowOffset + 3] / tensorHeight.toFloat()).coerceIn(0F, 1F)

            val w = x2 - x1
            val h = y2 - y1
            if (w <= 0F || h <= 0F) continue

            output0List.add(
                Output0(
                    x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                    cx = x1 + w / 2F, cy = y1 + h / 2F, w = w, h = h,
                    cnf = confidence, cls = cls, clsName = labelFor(cls),
                    maskWeight = emptyList() // Sin pesos de máscara
                )
            )
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
            postProcessTime: Long
        )
    }

    companion object {
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.50F
    }
}