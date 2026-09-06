package com.example.uchuvatwinapp

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var mapaView: MapaDigitalView
    private lateinit var btnToggleLotes: Button

    private lateinit var panelMenuLateral: LinearLayout
    private lateinit var rvListaMenuLotes: RecyclerView
    private var isMenuAbierto = false

    private lateinit var tvTotal: TextView
    private lateinit var tvMaduras: TextView
    private lateinit var tvInmaduras: TextView

    private lateinit var panelDetalle: LinearLayout
    private lateinit var tvDetalleId: TextView
    private lateinit var tvDetalleEstado: TextView
    private lateinit var tvDetalleConfianza: TextView
    private lateinit var tvDetalleCoordenadas: TextView
    private lateinit var tvDetalleFrame: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.enterTransition = com.google.android.material.transition.platform.MaterialSharedAxis(
            com.google.android.material.transition.platform.MaterialSharedAxis.Z, true
        ).apply { duration = 350L }

        window.returnTransition = com.google.android.material.transition.platform.MaterialSharedAxis(
            com.google.android.material.transition.platform.MaterialSharedAxis.Z, false
        ).apply { duration = 350L }

        setContentView(R.layout.activity_main)

        mapaView = findViewById(R.id.mapaDigital)
        btnToggleLotes = findViewById(R.id.btnToggleLotes)
        panelMenuLateral = findViewById(R.id.panelMenuLateral)
        rvListaMenuLotes = findViewById(R.id.rvListaMenuLotes)

        tvTotal = findViewById(R.id.tvTotal)
        tvMaduras = findViewById(R.id.tvMaduras)
        tvInmaduras = findViewById(R.id.tvInmaduras)

        panelDetalle = findViewById(R.id.panelDetalleUchuva)
        tvDetalleId = findViewById(R.id.tvDetalleId)
        tvDetalleEstado = findViewById(R.id.tvDetalleEstado)
        tvDetalleConfianza = findViewById(R.id.tvDetalleConfianza)
        tvDetalleCoordenadas = findViewById(R.id.tvDetalleCoordenadas)
        tvDetalleFrame = findViewById(R.id.tvDetalleFrame)

        rvListaMenuLotes.layoutManager = LinearLayoutManager(this)

        mapaView.onUchuvaSeleccionadaListener = { uchuva ->
            if (uchuva != null) {
                mostrarDetalleUchuva(uchuva)
            } else {
                panelDetalle.visibility = View.GONE
            }
        }

        btnToggleLotes.setOnClickListener {
            toggleMenuLateral()
        }
    }

    private fun toggleMenuLateral() {
        val anchoMenuPx = 280f * resources.displayMetrics.density

        if (isMenuAbierto) {
            panelMenuLateral.animate().translationX(-anchoMenuPx).setDuration(250).start()
            btnToggleLotes.animate().translationX(0f).setDuration(250).start()
            btnToggleLotes.text = "☰ LOTES"
            isMenuAbierto = false
        } else {
            cargarLotesEnMenu()
            panelMenuLateral.animate().translationX(0f).setDuration(250).start()
            btnToggleLotes.animate().translationX(anchoMenuPx).setDuration(250).start()
            btnToggleLotes.text = "✖"
            isMenuAbierto = true
        }
    }

    private fun cargarLotesEnMenu() {
        val directorioResultados = File(getExternalFilesDir(null), "UchuvaTwin_Resultados")

        if (!directorioResultados.exists()) {
            Toast.makeText(this, "Aún no hay lotes procesados.", Toast.LENGTH_SHORT).show()
            return
        }

        val carpetasLotes = directorioResultados.listFiles { file ->
            file.isDirectory && file.name.endsWith("_procesado") && File(file, ".completado").exists()
        }?.toList() ?: emptyList()

        if (carpetasLotes.isEmpty()) {
            Toast.makeText(this, "No se encontraron lotes procesados completos.", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = MenuLotesAdapter(carpetasLotes.sortedBy { it.name }) { carpetaSeleccionada ->
            toggleMenuLateral()
            extraerJsonsDelLote(carpetaSeleccionada)
        }
        rvListaMenuLotes.adapter = adapter
    }

    private fun extraerJsonsDelLote(carpetaLote: File) {
        val carpetaJson = File(carpetaLote, "Reportes_JSON")

        if (!carpetaJson.exists()) {
            Toast.makeText(this, "El lote seleccionado está dañado.", Toast.LENGTH_SHORT).show()
            return
        }

        val archivosJson = carpetaJson.listFiles { file -> file.extension.lowercase() == "json" }?.toList() ?: emptyList()

        if (archivosJson.isEmpty()) {
            Toast.makeText(this, "El lote está vacío.", Toast.LENGTH_SHORT).show()
            return
        }

        procesarMultiplesArchivosLocales(archivosJson)
    }

    private fun procesarMultiplesArchivosLocales(archivos: List<File>) {
        try {
            val archivosOrdenados = archivos.sortedBy { it.name }
            val gson = Gson()
            val tipoLista = object : TypeToken<List<ReporteUchuva>>() {}.type

            val listaArbustos = mutableListOf<ArbustoDigital>()
            var idArbustoContador = 1

            var totalGeneral = 0
            var madurasGeneral = 0
            var inmadurasGeneral = 0

            val separacionSurcosMetros = 2.5f
            val offsetCaraBMetros = 0.3f

            for (i in archivosOrdenados.indices step 2) {
                val uchuvasDelArbusto = mutableListOf<NodoDigital>()
                val posicionXM = (idArbustoContador - 1) * separacionSurcosMetros

                val uchuvasCaraA = leerNodosDesdeArchivo(archivosOrdenados[i], gson, tipoLista, posicionXM)
                uchuvasDelArbusto.addAll(uchuvasCaraA)

                if (i + 1 < archivosOrdenados.size) {
                    val uchuvasCaraB = leerNodosDesdeArchivo(archivosOrdenados[i + 1], gson, tipoLista, posicionXM + offsetCaraBMetros)
                    uchuvasDelArbusto.addAll(uchuvasCaraB)
                }

                if (uchuvasDelArbusto.isNotEmpty()) {
                    val limiteSupYM = uchuvasDelArbusto.minOf { it.ejeYM }
                    val limiteInfYM = uchuvasDelArbusto.maxOf { it.ejeYM }

                    totalGeneral += uchuvasDelArbusto.size

                    madurasGeneral += uchuvasDelArbusto.count { it.estado.equals("Maduro", ignoreCase = true) }
                    inmadurasGeneral += uchuvasDelArbusto.count { it.estado.equals("Inmaduro", ignoreCase = true) }

                    listaArbustos.add(
                        ArbustoDigital(
                            idArbusto = idArbustoContador,
                            uchuvas = uchuvasDelArbusto,
                            limiteSuperiorYM = limiteSupYM,
                            limiteInferiorYM = limiteInfYM,
                            centroXM = posicionXM + (offsetCaraBMetros / 2f)
                        )
                    )
                    idArbustoContador++
                }
            }

            tvTotal.text = totalGeneral.toString()
            tvMaduras.text = madurasGeneral.toString()
            tvInmaduras.text = inmadurasGeneral.toString()

            panelDetalle.visibility = View.GONE
            mapaView.cargarDatos(listaArbustos)

            Toast.makeText(this, "Graficados ${listaArbustos.size} surcos", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error procesando el Lote", Toast.LENGTH_LONG).show()
        }
    }

    private fun leerNodosDesdeArchivo(
        file: File,
        gson: Gson,
        tipo: java.lang.reflect.Type,
        ejeXMetros: Float
    ): List<NodoDigital> {
        val jsonString = file.readText()
        val reportes: List<ReporteUchuva> = gson.fromJson(jsonString, tipo) ?: emptyList()

        return reportes.map {
            // Jitter para dar un aspecto orgánico a los lados de la línea central del surco
            // basado en la altura física de la uchuva reportada por la cámara
            val jitterX = (it.coordenadaYM - 0.8f) * 0.3f

            NodoDigital(
                id = it.idTracking,
                estado = it.estado,
                ejeXM = ejeXMetros + jitterX, // Se distribuyen horizontalmente de forma natural
                ejeYM = it.frameCruce * 0.015f, // NUEVO: La distancia del surco es el avance del dron en el tiempo
                confianza = it.confianza,
                frameCruce = it.frameCruce
            )
        }
    }

    private fun mostrarDetalleUchuva(uchuva: NodoDigital) {
        panelDetalle.visibility = View.VISIBLE

        tvDetalleId.text = "UCHUVA ID: #${uchuva.id}"
        tvDetalleId.setTextColor(Color.parseColor("#FFF59D"))

        tvDetalleEstado.text = "ESTADO: ${uchuva.estado.uppercase(Locale.ROOT)}"

        if (uchuva.estado.equals("Maduro", ignoreCase = true)) {
            tvDetalleEstado.setTextColor(Color.parseColor("#D36D42"))
        } else {
            tvDetalleEstado.setTextColor(Color.parseColor("#A5D6A7"))
        }

        val porcConfianza = String.format(Locale.US, "%.1f", uchuva.confianza * 100f)
        tvDetalleConfianza.text = "CONFIANZA YOLO: $porcConfianza%"

        val xFormatted = String.format(Locale.US, "%.2f", uchuva.ejeXM)
        val yFormatted = String.format(Locale.US, "%.2f", uchuva.ejeYM)
        tvDetalleCoordenadas.text = "POSICIÓN: X=${xFormatted}m | Y=${yFormatted}m"

        tvDetalleFrame.text = "FRAME VIDEO: #${uchuva.frameCruce}"
    }

    inner class MenuLotesAdapter(
        private val lotes: List<File>,
        private val onClick: (File) -> Unit
    ) : RecyclerView.Adapter<MenuLotesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvNombreLoteMenuGemelo: TextView = view.findViewById(R.id.tvNombreLoteMenuGemelo)
            val tvFechaLoteMenuGemelo: TextView = view.findViewById(R.id.tvFechaLoteMenuGemelo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_menu_gemelo, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val archivoLote = lotes[position]
            holder.tvNombreLoteMenuGemelo.text = archivoLote.name.replace("_procesado", "").uppercase()

            val metaFile = File(archivoLote, "metadata.txt")
            holder.tvFechaLoteMenuGemelo.text = if (metaFile.exists()) metaFile.readText() else "Fecha desconocida"

            holder.itemView.setOnClickListener {
                onClick(archivoLote)
            }
        }

        override fun getItemCount(): Int = lotes.size
    }
}