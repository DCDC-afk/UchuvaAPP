package com.example.uchuvatwinapp

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class DronActivity : ComponentActivity(), InstanceSegmentation.InstanceSegmentationListener {

    private lateinit var cardControl: MaterialCardView
    private lateinit var cardDescarga: MaterialCardView
    private lateinit var cardProcesar: MaterialCardView

    private lateinit var panelControl: LinearLayout
    private lateinit var panelDescarga: LinearLayout
    private lateinit var panelProcesamiento: LinearLayout

    // --- Elementos de Cámara y Enlace ---
    private lateinit var previewViewDron: PreviewView
    private lateinit var ivOverlayDron: ImageView
    private lateinit var tvPlaceholderDron: TextView
    private lateinit var btnVerDronRTMP: MaterialButton
    private lateinit var btnCamaraTablet: MaterialButton
    private var isCameraActive = false
    private var cameraProvider: ProcessCameraProvider? = null

    // --- Pipeline de Inferencia YOLOv26 ---
    private var instanceSegmentation: InstanceSegmentation? = null
    private lateinit var drawImages: DrawImages
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private lateinit var etIpDron: EditText
    private lateinit var btnSincronizarLotes: MaterialButton
    private lateinit var pbSincronizacion: ProgressBar
    private lateinit var btnVolverLotes: MaterialButton
    private lateinit var rvGestorArchivos: RecyclerView

    private lateinit var tvEstadoConexion: TextView
    private lateinit var tvBateria: TextView
    private lateinit var tvAlmacenamiento: TextView
    private lateinit var tvVideosCola: TextView
    private lateinit var ivUchuvaEstado: ImageView
    private lateinit var ivBateriaHoja: ImageView
    private lateinit var ivSdUchuva: ImageView

    private lateinit var rvLotesProcesables: RecyclerView
    private lateinit var btnIniciarProcesamiento: MaterialButton
    private lateinit var panelProgresoLote: LinearLayout
    private lateinit var tvProgresoLote: TextView
    private lateinit var pbProcesamientoLote: ProgressBar
    private lateinit var adaptadorLotesProcesables: AdapterLotesProcesables
    private var loteSeleccionadoParaProcesar: LoteProcesable? = null

    private lateinit var prefs: SharedPreferences
    private var mostrandoLotes = true
    private var estaSincronizando = false

    private val handlerTelemetria = Handler(Looper.getMainLooper())
    private val intervaloTelemetria = 5000L
    private val runnableTelemetria = object : Runnable {
        override fun run() {
            if (!estaSincronizando) {
                actualizarBarraTelemetria()
            }
            handlerTelemetria.postDelayed(this, intervaloTelemetria)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            toggleCamaraTablet()
        } else {
            Toast.makeText(this, "Permiso de cámara requerido", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.enterTransition = com.google.android.material.transition.platform.MaterialSharedAxis(
            com.google.android.material.transition.platform.MaterialSharedAxis.Z, true
        ).apply { duration = 350L }

        window.returnTransition = com.google.android.material.transition.platform.MaterialSharedAxis(
            com.google.android.material.transition.platform.MaterialSharedAxis.Z, false
        ).apply { duration = 350L }

        setContentView(R.layout.activity_dron)

        prefs = getSharedPreferences("UchuvaTwinPrefs", Context.MODE_PRIVATE)

        cardControl = findViewById(R.id.cardBotonControl)
        cardDescarga = findViewById(R.id.cardBotonDescarga)
        cardProcesar = findViewById(R.id.cardBotonProcesar)

        panelControl = findViewById(R.id.panelControl)
        panelDescarga = findViewById(R.id.panelDescarga)
        panelProcesamiento = findViewById(R.id.panelProcesamiento)

        previewViewDron = findViewById(R.id.previewViewDron)
        ivOverlayDron = findViewById(R.id.ivOverlayDron)
        tvPlaceholderDron = findViewById(R.id.tvPlaceholderDron)
        btnVerDronRTMP = findViewById(R.id.btnVerDronRTMP)
        btnCamaraTablet = findViewById(R.id.btnCamaraTablet)

        // Inicializar motor de dibujo
        drawImages = DrawImages(applicationContext)

        // Inicializar intérprete TFLite del modelo
        try {
            instanceSegmentation = InstanceSegmentation(
                context = applicationContext,
                modelPath = "model_uchuvas.tflite", // Ajusta al nombre exacto de tu archivo en assets
                instanceSegmentationListener = this,
                message = { msg ->
                    runOnUiThread { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Aviso: Modelo no cargado: ${e.message}", Toast.LENGTH_LONG).show()
        }

        etIpDron = findViewById(R.id.etIpDron)
        btnSincronizarLotes = findViewById(R.id.btnSincronizarLotes)
        pbSincronizacion = findViewById(R.id.pbSincronizacion)
        btnVolverLotes = findViewById(R.id.btnVolverLotes)
        rvGestorArchivos = findViewById(R.id.rvGestorArchivos)

        tvEstadoConexion = findViewById(R.id.tvEstadoConexion)
        tvBateria = findViewById(R.id.tvBateria)
        tvAlmacenamiento = findViewById(R.id.tvAlmacenamiento)
        tvVideosCola = findViewById(R.id.tvVideosCola)
        ivUchuvaEstado = findViewById(R.id.ivUchuvaEstado)
        ivBateriaHoja = findViewById(R.id.ivBateriaHoja)
        ivSdUchuva = findViewById(R.id.ivSdUchuva)

        rvLotesProcesables = findViewById(R.id.rvLotesProcesables)
        btnIniciarProcesamiento = findViewById(R.id.btnIniciarProcesamiento)
        panelProgresoLote = findViewById(R.id.panelProgresoLote)
        tvProgresoLote = findViewById(R.id.tvProgresoLote)
        pbProcesamientoLote = findViewById(R.id.pbProcesamientoLote)

        rvGestorArchivos.layoutManager = LinearLayoutManager(this)
        rvLotesProcesables.layoutManager = LinearLayoutManager(this)

        val ipGuardada = prefs.getString("IP_DRON", "192.168.1.10")
        etIpDron.setText(ipGuardada)

        adaptadorLotesProcesables = AdapterLotesProcesables(emptyList()) { lote ->
            loteSeleccionadoParaProcesar = lote
            btnIniciarProcesamiento.isEnabled = true

            if (lote.estaProcesado) {
                btnIniciarProcesamiento.text = "RE-PROCESAR (SOBRESCRIBIR)"
                btnIniciarProcesamiento.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                btnIniciarProcesamiento.strokeColor = ColorStateList.valueOf(Color.parseColor("#FFF59D"))
                btnIniciarProcesamiento.strokeWidth = 4
                btnIniciarProcesamiento.setTextColor(Color.parseColor("#FFF59D"))
            } else {
                btnIniciarProcesamiento.text = "PROCESAR LOTE COMPLETO"
                btnIniciarProcesamiento.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D36D42"))
                btnIniciarProcesamiento.strokeWidth = 0
                btnIniciarProcesamiento.setTextColor(Color.BLACK)
            }
        }
        rvLotesProcesables.adapter = adaptadorLotesProcesables

        aplicarAnimacionCapsula(cardControl) { cambiarModo(1) }
        aplicarAnimacionCapsula(cardDescarga) { cambiarModo(2) }
        aplicarAnimacionCapsula(cardProcesar) {
            cambiarModo(3)
            cargarCarpetasParaProcesar()
        }

        btnSincronizarLotes.setOnClickListener { ejecutarSincronizacionHTTP() }
        btnVolverLotes.setOnClickListener { cargarLotesLocalesEnUI() }
        btnIniciarProcesamiento.setOnClickListener { iniciarProcesamientoMasivo() }

        btnCamaraTablet.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                toggleCamaraTablet()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnVerDronRTMP.setOnClickListener {
            Toast.makeText(this, "Conectando al stream RTMP del dron...", Toast.LENGTH_SHORT).show()
        }

        ivUchuvaEstado.setImageResource(R.drawable.ic_uchuva_naranja)

        cargarLotesLocalesEnUI()

        if (GestorProcesamiento.isProcesando) {
            mostrarUIProcesando()
        } else {
            cambiarModo(1)
        }

        handlerTelemetria.post(runnableTelemetria)
    }

    override fun onResume() {
        super.onResume()
        vincularEventosProcesamiento()

        if (GestorProcesamiento.isProcesando) {
            mostrarUIProcesando()
            GestorProcesamiento.onProgresoUI?.invoke(
                GestorProcesamiento.progresoActual,
                GestorProcesamiento.totalActual,
                GestorProcesamiento.videoActualNombre
            )
        }
    }

    override fun onPause() {
        super.onPause()
        GestorProcesamiento.onProgresoUI = null
        GestorProcesamiento.onFinalizadoUI = null
    }

    private fun vincularEventosProcesamiento() {
        GestorProcesamiento.onProgresoUI = { actual, total, nombreVideo ->
            Handler(Looper.getMainLooper()).post {
                if (panelProcesamiento.visibility == View.VISIBLE) {
                    tvProgresoLote.text = "Analizando: $nombreVideo\nVideo $actual de $total"
                    pbProcesamientoLote.max = total
                    pbProcesamientoLote.progress = actual
                }
            }
        }

        GestorProcesamiento.onFinalizadoUI = {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@DronActivity, "Lote Procesado Exitosamente", Toast.LENGTH_LONG).show()
                panelProgresoLote.visibility = View.GONE
                btnIniciarProcesamiento.visibility = View.VISIBLE
                rvLotesProcesables.visibility = View.VISIBLE
                btnIniciarProcesamiento.text = "SELECCIONE UNA CARPETA"
                btnIniciarProcesamiento.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#161B22"))
                btnIniciarProcesamiento.setTextColor(Color.parseColor("#8B949E"))
                btnIniciarProcesamiento.strokeWidth = 0
                btnIniciarProcesamiento.isEnabled = false
                cargarCarpetasParaProcesar()
            }
        }
    }

    private fun mostrarUIProcesando() {
        cambiarModo(3)
        btnIniciarProcesamiento.visibility = View.GONE
        rvLotesProcesables.visibility = View.GONE
        panelProgresoLote.visibility = View.VISIBLE
    }

    private fun aplicarAnimacionCapsula(card: MaterialCardView, onClick: () -> Unit) {
        card.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).withEndAction {
                        if (event.action == android.view.MotionEvent.ACTION_UP) {
                            v.performClick()
                            onClick()
                        }
                    }.start()
                }
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        instanceSegmentation?.close()
        handlerTelemetria.removeCallbacks(runnableTelemetria)
    }

    private fun toggleCamaraTablet() {
        if (isCameraActive) {
            cameraProvider?.unbindAll()

            previewViewDron.visibility = View.INVISIBLE
            ivOverlayDron.visibility = View.INVISIBLE
            ivOverlayDron.setImageDrawable(null)
            tvPlaceholderDron.visibility = View.VISIBLE

            btnCamaraTablet.text = "CÁMARA TABLET"
            btnCamaraTablet.strokeColor = ColorStateList.valueOf(Color.parseColor("#2A2F3A"))
            btnCamaraTablet.setTextColor(Color.WHITE)

            isCameraActive = false
        } else {
            tvPlaceholderDron.visibility = View.GONE
            previewViewDron.setBackgroundColor(Color.BLACK)
            previewViewDron.visibility = View.VISIBLE
            ivOverlayDron.visibility = View.VISIBLE

            iniciarCamaraPreview()

            btnCamaraTablet.text = "PAUSAR CÁMARA"
            btnCamaraTablet.strokeColor = ColorStateList.valueOf(Color.parseColor("#D36D42"))
            btnCamaraTablet.setTextColor(Color.parseColor("#D36D42"))

            isCameraActive = true
        }
    }

    private fun iniciarCamaraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // 1. Caso de uso: Vista Previa
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build().also {
                    it.setSurfaceProvider(previewViewDron.surfaceProvider)
                }

            // 2. Caso de uso: Extracción de Frames para YOLO (Pipeline Metodología Referencia)
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                    it.setAnalyzer(cameraExecutor, FrameAnalyzer())
                }

            previewViewDron.previewStreamState.observe(this) { state ->
                if (state == PreviewView.StreamState.STREAMING) {
                    previewViewDron.setBackgroundColor(Color.TRANSPARENT)
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Toast.makeText(this, "Fallo al vincular cámara: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Analizador de fotogramas de CameraX
// Analizador de fotogramas de CameraX
    inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            // 1. Usar el método seguro nativo de CameraX que maneja el padding de hardware
            val originalBitmap = imageProxy.toBitmap()

            // 2. Rotar la imagen si el sensor de la tablet lo requiere
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }

            val rotatedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height,
                matrix, true
            )

            // 3. Inyectar el frame corregido al modelo TFLite
            instanceSegmentation?.invoke(rotatedBitmap)

            // 4. Liberar la memoria del fotograma
            imageProxy.close()
        }
    }
    override fun onDetect(
        interfaceTime: Long,
        results: List<SegmentationResult>,
        preProcessTime: Long,
        postProcessTime: Long,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val overlayBitmap = drawImages.invoke(results, frameWidth, frameHeight)
        runOnUiThread {
            if (isCameraActive) {
                // Obligamos al ImageView a escalar y recortar la capa de dibujo igual que el PreviewView de CameraX
                ivOverlayDron.scaleType = ImageView.ScaleType.CENTER_CROP
                ivOverlayDron.setImageBitmap(overlayBitmap)
            }
        }
    }

    override fun onEmpty() {
        runOnUiThread {
            ivOverlayDron.setImageDrawable(null)
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            ivOverlayDron.setImageDrawable(null)
        }
    }
    private fun cargarCarpetasParaProcesar() {
        val carpetasOrigen = GestorLotes.listarLotesExistentes(this)
        val directorioResultados = File(getExternalFilesDir(null), "UchuvaTwin_Resultados")
        if (!directorioResultados.exists()) directorioResultados.mkdirs()

        val carpetasList = mutableListOf<LoteProcesable>()

        for (carpeta in carpetasOrigen) {
            val videos = carpeta.listFiles { f -> f.extension.lowercase() in listOf("mp4", "avi") } ?: emptyArray()
            if (videos.isNotEmpty()) {
                val carpetaProcesada = File(directorioResultados, "${carpeta.name}_procesado")
                val estaCompletado = carpetaProcesada.exists() && File(carpetaProcesada, ".completado").exists()

                val archivoMeta = File(carpeta, "metadata.txt")
                val fechaOriginal = if (archivoMeta.exists()) archivoMeta.readText() else "Fecha Desconocida"

                carpetasList.add(
                    LoteProcesable(carpeta.name, carpeta, estaCompletado, videos.size, fechaOriginal)
                )
            }
        }

        val listaOrdenada = carpetasList.sortedWith(compareBy({ it.estaProcesado }, { it.nombreCarpeta }))
        adaptadorLotesProcesables.actualizarLista(listaOrdenada)
    }

    private fun iniciarProcesamientoMasivo() {
        val lote = loteSeleccionadoParaProcesar ?: return

        mostrarUIProcesando()

        GestorProcesamiento.procesarLoteCompleto(
            context = this,
            assetManager = assets,
            carpetaLote = lote.rutaOrigen
        )
    }

    private fun actualizarBarraTelemetria() {
        val ipActual = prefs.getString("IP_DRON", "192.168.1.10") ?: return

        thread {
            val telemetria = ClienteDronHTTP.obtenerTelemetria(ipActual)

            if (telemetria != null) {
                val lotesRemotos = ClienteDronHTTP.obtenerEstructuraLotesRemotos(ipActual)
                var videosPendientes = 0

                for (lote in lotesRemotos) {
                    val carpetaLocal = GestorLotes.crearSubcarpetaLote(this@DronActivity, lote.nombreLote)
                    val archivosLocales = carpetaLocal.listFiles()?.map { it.name } ?: emptyList()

                    for (videoRemoto in lote.videos) {
                        if (!archivosLocales.contains(videoRemoto)) {
                            videosPendientes++
                        }
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    tvEstadoConexion.text = "ONLINE"
                    tvEstadoConexion.setTextColor(Color.parseColor("#A5D6A7"))
                    ivUchuvaEstado.setImageResource(R.drawable.ic_uchuva_verde)

                    val porcentajeBateria = telemetria.bateria.replace("%", "").trim().toIntOrNull() ?: 0
                    val colorBateriaHex = when {
                        porcentajeBateria >= 50 -> "#A5D6A7"
                        porcentajeBateria >= 20 -> "#FFF59D"
                        else -> "#EF9A9A"
                    }

                    tvBateria.text = telemetria.bateria
                    tvBateria.setTextColor(Color.parseColor(colorBateriaHex))
                    ivBateriaHoja.setColorFilter(Color.parseColor(colorBateriaHex))

                    tvAlmacenamiento.text = telemetria.almacenamiento
                    tvAlmacenamiento.setTextColor(Color.parseColor("#FFFFFF"))
                    ivSdUchuva.clearColorFilter()

                    if (videosPendientes > 0) {
                        tvVideosCola.text = "NUEVOS: $videosPendientes"
                        tvVideosCola.setTextColor(Color.parseColor("#D36D42"))
                    } else {
                        tvVideosCola.text = "ACTUALIZADO"
                        tvVideosCola.setTextColor(Color.parseColor("#A5D6A7"))
                    }
                }
            } else {
                Handler(Looper.getMainLooper()).post {
                    tvEstadoConexion.text = "OFFLINE"
                    tvEstadoConexion.setTextColor(Color.parseColor("#E57373"))
                    ivUchuvaEstado.setImageResource(R.drawable.ic_uchuva_naranja)

                    tvBateria.text = "--"
                    tvBateria.setTextColor(Color.parseColor("#8B949E"))
                    ivBateriaHoja.setColorFilter(Color.parseColor("#5D4037"))

                    tvAlmacenamiento.text = "--"
                    tvAlmacenamiento.setTextColor(Color.parseColor("#8B949E"))
                    ivSdUchuva.setColorFilter(Color.parseColor("#5D4037"))
                    tvVideosCola.text = "SIN RED"
                    tvVideosCola.setTextColor(Color.parseColor("#FFB74D"))
                }
            }
        }
    }

    private fun ejecutarSincronizacionHTTP() {
        val ipIngresada = etIpDron.text.toString().trim()
        if (ipIngresada.isEmpty()) return

        prefs.edit().putString("IP_DRON", ipIngresada).apply()

        pbSincronizacion.visibility = View.VISIBLE
        btnSincronizarLotes.isEnabled = false
        estaSincronizando = true
        Toast.makeText(this, "Sincronizando Archivos...", Toast.LENGTH_SHORT).show()

        thread {
            var videosNuevosDescargados = 0
            val estructuraRemota = ClienteDronHTTP.obtenerEstructuraLotesRemotos(ipIngresada)

            if (estructuraRemota.isEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    pbSincronizacion.visibility = View.GONE
                    btnSincronizarLotes.isEnabled = true
                    estaSincronizando = false
                    Toast.makeText(this@DronActivity, "Error de red: ${ClienteDronHTTP.ultimoErrorDiagnostico}", Toast.LENGTH_LONG).show()
                }
                return@thread
            }

            for (loteRemoto in estructuraRemota) {
                val carpetaLocal = GestorLotes.crearSubcarpetaLote(this@DronActivity, loteRemoto.nombreLote)

                val archivoMeta = File(carpetaLocal, "metadata.txt")
                if (!archivoMeta.exists() && loteRemoto.fechaOriginal.isNotEmpty()) {
                    archivoMeta.writeText(loteRemoto.fechaOriginal)
                }

                val archivosLocales = carpetaLocal.listFiles()?.map { it.name } ?: emptyList()

                for (nombreVideo in loteRemoto.videos) {
                    if (!archivosLocales.contains(nombreVideo)) {
                        val archivoDestino = File(carpetaLocal, nombreVideo)
                        val exitoDescarga = ClienteDronHTTP.descargarVideoRemoto(
                            ipIngresada, 8080, loteRemoto.nombreLote, nombreVideo, archivoDestino
                        )
                        if (exitoDescarga) {
                            videosNuevosDescargados++
                        }
                    }
                }
            }

            Handler(Looper.getMainLooper()).post {
                pbSincronizacion.visibility = View.GONE
                btnSincronizarLotes.isEnabled = true
                estaSincronizando = false
                if (videosNuevosDescargados > 0) {
                    Toast.makeText(this@DronActivity, "¡Éxito! $videosNuevosDescargados videos descargados.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@DronActivity, "El dispositivo ya está actualizado.", Toast.LENGTH_SHORT).show()
                }
                cargarLotesLocalesEnUI()
                actualizarBarraTelemetria()
            }
        }
    }

    private fun cargarLotesLocalesEnUI() {
        mostrandoLotes = true
        btnVolverLotes.visibility = View.GONE

        val carpetas = GestorLotes.listarLotesExistentes(this)
        val listaItems = mutableListOf<LoteItem>()

        for (carpeta in carpetas) {
            val archivos = carpeta.listFiles { file -> file.extension.lowercase() in listOf("mp4", "avi") } ?: emptyArray()
            val totalMB = archivos.sumOf { it.length() } / (1024 * 1024)

            val archivoMeta = File(carpeta, "metadata.txt")
            val fechaOriginal = if (archivoMeta.exists()) archivoMeta.readText() else "Fecha Desconocida"

            listaItems.add(LoteItem(carpeta.name, archivos.size, totalMB, carpeta, fechaOriginal))
        }

        val adapter = LoteAdapter(listaItems) { loteSeleccionado -> abrirLote(loteSeleccionado.carpetaFisica) }
        rvGestorArchivos.adapter = adapter
    }

    private fun abrirLote(carpetaLote: File) {
        mostrandoLotes = false
        btnVolverLotes.visibility = View.VISIBLE

        val archivos = carpetaLote.listFiles { file -> file.extension.lowercase() in listOf("mp4", "avi") } ?: emptyArray()
        val listaVideos = archivos.map { file ->
            val mb = file.length() / (1024 * 1024)
            VideoItem(file.name, "Tamaño: $mb MB", Uri.fromFile(file))
        }

        val adapter = VideoAdapter(listaVideos) { videoSeleccionado -> reproducirVideoNativo(videoSeleccionado.uri) }
        rvGestorArchivos.adapter = adapter
    }

    override fun onBackPressed() {
        if (!mostrandoLotes && panelDescarga.visibility == View.VISIBLE) {
            cargarLotesLocalesEnUI()
        } else {
            super.onBackPressed()
        }
    }

    private fun reproducirVideoNativo(uriVideo: Uri) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val videoView = VideoView(this)
        videoView.setVideoURI(uriVideo)
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)
        dialog.setContentView(videoView)
        dialog.show()
        videoView.start()
    }

    private fun cambiarModo(modo: Int) {
        if (modo != 1 && isCameraActive) {
            toggleCamaraTablet()
        }

        panelControl.visibility = View.GONE
        panelDescarga.visibility = View.GONE
        panelProcesamiento.visibility = View.GONE
        restablecerEstiloCapsulas()

        val colorActivo = Color.parseColor("#D36D42")

        when (modo) {
            1 -> {
                panelControl.visibility = View.VISIBLE
                cardControl.strokeColor = colorActivo
                cardControl.strokeWidth = 4
            }
            2 -> {
                panelDescarga.visibility = View.VISIBLE
                cardDescarga.strokeColor = colorActivo
                cardDescarga.strokeWidth = 4
                cargarLotesLocalesEnUI()
            }
            3 -> {
                panelProcesamiento.visibility = View.VISIBLE
                cardProcesar.strokeColor = colorActivo
                cardProcesar.strokeWidth = 4
            }
        }
    }

    private fun restablecerEstiloCapsulas() {
        val inactivoBorde = Color.parseColor("#2A2F3A")

        cardControl.strokeColor = inactivoBorde
        cardControl.strokeWidth = 2

        cardDescarga.strokeColor = inactivoBorde
        cardDescarga.strokeWidth = 2

        cardProcesar.strokeColor = inactivoBorde
        cardProcesar.strokeWidth = 2
    }
}