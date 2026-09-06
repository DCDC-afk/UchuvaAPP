package com.example.uchuvatwinapp

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.VideoWriter
import org.opencv.videoio.Videoio
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

data class RegistroUchuva(
    val id_tracking: Int,
    var id_orden_ordenado_y: Int = 0,
    val estado: String,
    val confianza_clasificacion: Double,
    val coordenada_x_px: Int,
    val coordenada_x_m: Double,
    val coordenada_y_px: Int,
    val coordenada_y_m: Double,
    val frame_cruce: Int
)

object GestorProcesamiento {

    const val PIXELES_POR_METRO = 450.0
    private const val MODELO_NOMBRE = "model_uchuvas.tflite"
    const val MODELO_ANCHO = 640
    const val MODELO_ALTO = 640

    private val NOMBRES_CLASES = arrayOf("Maduro", "Inmaduro")

    var isProcesando = false
    var progresoActual = 0
    var totalActual = 0
    var videoActualNombre = ""

    var onProgresoUI: ((Int, Int, String) -> Unit)? = null
    var onFinalizadoUI: (() -> Unit)? = null

    fun convertirPixelesAMetros(pixeles: Int): Double {
        return Math.round((pixeles / PIXELES_POR_METRO) * 100.0) / 100.0
    }

    // --- ALGORITMO NMS PARA ELIMINAR CAJAS DUPLICADAS SOBRE LA MISMA UCHUVA ---
    private fun calcularIoUNMS(caja1: FloatArray, caja2: FloatArray): Float {
        val xMinInter = max(caja1[0], caja2[0])
        val yMinInter = max(caja1[1], caja2[1])
        val xMaxInter = min(caja1[2], caja2[2])
        val yMaxInter = min(caja1[3], caja2[3])

        val anchoInter = max(0f, xMaxInter - xMinInter)
        val altoInter = max(0f, yMaxInter - yMinInter)
        val areaInter = anchoInter * altoInter

        val areaCaja1 = max(0f, caja1[2] - caja1[0]) * max(0f, caja1[3] - caja1[1])
        val areaCaja2 = max(0f, caja2[2] - caja2[0]) * max(0f, caja2[3] - caja2[1])

        val areaUnion = areaCaja1 + areaCaja2 - areaInter
        return if (areaUnion > 0f) areaInter / areaUnion else 0f
    }

    private fun aplicarNMS(detecciones: List<STrack>, iouThresh: Float = 0.45f): List<STrack> {
        val ordenadas = detecciones.sortedByDescending { it.confianza }.toMutableList()
        val result = mutableListOf<STrack>()

        while (ordenadas.isNotEmpty()) {
            val mejor = ordenadas.removeAt(0)
            result.add(mejor)
            // Elimina cualquier caja que se superponga más del 45% con la "mejor" caja
            ordenadas.removeAll { calcularIoUNMS(mejor.caja, it.caja) > iouThresh }
        }
        return result
    }

    fun procesarLoteCompleto(context: Context, assetManager: AssetManager, carpetaLote: File) {
        if (isProcesando) return

        val appContext = context.applicationContext
        isProcesando = true
        progresoActual = 0
        totalActual = 0
        videoActualNombre = ""

        Thread {
            val tfliteInterpreter = cargarModeloTFLite(assetManager)
            if (tfliteInterpreter == null) {
                finalizarProceso()
                return@Thread
            }

            try {
                val videos = carpetaLote.listFiles { file -> file.extension.lowercase() == "mp4" } ?: emptyArray()
                if (videos.isEmpty()) {
                    finalizarProceso()
                    return@Thread
                }

                totalActual = videos.size
                val directorioBase = appContext.getExternalFilesDir(null)
                val carpetaLoteProcesado = File(directorioBase, "UchuvaTwin_Resultados/${carpetaLote.name}_procesado")
                val carpetaVideosSalida = File(carpetaLoteProcesado, "Videos")
                val carpetaJsonSalida = File(carpetaLoteProcesado, "Reportes_JSON")

                carpetaVideosSalida.mkdirs()
                carpetaJsonSalida.mkdirs()

                val metaOrigen = File(carpetaLote, "metadata.txt")
                if (metaOrigen.exists()) {
                    metaOrigen.copyTo(File(carpetaLoteProcesado, "metadata.txt"), overwrite = true)
                }

                videos.forEachIndexed { index, video ->
                    progresoActual = index + 1
                    videoActualNombre = video.name
                    onProgresoUI?.invoke(progresoActual, totalActual, videoActualNombre)

                    procesarVideoIndividual(video, carpetaVideosSalida, carpetaJsonSalida, tfliteInterpreter)
                }

                val archivoMarker = File(carpetaLoteProcesado, ".completado")
                if (!archivoMarker.exists()) {
                    archivoMarker.createNewFile()
                }

            } catch (e: Exception) {
                Log.e("UchuvaVision", "Excepción en el procesamiento: ${e.message}")
            } finally {
                tfliteInterpreter.close()
                finalizarProceso()
            }
        }.start()
    }

