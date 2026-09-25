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
    fun texto(todas: List<ObligacionPendiente>, ahoraMillis: Long, esPax: Boolean = false): String? {
        // Checkpoint 2 (E1): una CONTRADICCIÓN con el servidor se avisa aparte y sólo durante VENTANA_CONTRADICCION_MS
        // después del veredicto — es evidencia que Avoqado concilia, no algo que el cajero pueda resolver; la fila se
        // conserva (nunca se poda) aunque el aviso deje de mostrarla.
        val contradicciones = todas.filter { it.contradiccion == 1 && ahoraMillis - it.desdeMillis < VENTANA_CONTRADICCION_MS }
        // En la PAX cada pendiente se quita solo a los VENTANA_AVISO_PAX_MS; la fila sigue en la libreta para conciliar.
        val pendientes = todas.filter { it.contradiccion == 0 && !(esPax && ahoraMillis - it.desdeMillis >= VENTANA_AVISO_PAX_MS) }
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

    /**
     * 10 min (decisión del founder, 24-sep): en la PAX el aviso ES el cinturón y se quita solo. La PAX no recibe el aviso del
     * banco, así que sin esto el mensaje se quedaría para siempre; a los 10 min el cliente ya se fue y el aviso ya no evita
     * ningún cobro doble. La fila NO se toca: sigue guardada para conciliar. La Nexgo no cambia.
     */
    const val VENTANA_AVISO_PAX_MS = 10L * 60 * 1000

    /** 72 h: suficiente para que operaciones actúe; después la contradicción sigue en la libreta, no en el aviso. */
    const val VENTANA_CONTRADICCION_MS = 72L * 60 * 60 * 1000

    /**
     * «$10.00, hace 2 min» — el MISMO formato de importe y antigüedad que este aviso, para que la pantalla que nombra
     * la fila que aparta la terminal (§3.8, rechazo de la barrera en un cobro del POS) diga lo mismo que el aviso F0.
     */
    fun importeYAntiguedad(totalCentavos: Long, desdeMillis: Long, ahoraMillis: Long): String =
        "${pesos(totalCentavos)}, ${antiguedad(ahoraMillis - desdeMillis)}"

    /**
     * 🔴 Lo que dice la terminal cuando la libreta NO admitió el intento.
     *
     * Antes decía «No se pudo guardar el intento **o** esta venta tiene un cobro pendiente»: dos causas
     * muy distintas en una sola frase, sin nombrar la venta. El founder se lo topó el 21-sep en la TPV y
     * no tenía forma de saber cuál le había tocado ni qué resolver.
     *
     * Son TRES desenlaces y cada uno pide algo distinto del cajero:
     *  - **la VENTA está cercada** — hay un cobro sin confirmar de ESTA misma cuenta: resolverlo, porque
     *    volver a cobrarla es el cobro doble que la cerca existe para impedir;
     *  - **el APARATO está apartado** — la obligación es de OTRA venta: se resuelve, o se cobra en otra
     *    terminal;
     *  - **no se pudo guardar** — ni siquiera se llegó a la cerca: se reintenta.
     *
     * @param apartaLaVenta `"$120.00, hace 3 min"` de la fila que cerca ESTA venta, o null.
     * @param apartaElAparato lo mismo para la fila que aparta la terminal, o null.
     */
    /**
     * 🔴 Decisión del founder (23-sep, «corrige y avisa»): lo que ve el cajero cuando la terminal se enteró TARDE de que un
     * cobro que dio por no cobrado SÍ pasó (el lector dijo «cancelado» y el banco aprobó un segundo después — Testarudo, 16-sep).
     * Nombra importe y antigüedad para que lo reconozca, y dice lo único que importa: no volver a cobrarlo. Va con «Entendido».
     */
    fun cobroQueSiPaso(totalCentavos: Long, desdeMillis: Long, ahoraMillis: Long): String =
        "El cobro de ${pesos(totalCentavos)} (${antiguedad(ahoraMillis - desdeMillis)}) SÍ pasó: Avoqado ya lo registró, " +
            "así que no lo vuelvas a cobrar."

    fun barreraDeLaLibreta(
        apartaLaVenta: String?, apartaElAparato: String?, laVentaYaCobrada: Boolean = false, elAparatoYaCobrado: Boolean = false,
    ): String = when {
        // Decisión del founder (23-sep): un cobro que SÍ pasó se resuelve con el «Entendido» del aviso, no en otra terminal.
        apartaLaVenta != null && laVentaYaCobrada ->
            "Esta venta ya se cobró ($apartaLaVenta): Avoqado lo registró. NO se inició otro cobro. " +
                "Toca «Entendido» en el aviso de arriba."
        // La venta primero: es la más específica y la que de verdad puede cobrarse dos veces.
        apartaLaVenta != null ->
            "Esta venta ya tiene un cobro sin confirmar ($apartaLaVenta). NO se inició otro cobro. " +
                "Revísalo en Transacciones antes de volver a cobrarla."
        apartaElAparato != null && elAparatoYaCobrado ->
            "La terminal está apartada por un cobro que SÍ pasó ($apartaElAparato). NO se inició este cobro. " +
                "Toca «Entendido» en el aviso de arriba y vuelve a cobrar."
        apartaElAparato != null ->
            "La terminal está apartada por otro cobro sin confirmar ($apartaElAparato). NO se inició este cobro. " +
                "Resuélvelo o cobra con otra terminal."
        // 🔴 Nada que nombrar NO es «no hay cerca»: también es «no se pudo leer la libreta». Por eso el
        // texto no afirma que la venta esté libre — sólo dice lo único que consta: no se cobró.
        else -> "No se pudo guardar el intento en esta terminal. NO se cobró. Vuelve a intentarlo."
    }

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
