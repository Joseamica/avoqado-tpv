package com.jaac.avoqado_tpv

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.avoqado.motorlab.AppDelLaboratorio
import com.avoqado.motorlab.Laboratorio
import com.avoqado.motorlab.ModoDeCierre
import com.avoqado.motorlab.ModoDeSondeo
import com.avoqado.motorlab.Orden
import com.avoqado.motorlab.centavosDe
import com.avoqado.motorlab.momentosDePintar
import com.avoqado.motorlab.porQueNoCobra
import com.avoqado.motorlab.textoDeLaSituacion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Motor de cobro · laboratorio (bloque 4-L · L-d1): la entrada que abre la puerta del laboratorio (`abrir-tpv` abre
 * `com.jaac.avoqado_tpv.MainActivity` en todo paquete `sandbox`). Es SÓLO la vista: cada toque es una orden al carril del
 * laboratorio de `AppDelLaboratorio`, que la acepta o la rechaza, y lo que se pinta sale del estado vigente del carril. No crea
 * lector, motor ni historial: recrearla no pierde ni repite trabajo. La identidad nunca se enseña: sus tres campos van ocultos,
 * se vacían al guardar y la cabeza sólo dice si está guardada y si es la del motor.
 */
class MainActivity : Activity() {
    private lateinit var app: AppDelLaboratorio
    private lateinit var lab: Laboratorio
    private var pantalla = 0
    private var pintor: Job? = null