    private fun finalizarProceso() {
        isProcesando = false
        onFinalizadoUI?.invoke()
    }

    private fun cargarModeloTFLite(assetManager: AssetManager): Interpreter? {
        return try {
            val fd = assetManager.openFd(MODELO_NOMBRE)
            val buffer = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            Interpreter(buffer)
        } catch (e: Exception) {
            Log.e("UchuvaVision", "ERROR MODELO: ${e.message}")
            null
        }
    }

    private fun arrancarMotorVision() = OpenCVLoader.initDebug()

    private fun procesarVideoIndividual(
        archivoVideo: File,
        carpetaVideosSalida: File,
        carpetaJsonSalida: File,
        tfliteInterpreter: Interpreter
    ) {
        if (!arrancarMotorVision()) return

        val cap = VideoCapture(archivoVideo.absolutePath)
        if (!cap.isOpened) return

        val totalFrames = cap.get(Videoio.CAP_PROP_FRAME_COUNT).toInt()
        val anchoVideo = cap.get(Videoio.CAP_PROP_FRAME_WIDTH).toInt()
        val altoVideo = cap.get(Videoio.CAP_PROP_FRAME_HEIGHT).toInt()
        val fps = cap.get(Videoio.CAP_PROP_FPS)
        val lineaX = anchoVideo / 2

        val archivoSalida = File(carpetaVideosSalida, "debug_${archivoVideo.nameWithoutExtension}.avi")
        val writer = VideoWriter(archivoSalida.absolutePath, VideoWriter.fourcc('M', 'J', 'P', 'G'), fps, Size(anchoVideo.toDouble(), altoVideo.toDouble()))

        val frameMatriz = Mat()
        var contadorFrames = 0

        val tracker = ByteTracker(trackHighThresh = 0.5f, trackLowThresh = 0.1f, trackBuffer = 15)

        val objetosContados = mutableSetOf<Int>()
        val listaConteoRegistros = mutableListOf<RegistroUchuva>()
        val esNCHW = tfliteInterpreter.getInputTensor(0)?.shape()?.get(1) == 3

        while (cap.read(frameMatriz)) {
            contadorFrames++
            if (contadorFrames % 10 == 0) Log.d("UchuvaVision", "Procesando Frame $contadorFrames de $totalFrames...")

            val resizedMat = Mat()
            Imgproc.resize(frameMatriz, resizedMat, Size(MODELO_ANCHO.toDouble(), MODELO_ALTO.toDouble()))
            val rgbMat = Mat()
            Imgproc.cvtColor(resizedMat, rgbMat, Imgproc.COLOR_BGR2RGB)

            val inputBuffer = ByteBuffer.allocateDirect(1 * MODELO_ANCHO * MODELO_ALTO * 3 * 4).apply { order(ByteOrder.nativeOrder()) }
            val byteArray = ByteArray(MODELO_ANCHO * MODELO_ALTO * 3).also { rgbMat.get(0, 0, it) }

            if (esNCHW) {
                for (c in 0 until 3) for (p in 0 until MODELO_ANCHO * MODELO_ALTO) {
                    inputBuffer.putFloat((byteArray[p * 3 + c].toInt() and 0xFF) / 255.0f)
                }
            } else {
                for (i in byteArray.indices) inputBuffer.putFloat((byteArray[i].toInt() and 0xFF) / 255.0f)
            }

            val outputArray = Array(1) { Array(300) { FloatArray(38) } }
            tfliteInterpreter.run(inputBuffer, outputArray)
            val deteccionesFrame = outputArray[0]

            Imgproc.line(frameMatriz, Point(lineaX.toDouble(), 0.0), Point(lineaX.toDouble(), altoVideo.toDouble()), Scalar(0.0, 255.0, 255.0), 5)

            // 1. RECOPILAR DETECCIONES CRUDAS
            val deteccionesYolo = mutableListOf<STrack>()
            for (i in 0 until 300) {
                val confidence = deteccionesFrame[i][4]
                if (confidence >= 0.1f) {
                    val x1 = deteccionesFrame[i][0]
                    val y1 = deteccionesFrame[i][1]
                    val x2 = deteccionesFrame[i][2]
                    val y2 = deteccionesFrame[i][3]
                    val classIdFloat = deteccionesFrame[i][5]

                    val xMin = ((x1 / MODELO_ANCHO.toFloat()) * anchoVideo).toFloat()
                    val yMin = ((y1 / MODELO_ALTO.toFloat()) * altoVideo).toFloat()
                    val xMax = ((x2 / MODELO_ANCHO.toFloat()) * anchoVideo).toFloat()
                    val yMax = ((y2 / MODELO_ALTO.toFloat()) * altoVideo).toFloat()

                    val claseIdx = classIdFloat.toInt()
                    val nombreClase = if (claseIdx in NOMBRES_CLASES.indices) NOMBRES_CLASES[claseIdx] else "Desconocido"

                    deteccionesYolo.add(STrack(floatArrayOf(xMin, yMin, xMax, yMax), confidence, nombreClase))
                }
            }

            // 2. APLICAR NMS
            val deteccionesLimpias = aplicarNMS(deteccionesYolo)

            // 3. ACTUALIZAR EL TRACKER SOLO CON LAS CAJAS LIMPIAS
            val tracksVisibles = tracker.update(deteccionesLimpias)

            // 4. DIBUJAR EN VIDEO SOLO LOS TRACKS VISIBLES
            for (track in tracksVisibles) {
                val colorCaja = if (track.clase == "Maduro") Scalar(0.0, 165.0, 255.0) else Scalar(0.0, 255.0, 0.0)

                Imgproc.rectangle(frameMatriz, Point(track.caja[0].toDouble(), track.caja[1].toDouble()), Point(track.caja[2].toDouble(), track.caja[3].toDouble()), colorCaja, 4)
                val texto = "id:${track.id} ${track.clase} ${String.format("%.2f", track.confianza)}"

                // AQUÍ ESTABA EL ERROR: Agregado el parámetro 'texto'
                Imgproc.putText(frameMatriz, texto, Point(track.caja[0].toDouble(), track.caja[1].toDouble() - 10.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, colorCaja, 2)
                Imgproc.circle(frameMatriz, Point(track.centroX.toDouble(), track.centroY.toDouble()), 8, Scalar(0.0, 0.0, 255.0), -1)
            }

            // 5. VERIFICAR CRUCES DE LÍNEA
            for (track in tracker.tracksActivos) {
                if (track.estado != TrackState.Tracked) continue

                if (!objetosContados.contains(track.id) && track.historialX.size >= 2) {
                    val xAnterior = track.historialX[track.historialX.size - 2]
                    val xActual = track.historialX.last()

                    if ((xAnterior > lineaX && xActual <= lineaX) || (xAnterior < lineaX && xActual >= lineaX)) {
                        objetosContados.add(track.id)

                        val claseFinal = track.historialClases.groupBy { it }.maxByOrNull { it.value.size }?.key ?: track.clase
                        val confMedia = track.confianzas.average()

                        listaConteoRegistros.add(RegistroUchuva(
                            id_tracking = track.id,
                            estado = claseFinal,
                            confianza_clasificacion = Math.round(confMedia * 100.0) / 100.0,
                            coordenada_x_px = xActual.toInt(),
                            coordenada_x_m = convertirPixelesAMetros(xActual.toInt()),
                            coordenada_y_px = track.historialY.last().toInt(),
                            coordenada_y_m = convertirPixelesAMetros(track.historialY.last().toInt()),
                            frame_cruce = contadorFrames
                        ))
                    }
                }
            }

            val frameCorregido = Mat()
            Imgproc.cvtColor(frameMatriz, frameCorregido, Imgproc.COLOR_BGR2RGB)
            writer.write(frameCorregido)
            frameCorregido.release()

            resizedMat.release()
            rgbMat.release()
        }

        cap.release()
        writer.release()
        frameMatriz.release()

        // 6. GUARDAR JSON ORDENADO POR EJE Y
        val jsonArray = JSONArray()
        listaConteoRegistros.sortedBy { it.coordenada_y_px }.forEachIndexed { indice, registro ->
            registro.id_orden_ordenado_y = indice + 1
            val obj = JSONObject()
            obj.put("id_tracking", registro.id_tracking)
            obj.put("id_orden_ordenado_y", registro.id_orden_ordenado_y)
            obj.put("estado", registro.estado)
            obj.put("confianza_clasificacion", registro.confianza_clasificacion)
            obj.put("coordenada_x_px", registro.coordenada_x_px)
            obj.put("coordenada_x_m", registro.coordenada_x_m)
            obj.put("coordenada_y_px", registro.coordenada_y_px)
            obj.put("coordenada_y_m", registro.coordenada_y_m)
            obj.put("frame_cruce", registro.frame_cruce)
            jsonArray.put(obj)
        }

        val rutaJson = File(carpetaJsonSalida, "reporte_conteo_${archivoVideo.nameWithoutExtension}.json")
        FileWriter(rutaJson).use { it.write(jsonArray.toString(4)) }
    }
}