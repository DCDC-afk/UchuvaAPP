package com.example.uchuvatwinapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

data class LoteItem(
    val nombre: String,
    val cantidadVideos: Int,
    val tamañoMB: Long,
    val carpetaFisica: File,
    val fechaOriginal: String // DATO QUE VIENE DEL SERVIDOR
)

class LoteAdapter(
    private val listaLotes: List<LoteItem>,
    private val onClick: (LoteItem) -> Unit
) : RecyclerView.Adapter<LoteAdapter.LoteViewHolder>() {

    class LoteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre: TextView = view.findViewById(R.id.tvNombreLoteMenu)
        val tvDetalles: TextView = view.findViewById(R.id.tvDetallesLote)
        val tvFecha: TextView = view.findViewById(R.id.tvFechaLoteMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_menu_lote, parent, false)
        return LoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: LoteViewHolder, position: Int) {
        val lote = listaLotes[position]

        holder.tvNombre.text = lote.nombre.uppercase()
        holder.tvDetalles.text = "Contenido: ${lote.cantidadVideos} videos  |  Tamaño: ${lote.tamañoMB} MB"
        holder.tvFecha.text = lote.fechaOriginal

        holder.itemView.setOnClickListener { onClick(lote) }
    }

    override fun getItemCount(): Int = listaLotes.size
}