package com.jaac.avoqado_tpv

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.motorlab.Cierre
import com.avoqado.motorlab.Informe
import com.avoqado.motorlab.LectorPax
import com.avoqado.motorlab.PaxDeLaTerminal
import com.avoqado.motorlab.RedDelLaboratorio
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Motor de cobro · laboratorio. L-a: la entrada que abre la puerta del laboratorio (`abrir-tpv` abre
 * `com.jaac.avoqado_tpv.MainActivity` en todo paquete `sandbox`). L-c: la LECTURA SECA del adaptador, sin motor ni Blumon:
 * lee la tarjeta sin contacto con un importe distinto de $1, cierra el lector y enseña sólo lo permitido (nunca la banda, el
 * PAN, el criptograma ni el país). El motor y la pantalla de verdad llegan en L-d.
 */
class MainActivity : Activity() {
    private lateinit var boton: Button
    private lateinit var salida: TextView

    /** Cuántos intentos inició este botón (sólo en el hilo de la pantalla): la foto de cada toque enseña un número nuevo. */
    private var lecturas = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        boton = Button(this).apply {
            text = "Lectura seca de \$25.50"
            textSize = 20f
            setOnClickListener { leer() }
        }
        salida = TextView(this).apply {
            text = "Motor de cobro · laboratorio\nTodavía no cobra."
            textSize = 18f
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(boton)
                addView(salida)
            },
        )
    }

    private fun leer() {
        boton.isEnabled = false
        val n = ++lecturas
        salida.text = "Lectura n.º $n\nLeyendo… (acerca la tarjeta)"
        val lector = lectorDelProceso(applicationContext)
        thread(name = "motorlab-lectura-seca") {
            val folio = AttemptId.nuevo()
            val texto = "Lectura n.º $n\n" + if (!lector.siguienteFolio(folio)) {
                "El lector tiene otro dueño: espera a que termine."
            } else {
                val resultado = runBlocking { lector.leer(Operacion.VENTA, IMPORTE, ESPERA) }
                describir(resultado, lector.informes.value?.takeIf { it.folio == folio })
            }
            runOnUiThread {
                salida.text = texto
                boton.isEnabled = true
            }
        }
    }

    private companion object {
        const val IMPORTE = 2_550L // $25.50: distinto de $1 y dentro del tope de la tarjeta del laboratorio ($100)
        const val ESPERA = 30_000L // la misma espera de lectura que el motor

        /** Sólo lo permitido: el resultado, la causa, lo que dijo el kernel, el 9F02, QUÉ tags vinieron y cómo quedó el lector. */
        fun describir(r: ResultadoDeLectura, i: Informe?): String = buildString {
            appendLine("Lectura seca de \$25.50 (no cobra)")
            appendLine(
                "Resultado: " + when (r) {
                    is ResultadoDeLectura.Leida -> "Leída"
                    is ResultadoDeLectura.Cortada -> "Cortada (código ${r.codigoKernel})"
                    ResultadoDeLectura.Vencida -> "Vencida"
                    ResultadoDeLectura.Cancelada -> "Cancelada"
                },
            )
            if (i == null) {
                append("Sin informe del lector")
                return@buildString
            }
            appendLine("Causa: ${i.causa} · código: ${i.codigo ?: "—"}")
            appendLine("Marca: ${i.marca ?: "—"} · kernel: ${i.resultado ?: "—"} · CVM: ${i.cvm ?: "—"}")
            appendLine("9F02: ${i.importeDelKernel ?: "—"} (pedido $IMPORTE): " + if (i.importeDelKernel == IMPORTE) "coincide" else "NO coincide")
            appendLine("Tags que vinieron: " + i.tagsPresentes.sorted().joinToString(" ") { "%X".format(it) })
            appendLine(
                when (i.cierre) {
                    Cierre.NO_SE_ABRIO -> "Este intento no abrió el lector"
                    Cierre.ACREDITADO -> "Lector cerrado"
                    Cierre.SIN_ACREDITAR -> "Cierre del lector sin acreditar: " + i.fallasDelCierre.joinToString()
                },
            )
            i.error?.let { appendLine("Error: $it") }
        }
    }
}

/** Un solo lector por proceso («un dueño por PROCESO», pre-plan §3): recrear la pantalla no crea otro. L-d lo pasa a su `Application`. */
@Volatile private var lectorUnico: LectorPax? = null

private fun lectorDelProceso(contexto: Context): LectorPax = lectorUnico ?: synchronized(MainActivity::class.java) {
    lectorUnico ?: LectorPax(PaxDeLaTerminal(contexto), RedDelLaboratorio(contexto)).also { lectorUnico = it }
}
