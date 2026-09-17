package com.jaac.avoqado_tpv.core.remotepayment

import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import java.math.BigDecimal

/**
 * 🔴 El aviso que hace entregable a F0.
 *
 * Desde que una obligación pendiente cerca su VENTA en vez de apagar la terminal, el negocio
 * sigue cobrando — y con eso vuelve a ser posible cobrar dos veces la misma venta entrando por
 * otra puerta: volver al carrito (que crea una orden nueva) o teclearla como Pago rápido.
 *
 * 🔑 Desde la libreta esas dos cosas son INDISTINGUIBLES: «reintento de la venta incierta» y
 * «una venta nueva idéntica» se ven igual. Bloquear por importe igual apagaría la segunda taza
 * de café de $50 del día, que es interrumpir el negocio. **Sólo el cajero sabe cuál es cuál**, así
 * que la respuesta correcta es decírselo, no impedírselo.
 *
 * Por eso el texto nombra el IMPORTE y la ANTIGÜEDAD: un conteo («hay 2 cobros pendientes») no
 * le deja decidir nada.
 */
object AvisoDeCobrosPendientes {

    /** Cuántas obligaciones se nombran con su importe antes de contar el resto. */
    private const val MAXIMO_ENUMERADO = 3


    /** null = no hay nada que avisar. La lista viene de la más reciente a la más vieja. */
    fun texto(todas: List<ObligacionPendiente>, ahoraMillis: Long): String? {
        // Checkpoint 2 (E1): una CONTRADICCIÓN con el servidor se avisa aparte y sólo durante VENTANA_CONTRADICCION_MS
        // después del veredicto — es evidencia que Avoqado concilia, no algo que el cajero pueda resolver; la fila se
        // conserva (nunca se poda) aunque el aviso deje de mostrarla.
        val contradicciones = todas.filter { it.contradiccion == 1 && ahoraMillis - it.desdeMillis < VENTANA_CONTRADICCION_MS }
        val pendientes = todas.filter { it.contradiccion == 0 }
        val textoPendientes = textoDePendientes(pendientes, ahoraMillis)
        val textoContradicciones = contradicciones.takeIf { it.isNotEmpty() }?.let { lista ->
            val enumeradas = lista.take(MAXIMO_ENUMERADO).joinToString(", ") { pesos(it.totalCentavos) }
            // Fix 4 (D3b): «tiene evidencia de cobro», no «registró dinero» — la contradicción puede nacer de una aprobación
            // bancaria SIN Payment, y afirmar un registro que no existe es una pantalla que miente.
            "Avoqado tiene evidencia de cobro de ${if (lista.size == 1) "un intento" else "${lista.size} intentos"} ($enumeradas) que esta terminal " +
                "dio por no cobrado o como posible cobro doble: no lo vuelvas a cobrar, Avoqado lo concilia."
        }
        return listOfNotNull(textoPendientes, textoContradicciones).takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun textoDePendientes(pendientes: List<ObligacionPendiente>, ahoraMillis: Long): String? {
        val masReciente = pendientes.firstOrNull() ?: return null
        val importe = pesos(masReciente.totalCentavos)
        val cuando = antiguedad(ahoraMillis - masReciente.desdeMillis)
        if (pendientes.size == 1) {
            return "Quedó un cobro de $importe sin confirmar $cuando. " +
                "Si es esta misma venta, no la cobres otra vez."
        }
        // 🔴 Se NOMBRAN varias, no sólo la más reciente: con una de $120 y otra de $50, decir
        // «2 cobros… $50» no le deja al cajero reconocer la de $120, que es justo lo que el aviso
        // existe para que reconozca (Codex, 2026-09-12). Se enumeran hasta 3 y el resto se cuenta.
        val nombradas = pendientes.take(MAXIMO_ENUMERADO)
        // 🔴 El resto se cuenta sobre la LISTA, nunca contando comas del texto: `$8,856.00` lleva
        // una coma propia y el número saldría mal justo en los importes grandes.
        val resto = pendientes.size - nombradas.size
        val enumeradas = nombradas.joinToString(", ") {
            "${pesos(it.totalCentavos)} ${antiguedad(ahoraMillis - it.desdeMillis)}"
        }
        val cola = if (resto > 0) ", y $resto más" else ""
        return "Quedaron ${pendientes.size} cobros sin confirmar: $enumeradas$cola. " +
            "Si alguno es esta venta, no la cobres otra vez."
    }

    /** 72 h: suficiente para que operaciones actúe; después la contradicción sigue en la libreta, no en el aviso. */
    const val VENTANA_CONTRADICCION_MS = 72L * 60 * 60 * 1000

    private fun pesos(centavos: Long): String =
        CurrencyFormatter.format(BigDecimal(centavos).movePointLeft(2))

    /**
     * 🔴 Nunca una antigüedad negativa ni «hace 0 min»: el reloj del aparato puede ir atrás del que
     * escribió la fila, y un aviso que dice «hace -4 min» se lee como un defecto y se ignora.
     */
    private fun antiguedad(millis: Long): String {
        // ⚠️ Aquí hay DOS protecciones contra un «hace -4 min», y cada una basta sola: el
        // `coerceAtLeast(0)` y la rama `minutos < 1`. Medido el 2026-09-12: romper cualquiera de
        // las dos por separado NO tumba ninguna prueba; rotas las DOS cae la prueba de la fila
        // del futuro. Queda escrito para que nadie borre una creyendo que la otra no existe, y
        // para no llamar decorativa a una prueba que sí guarda el comportamiento.
        val segundos = (millis / 1000).coerceAtLeast(0)
        val minutos = segundos / 60
        return when {
            minutos < 1 -> "hace unos segundos"
            minutos < 60 -> "hace $minutos min"
            else -> "hace ${minutos / 60} h"
        }
    }
}
