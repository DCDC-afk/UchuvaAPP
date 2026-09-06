package com.example.uchuvatwinapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class VideoAdapter(
    private val listaVideos: List<VideoItem>,
    private val onClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre: TextView = view.findViewById(R.id.tvNombreVideo)
        val tvTamaño: TextView = view.findViewById(R.id.tvTamañoVideo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = listaVideos[position]
        holder.tvNombre.text = video.nombre
        holder.tvTamaño.text = video.tamañoTexto

        // Habilita el clic sobre cada video
        holder.itemView.setOnClickListener { onClick(video) }
    }

    override fun getItemCount(): Int = listaVideos.size
}