    private lateinit var cabeza: TextView
    private lateinit var lecturaSeca: Button
    private lateinit var resultado: TextView
    private lateinit var medicion: TextView
    private lateinit var cobrar: Button
    private lateinit var folios: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as AppDelLaboratorio
        lab = app.laboratorio
        pantalla = ++pantallas
        cabeza = TextView(this)
        lecturaSeca = boton("Lectura seca de \$25.50") { lab.orden(Orden.LecturaSeca) }
        resultado = TextView(this)
        // La medición de chip (26-nov): sin dinero y sin red. Los dos modos se eligen tocando su botón; el texto dice el elegido.
        val sondeo = Button(this).apply {
            text = "Sondeo: ${sondeoElegido.texto}"
            setOnClickListener {
                sondeoElegido = ModoDeSondeo.entries[(sondeoElegido.ordinal + 1) % ModoDeSondeo.entries.size]
                text = "Sondeo: ${sondeoElegido.texto}"
            }
        }
        val cierre = Button(this).apply {
            text = "Cierre: ${cierreElegido.texto}"
            setOnClickListener {
                cierreElegido = ModoDeCierre.entries[(cierreElegido.ordinal + 1) % ModoDeCierre.entries.size]
                text = "Cierre: ${cierreElegido.texto}"
            }
        }
        val medir = boton("Medir chip (sin dinero)") { app.medidor.medir(sondeoElegido, cierreElegido) }
        val cancelarMedicion = boton("Cancelar medición") { app.medidor.cancelar() }
        medicion = TextView(this)
        val serie = oculto("Serie de Blumon", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val posId = oculto("posId", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val fisica = oculto("Serie física (vacía: la misma)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val guardar = boton("Guardar identidad") {
            val o = Orden.Guardar(serie.text.toString(), posId.text.toString(), fisica.text.toString())
            listOf(serie, posId, fisica).forEach { it.setText("") }
            lab.orden(o)
        }
        val importe = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("10.00")
        }
        cobrar = boton("Cobrar") {
            lab.orden(centavosDe(importe.text.toString())?.let { Orden.Cobrar(it) } ?: Orden.Aviso("importe sin forma: de 0.01 a 100.00 pesos"))
        }
        val cancelar = boton("Cancelar") { lab.orden(Orden.Cancelar) }
        // Bloque P0: el SERVIDOR colgado. El texto sale del respondedor, no de la pantalla: recrearla no lo pierde.
        val mudo = Button(this).apply {
            text = textoDelMudo(app.mudo)
            setOnClickListener { text = textoDelMudo(app.alternarMudo()) }
        }
        folios = TextView(this) // al final: crecen
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            listOf(cabeza, lecturaSeca, resultado, sondeo, cierre, medir, cancelarMedicion, medicion, serie, posId, fisica, guardar, importe, cobrar, cancelar, mudo, folios).forEach { addView(it) }
        }
        setContentView(ScrollView(this).apply { addView(columna) })
        pintar() // este hilo ES el carril: el primer cuadro ya trae el estado vigente, no una pantalla vacía con «Cobrar» encendido
    }

    /**
     * Pinta en cada cambio del carril o de los folios y en cada latido (cada segundo) mientras se ve; al dejar de verse se
     * suelta, y el latido con él. La fila del motor puede cambiar sin evento (un reverso tras «por confirmar»): al verse y en
     * cada latido el laboratorio la relee. La cabeza lee la red al pintar, así que el latido la mantiene al día aunque el carril
     * no publique. `pintar()` no toca los campos de la identidad ni el importe: repintar no borra lo que se teclea. Es sólo la
     * vista: no toca el carril ni retrasa ninguna orden. El latido revisa cada segundo, pero sólo escribe el texto que cambió.
     */
    override fun onStart() {
        super.onStart()
        lab.releer()
        pintor = CoroutineScope(Dispatchers.Default).launch {
            val latido = flow { while (true) { delay(1_000); lab.releer(); emit(Unit) } }
            momentosDePintar(lab.situacion, merge(lab.folios, app.medidor.texto), latido).collect { runOnUiThread { if (!isDestroyed) pintar() } }
        }
    }

    override fun onStop() {
        pintor?.cancel()
        pintor = null
        super.onStop()
    }

    /** El estado VIGENTE del carril, leído aquí: este hilo es el carril (Codex r3 #2). */
    private fun pintar() {
        val s = lab.situacion.value
        cabeza.ponSiCambia("Pantalla n.º $pantalla\n${app.cabecera()}\n${textoDeLaSituacion(s)}")
        resultado.ponSiCambia(s.lecturaSeca ?: "Lectura seca: sin tocar")
        medicion.ponSiCambia(app.medidor.texto.value)
        cobrar.isEnabled = porQueNoCobra(s) == null
        lecturaSeca.isEnabled = s.trabajo == null
        folios.ponSiCambia(lab.folios.value)
    }

    /**
     * Escribe sólo si el texto cambió. Un `setText` con el mismo texto también despierta a la accesibilidad, y con el latido de
     * un segundo la pantalla nunca quedaba quieta: `uiautomator dump` contestaba «could not get idle state» y el laboratorio no
     * podía leer la app (hallado en la PAX el 25-nov).
     */
    private fun TextView.ponSiCambia(texto: String) {
        if (text.toString() != texto) text = texto
    }

    private fun boton(texto: String, alTocar: () -> Unit) = Button(this).apply {
        text = texto
        setOnClickListener { alTocar() }
    }

    /** Un campo de la identidad: una línea PRIMERO, luego el tipo y el ocultado (en ese orden, Codex r2 #6), y sin autollenado. */
    private fun oculto(nombre: String, tipo: Int) = EditText(this).apply {
        hint = nombre
        contentDescription = nombre
        setSingleLine(true)
        inputType = tipo
        transformationMethod = PasswordTransformationMethod.getInstance()
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun textoDelMudo(prendido: Boolean) = "Servidor mudo: ${if (prendido) "sí" else "no"}"

    private companion object {
        /** Cuántas veces se creó la pantalla en este proceso (sólo en el hilo de la pantalla): «Pantalla n.º». */
        var pantallas = 0

        /** Los modos elegidos de la medición de chip (sólo en el hilo de la pantalla): recrearla no los pierde. */
        var sondeoElegido = ModoDeSondeo.CHIP
        var cierreElegido = ModoDeCierre.BANCO_NO_CONTESTO
    }
}
