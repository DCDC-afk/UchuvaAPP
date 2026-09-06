package com.example.uchuvatwinapp

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.io.File

data class LoteProcesable(
    val nombreCarpeta: String,
    val rutaOrigen: File,
    val estaProcesado: Boolean,
    val cantidadVideos: Int,
    val fechaOriginal: String // DATO QUE VIENE DEL SERVIDOR
)

class AdapterLotesProcesables(
    private var listaLotes: List<LoteProcesable>,
    private val onLoteSeleccionado: (LoteProcesable) -> Unit
) : RecyclerView.Adapter<AdapterLotesProcesables.LoteViewHolder>() {

    private var posicionSeleccionada = -1

    class LoteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val contenedor: MaterialCardView = view.findViewById(R.id.cardLoteProcesable)
        val ivIconoCarpeta: ImageView = view.findViewById(R.id.ivIconoCarpeta)
        val tvNombre: TextView = view.findViewById(R.id.tvNombreLote)
        val tvEstado: TextView = view.findViewById(R.id.tvEstadoLote)
        val tvFecha: TextView = view.findViewById(R.id.tvFechaLote)
        val tvCheckProcesado: TextView = view.findViewById(R.id.tvCheckProcesado)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LoteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lote_procesable, parent, false)
        return LoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: LoteViewHolder, position: Int) {
        val lote = listaLotes[position]

        holder.tvNombre.text = lote.nombreCarpeta
        holder.tvFecha.text = lote.fechaOriginal // inyectamos directo

        val colorTemaHex = if (lote.estaProcesado) {
            "#D36D42" // Naranja
        } else {
            "#FFF59D" // Amarillo Pastel
        }
        val colorTema = Color.parseColor(colorTemaHex)

        holder.tvNombre.setTextColor(colorTema)
        holder.tvEstado.setTextColor(colorTema)
        holder.tvFecha.setTextColor(colorTema)
        holder.tvCheckProcesado.setTextColor(colorTema)

        if (lote.estaProcesado) {
            holder.ivIconoCarpeta.setImageResource(R.drawable.ic_folder_uchuva_procesado)
            holder.tvEstado.text = "✓ PROCESADO (${lote.cantidadVideos} videos)"
            holder.tvCheckProcesado.text = "✓"
        } else {
            holder.ivIconoCarpeta.setImageResource(R.drawable.ic_folder_uchuva_pendiente)
            holder.tvEstado.text = "PENDIENTE (${lote.cantidadVideos} videos)"
            holder.tvCheckProcesado.text = "›"
        }

        holder.contenedor.strokeColor = colorTema

        if (posicionSeleccionada == position) {
            holder.contenedor.setCardBackgroundColor(Color.parseColor("#2A221E"))
            holder.contenedor.strokeWidth = 4
        } else {
            holder.contenedor.setCardBackgroundColor(Color.parseColor("#171513"))
            holder.contenedor.strokeWidth = 1
        }

        holder.itemView.setOnClickListener {
            val posicionAnterior = posicionSeleccionada
            posicionSeleccionada = holder.adapterPosition
            notifyItemChanged(posicionAnterior)
            notifyItemChanged(posicionSeleccionada)
            onLoteSeleccionado(lote)
        }
    }

    override fun getItemCount(): Int = listaLotes.size

    fun actualizarLista(nuevaLista: List<LoteProcesable>) {
        listaLotes = nuevaLista
        posicionSeleccionada = -1
        notifyDataSetChanged()
    }
}