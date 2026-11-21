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
        // Cada pendiente se quita solo a los VENTANA_AVISO_MS; la fila sigue en la libreta para conciliar.
        val pendientes = todas.filter { it.contradiccion == 0 && ahoraMillis - it.desdeMillis < VENTANA_AVISO_MS }
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
     * 🔴 D20 (founder, 17-nov: «lista»): el recuadro «Por revisar» de Pagos, sin la ventana de 10 min. El aviso verde se quita a los
     * 10 min porque ya no evita un cobro doble; la duda sigue, y sin esto no le quedaba ninguna salida en la terminal. No dice que
     * Avoqado lo concilie: un Pago rápido de la PAX no llega a Avoqado (D22), y prometerlo sería una pantalla que miente.
     */
    fun textoPorRevisar(cobros: List<ObligacionPendiente>, ahoraMillis: Long, recortada: Boolean = false): String? {
        val masReciente = cobros.firstOrNull() ?: return null
        // Codex r1 #1 y r2: con la consulta llena puede haber más viejos; se dice y el recuadro ofrece «Ver anteriores».
        val tope = if (recortada) " Hay más anteriores: toca «Ver anteriores»." else ""
        // F1 (tarea 4): lo revisa el gerente con su PIN; el cajero no decide. F1-textos (catálogo D5, fila 23): más corto para el
        // recuadro de 360 dp, y sólo prohíbe la TARJETA (el efectivo sí se deja, D10 «B»).
        if (cobros.size == 1) {
            return "Por revisar: cobro con tarjeta de ${pesos(masReciente.totalCentavos)} sin confirmar " +
                "(${antiguedad(ahoraMillis - masReciente.desdeMillis)}). El gerente lo revisa con su PIN en «Revisar». " +
                "Mientras, no lo cobres otra vez con tarjeta.$tope"
        }
        val nombradas = cobros.take(MAXIMO_ENUMERADO)
        val resto = cobros.size - nombradas.size
        val enumeradas = nombradas.joinToString(", ") { "${pesos(it.totalCentavos)} ${antiguedad(ahoraMillis - it.desdeMillis)}" }
        val cola = if (resto > 0) ", y $resto más" else ""
        return "Por revisar: ${cobros.size} cobros con tarjeta sin confirmar: $enumeradas$cola. " +
            "El gerente los revisa con su PIN en «Revisar». Mientras, no los cobres otra vez con tarjeta.$tope"
    }

    /**
     * 10 min, en TODA terminal (founder: 24-sep para la PAX, 25-sep para todas — «ninguna duda apaga la terminal»). A los
     * 10 min el cliente ya se fue y el aviso ya no evita ningún cobro doble. La fila NO se toca: sigue para conciliar.
     */
    const val VENTANA_AVISO_MS = 10L * 60 * 1000

    /** 72 h: suficiente para que operaciones actúe; después la contradicción sigue en la libreta, no en el aviso. */
    const val VENTANA_CONTRADICCION_MS = 72L * 60 * 60 * 1000

    /**
     * «$10.00, hace 2 min» — el MISMO formato de importe y antigüedad que este aviso, para que la pantalla que nombra
     * la fila que aparta la terminal (§3.8, rechazo de la barrera en un cobro del POS) diga lo mismo que el aviso F0.
     */
    fun importeYAntiguedad(totalCentavos: Long, desdeMillis: Long, ahoraMillis: Long): String =
        "${pesos(totalCentavos)}, ${antiguedad(ahoraMillis - desdeMillis)}"

    /**
     * F1 (tarea 4): «$120.00 · 14:32 · ref E6A1B2» — el renglón que elige el gerente: importe, hora del negocio y una referencia corta
     * que distingue dos filas del mismo importe y minuto. La hora va en la zona del negocio (nunca la del aparato).
     */
    fun renglonParaElGerente(totalCentavos: Long, desdeMillis: Long, referencia: String, zona: java.time.ZoneId): String =
        "${pesos(totalCentavos)} · ${java.time.Instant.ofEpochMilli(desdeMillis).atZone(zona).format(HORA)} · ref $referencia"

    /** F1 (Codex código r1 #10): «$120.00 de las 13:05», lo que el gerente firma en el paso del PIN. */
    fun pesosYHora(totalCentavos: Long, desdeMillis: Long, zona: java.time.ZoneId): String =
        "${pesos(totalCentavos)} de las ${java.time.Instant.ofEpochMilli(desdeMillis).atZone(zona).format(HORA)}"

    private val HORA = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    /** «$120.00 · hace 3 min»: el renglón de la lista del botón «No se cobró». */
    fun renglonDeCobro(totalCentavos: Long, desdeMillis: Long, ahoraMillis: Long): String =
        "${pesos(totalCentavos)} · ${antiguedad(ahoraMillis - desdeMillis)}"

    /**
     * 🔴 Decisión del founder (23-sep, «corrige y avisa»): lo que ve el cajero cuando la terminal se enteró TARDE de que un
     * cobro que dio por no cobrado SÍ pasó (el lector dijo «cancelado» y el banco aprobó un segundo después — Testarudo, 16-sep).
     * Nombra importe y antigüedad para que lo reconozca, y dice lo único que importa: no volver a cobrarlo. Va con «Entendido».
     */
    fun cobroQueSiPaso(totalCentavos: Long, desdeMillis: Long, ahoraMillis: Long): String =
        "El cobro de ${pesos(totalCentavos)} (${antiguedad(ahoraMillis - desdeMillis)}) SÍ pasó: Avoqado ya lo registró, " +
            "así que no lo vuelvas a cobrar."

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
     *  - **el APARATO tiene otro cobro EN CURSO**: se espera a que termine, o se cobra en otra terminal;
     *  - **no se pudo guardar** — ni siquiera se llegó a la cerca: se reintenta.
     *
     * @param apartaLaVenta `"$120.00, hace 3 min"` de la fila que cerca ESTA venta, o null.
     * @param apartaElAparato lo mismo para la fila que aparta la terminal, o null.
     */
    fun barreraDeLaLibreta(
        apartaLaVenta: String?, apartaElAparato: String?, laVentaYaCobrada: Boolean = false,
        /**
         * F1-textos (catálogo D5, fila 17 ⚙): la fila que cerca la venta es la duda de un cobro del POS que el techo suelta sola
         * (`PaymentAttemptLedger.seSueltaSolaAlTecho`); con dinero en juego llega false y va la frase general.
         */
        laVentaLaApartaUnCobroDelPos: Boolean = false,
    ): String = when {
        // Decisión del founder (23-sep): un cobro que SÍ pasó se resuelve con el «Entendido» del aviso, no en otra terminal.
        apartaLaVenta != null && laVentaYaCobrada ->
            "Esta venta ya se cobró ($apartaLaVenta): Avoqado lo registró. NO se inició otro cobro. " +
                "Toca «Entendido» en el aviso de arriba."
        // La venta primero: es la más específica y la que de verdad puede cobrarse dos veces.
        // F1 (17-nov, K19c): otra tarjeta de esta cuenta espera al gerente; el cajero no decide. F1-textos (catálogo D5, fila 17 ⚙):
        // si la que cerca es un cobro del POS, se suelta sola al techo, y se dice.
        apartaLaVenta != null && laVentaLaApartaUnCobroDelPos ->
            "${CUENTA_EN_DUDA}una tarjeta por confirmar ($apartaLaVenta). No se inició otro cobro. " +
                "Se libera sola a los ${com.jaac.avoqado_tpv.features.payment.data.ledger.MINUTOS_DEL_TECHO} o con el gerente."
        apartaLaVenta != null ->
            "${CUENTA_EN_DUDA}un cobro por confirmar ($apartaLaVenta). No se inició otro cobro. Avísale al gerente."
        // El aparato ya sólo lo aparta un cobro CORRIENDO en este proceso (founder, 25-sep), nunca uno «por reconocer».
        apartaElAparato != null ->
            "Hay otro cobro en curso en esta terminal ($apartaElAparato). NO se inició este cobro. " +
                "Espera a que termine; si no ves ningún cobro en pantalla, cierra y vuelve a abrir la app."
        // 🔴 Nada que nombrar NO es «no hay cerca»: también es «no se pudo leer la libreta». Por eso el
        // texto no afirma que la venta esté libre — sólo dice lo único que consta: no se cobró.
        else -> "No se pudo guardar el intento en esta terminal. NO se cobró. Vuelve a intentarlo."
    }

    private const val CUENTA_EN_DUDA = "Esta cuenta tiene "

    /**
     * F1-textos (fila 17, Codex fila17 #1 y #3): ¿la barrera frenó por una duda de la cuenta (las dos frases de la fila 17)? Sólo esas
     * van arriba de la pantalla de la duda: «Vuelve a intentarlo» y «Toca «Entendido»…» no caben junto a «No la repitas» y [Seguir].
     */
    fun esLaCuentaEnDuda(motivo: String): Boolean = motivo.startsWith(CUENTA_EN_DUDA)

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
            // D20: «Por revisar» nombra dudas de días; «hace 50 h» no se lee.
            minutos < 24 * 60 -> "hace ${minutos / 60} h"
            else -> "hace ${minutos / (24 * 60)} d"
        }
    }
}
