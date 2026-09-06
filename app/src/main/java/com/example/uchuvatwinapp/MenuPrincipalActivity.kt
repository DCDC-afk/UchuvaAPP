package com.example.uchuvatwinapp

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.transition.platform.MaterialSharedAxis

class MenuPrincipalActivity : ComponentActivity() {

    private lateinit var imgFondo: ImageView

    // Lista de fondos en drawable
    private val listaFondos = intArrayOf(
        R.drawable.bg_uchuva_pixel,
        R.drawable.bg_uchuva_art,
        R.drawable.bg_uchuva_art_dron
    )
    private var indiceFondoActual = 0

    // Detector de Triple Clic
    private var contadorToques = 0
    private val handlerToques = Handler(Looper.getMainLooper())
    private val resetToquesRunnable = Runnable { contadorToques = 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_principal)

        val rootLayout = findViewById<ConstraintLayout>(R.id.rootMenuPrincipal)
        imgFondo = findViewById(R.id.imgAnimacionUchuva)
        val btnDron = findViewById<MaterialButton>(R.id.btnModuloDron)
        val btnAnalisis = findViewById<MaterialButton>(R.id.btnModuloAnalisis)

        // Asignar imagen inicial
        imgFondo.setImageResource(listaFondos[0])

        // Captura de toques en la pantalla vacía
        rootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                registrarToquePantalla()
            }
            true
        }

        aplicarEfectoTactil(btnDron) {
            abrirConTransicionZ(DronActivity::class.java)
        }

        aplicarEfectoTactil(btnAnalisis) {
            abrirConTransicionZ(MainActivity::class.java)
        }
    }

    private fun registrarToquePantalla() {
        contadorToques++

        handlerToques.removeCallbacks(resetToquesRunnable)
        handlerToques.postDelayed(resetToquesRunnable, 400) // Ventana de 400ms

        if (contadorToques >= 3) {
            contadorToques = 0
            handlerToques.removeCallbacks(resetToquesRunnable)
            cambiarFondoDinamico()
        }
    }

    private fun cambiarFondoDinamico() {
        // Lógica circular dinámica (avanza 0 -> 1 -> 2 -> 0 -> ...)
        indiceFondoActual = (indiceFondoActual + 1) % listaFondos.size
        val nuevoFondoResId = listaFondos[indiceFondoActual]

        // Transición de cambio de imagen
        imgFondo.animate()
            .alpha(0.0f)
            .setDuration(150)
            .withEndAction {
                imgFondo.setImageResource(nuevoFondoResId)
                imgFondo.animate()
                    .alpha(1.0f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    private fun abrirConTransicionZ(claseDestino: Class<*>) {
        window.exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply {
            duration = 350L
        }
        window.reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply {
            duration = 350L
        }

        val intent = Intent(this, claseDestino)
        val options = ActivityOptions.makeSceneTransitionAnimation(this)
        startActivity(intent, options.toBundle())
    }

    private fun aplicarEfectoTactil(view: View, onClick: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).withEndAction {
                        if (event.action == MotionEvent.ACTION_UP) {
                            v.performClick()
                            onClick()
                        }
                    }.start()
                }
            }
            true
        }
    }
}