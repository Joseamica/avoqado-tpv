package com.jaac.avoqado_tpv.features.onlineorders.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.features.onlineorders.data.FoliosRepository
import com.jaac.avoqado_tpv.features.onlineorders.data.PedidoYaCambio
import com.jaac.avoqado_tpv.features.onlineorders.data.VigiaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.domain.EstadoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.PaginaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.TarjetaPorAbrir
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.math.RoundingMode
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Textos de «Pedidos por pagar» en la TPV: mockups v2 §2 y reglas de UX (`ux-critica-v2.md`), que ganan sobre el plan. */
object TextosPorPagar {
    const val TITULO = "Pedidos por pagar"
    fun tituloFolio(folio: String) = "Folio $folio"
    /** Mockup 2d: bajo el monto de «Método de pago» al cobrar un folio. */
    fun folioDelCobro(folio: String, nombre: String?, pagadoConSaldo: java.math.BigDecimal? = null, yaPago: java.math.BigDecimal? = null) =
        "Folio $folio" + (nombre?.trim()?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") + abonoDelCobro(pagadoConSaldo, yaPago)

    /**
     * Parte 2 (2e·3, Codex r5 F2): lo ya pagado, para que la cajera no lo vuelva a cobrar. El número es el del SERVIDOR ([yaPago]); «con saldo»
     * sólo si esta pantalla lo acaba de cobrar con saldo y es todo el abono. Sin el número del SERVIDOR, lo cobrado con saldo aquí.
     */
    private fun abonoDelCobro(pagadoConSaldo: java.math.BigDecimal?, yaPago: java.math.BigDecimal?): String {
        val saldo = pagadoConSaldo?.takeIf { it.signum() > 0 }
        val abono = yaPago?.takeIf { it.signum() > 0 }
        return when {
            saldo != null && (abono == null || abono.compareTo(saldo) == 0) -> " · ya pagó ${CurrencyFormatter.format(saldo)} con saldo"
            abono != null && saldo != null -> " · ya pagó ${CurrencyFormatter.format(abono)} (${CurrencyFormatter.format(saldo)} con saldo)"
            abono != null -> " · ya pagó ${CurrencyFormatter.format(abono)}"
            else -> ""
        }
    }
    const val BUSCAR = "Folio o nombre"
    const val POR_PAGAR = "Por pagar"
    const val POR_ENTREGAR = "Por entregar"
    const val VACIO = "No hay pedidos por pagar"
    const val VACIO_AYUDA = "Cuando alguien pida por el QR, aparece aquí con su folio y su nombre."
    const val NADA_POR_ENTREGAR = "Nada por entregar."
    fun resultados(n: Int) = if (n == 1) "1 resultado" else "$n resultados"
    fun sinResultados(texto: String) = "No encontramos «$texto»"
    const val SIN_RESULTADOS_AYUDA = "Si ya venció o ya se pagó, revisa «Por entregar». Si venció, cóbralo en «Cobrar» como venta normal."
    const val PUEDE_HABER_MAS = "Puede haber más: toca «Cargar más» para seguir buscando."
    const val SIN_CONEXION_TITULO = "Pedidos por pagar · Sin conexión:"
    /** D661 · W1: sin red no se ven los pagados en línea; cobrarlos en caja haría un segundo cobro cuando otra caja los abra. */
    const val SIN_CONEXION_CUERPO = "los pedidos nuevos no se ven. Cobra en \"Cobrar\" como venta normal. " +
        "Si ya pagó en línea, no lo cobres: ábrelo cuando vuelva la conexión."
    const val ENTREGA_SIN_RED = "«Listo» y «Entregado» vuelven con la conexión"
    const val FOLIO_SIN_RED = "Sin conexión: cóbralo como venta normal en «Cobrar»."
    const val SIN_ACTUALIZAR = "Sin actualizar: la lista puede no estar al día."
    const val LEYENDO = "Leyendo folios…"
    const val NO_SE_PUDO_CARGAR = "No se pudo cargar."
    const val NO_SE_PUDIERON_CARGAR_MAS = "No se pudieron cargar más."
    const val REINTENTAR = "Reintentar"
    const val CARGAR_MAS = "Cargar más"
    const val LISTO = "Listo"
    const val ENTREGADO = "Entregado"
    fun productos(n: Int) = if (n == 1) "1 producto" else "$n productos"
    fun venceEn(minutos: Long) = "Vence en $minutos min"
    fun venceALas(hora: String) = "Vence a las $hora"
    fun vencioALas(hora: String) = "Venció a las $hora"
    fun pagadoALas(hora: String) = "Pagado a las $hora"
    fun pidioALas(hora: String) = "Pidió a las $hora"
    fun faltaDe(falta: String, total: String) = "Falta $falta de $total"
    fun yaPago(monto: String) = "Ya pagó $monto"
    const val NOTA = "Nota:"
    const val TOTAL = "Total"
    fun cobrar(monto: String) = "Cobrar $monto"
    const val CANCELAR_FOLIO = "Cancelar folio"
    fun cancelarTitulo(folio: String) = "¿Cancelar el folio $folio?"
    fun cancelarCuerpo(nombre: String?) =
        (nombre?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it lo verá" } ?: "Lo verá") +
            " cancelado en su celular. Úsalo para un pedido falso o si ya no lo quiere."
    const val NO = "No"
    const val SI_CANCELAR = "Sí, cancelar"
    const val ENTENDIDO = "Entendido"
    fun entregadoA(folio: String, nombre: String?) = conNombre(folio, "entregado a", "entregado", nombre)
    fun listoPara(folio: String, nombre: String?) = conNombre(folio, "listo para", "listo", nombre)
    fun cancelado(folio: String) = "Folio $folio cancelado"
    private fun conNombre(folio: String, con: String, sin: String, nombre: String?) =
        nombre?.trim()?.takeIf { it.isNotEmpty() }?.let { "$folio $con $it" } ?: "$folio $sin"

    // Diálogos (2c·4, 2c·5 y los fallos de las acciones).
    val VENCIDO = AvisoDeFolio("Este pedido ya venció", "Cóbralo como venta normal en «Cobrar».")
    fun yaPagado(hora: String?) = AvisoDeFolio(
        "Este folio ya se pagó",
        "Lo cobró otra caja${hora?.let { " a las $it" }.orEmpty()}. Ya está en «Por entregar».",
        boton = "Ver en «Por entregar»",
        irAPorEntregar = true,
    )
    val YA_NO_ESTA = AvisoDeFolio(
        "Este folio ya no está por pagar",
        "Se canceló o ya se entregó. Si la clienta no ha pagado, cóbralo como venta normal en «Cobrar».",
    )
    fun noSePudoRevisar(sinRed: Boolean) =
        AvisoDeFolio("No se pudo revisar el folio", if (sinRed) FOLIO_SIN_RED else "Inténtalo de nuevo.")
    val CON_COBRO = AvisoDeFolio(
        "No se puede cancelar",
        "Este folio ya tiene un cobro, o uno en curso en la terminal. Si ya se cobró, primero se reembolsa.",
    )
    /** R78: este aparato ya lo cobró y el registro sigue en su cola; el SERVIDOR aún lo ve «por pagar». */
    val COBRO_SUBIENDOSE = AvisoDeFolio("Hay un cobro de este folio subiéndose", "Espera a que suba.")
    /** R83: este aparato lo cobró, el SERVIDOR rechazó el registro para siempre y nadie lo ha revisado (dinero en la caja). */
    val COBRO_SIN_REGISTRAR = AvisoDeFolio(
        "Este folio tiene un cobro sin registrar en esta terminal",
        // Inicio › «Turnos de caja» › «Cerrar caja»: con ventas sin revisar no cierra; enseña «La caja espera a las ventas guardadas».
        "Pídele a un gerente que lo revise en «Turnos de caja» › «Cerrar caja» antes de volver a cobrarlo.",
    )
    /** R83: esta terminal está cobrando o registrando una tarjeta (la tarjeta no tiene fila en la cola hasta que su registro falla). */
    val COBRO_EN_CURSO = AvisoDeFolio("Hay un cobro en curso en esta terminal", "Espera a que termine.")
    /** R89: la libreta tiene un cobro con tarjeta de esta cuenta aprobado y sin registrar (p. ej. el registro falló tras salir del éxito).
     *  R91: en el aparato no hay un camino para que un gerente lo «revise», así que el aviso no manda a nadie. */
    val COBRO_CON_TARJETA_SIN_REGISTRAR = AvisoDeFolio(
        "La tarjeta de este folio ya se aprobó",
        "La terminal lo está registrando. No lo cobres otra vez.",
    )
    // Parte 2 (P2): saldo o tarjeta de regalo.
    const val SALDO_O_TARJETA = "Saldo o tarjeta de regalo"
    const val NECESITA_CONEXION = "Necesita conexión"
    const val COMPROBANDO_SALDO = "Comprobando el cobro con saldo…"
    val SALDO_COMPROBANDO = AvisoDeFolio(COMPROBANDO_SALDO, "Espera a que termine.")
    val SALDO_SIN_CONFIRMAR = AvisoDeFolio(
        "No pudimos confirmar el cobro con saldo",
        "Revisa la conexión. No cobres este folio otra vez: al tocar «Cobrar» se vuelve a comprobar.",
    )
    fun saldoNoSalio(texto: String) = AvisoDeFolio("No se cobró con saldo", texto)
    fun pagadoConSaldo(folio: String) = AvisoDeFolio(
        "Pagado con saldo",
        "El folio $folio ya está pagado. Está en «Por entregar».",
        boton = "Ver en «Por entregar»",
        irAPorEntregar = true,
    )
    fun saldoConfirmado(falta: String) = AvisoDeFolio("Cobro con saldo confirmado", "Faltan $falta. Toca «Cobrar» para cobrar lo que falta.")
    /** S14: la línea de la cuenta de la clienta (como el MCP: «Con cuenta: Ana L. · Premio −$45.00»). */
    fun conCuenta(nombre: String) = "Con cuenta: $nombre"
    fun beneficio(nombre: String, monto: java.math.BigDecimal) = "$nombre −${CurrencyFormatter.format(monto)}"
    fun yMas(n: Int) = "y $n más"
    /** Full-testing H2: sin red, el folio con un cobro con saldo sin confirmar dice lo mismo que el diálogo, nunca «cóbralo como venta normal». */
    const val SALDO_EN_DUDA_SIN_RED = "Hay un cobro con saldo sin confirmar. No cobres este folio otra vez: se comprueba al volver la conexión."
    /** Full-testing H2: la marca de la lista. */
    const val SALDO_SIN_CONFIRMAR_MARCA = "Cobro con saldo sin confirmar"
    /** D12: el almacén cifrado de los cobros con saldo no se dejó leer (no se sabe si el folio tiene un cobro en duda). */
    val SALDO_ILEGIBLE = AvisoDeFolio(
        com.jaac.avoqado_tpv.features.giftcards.data.TextosDeSaldo.ILEGIBLE_TITULO,
        com.jaac.avoqado_tpv.features.giftcards.data.TextosDeSaldo.ILEGIBLE,
    )
    const val SALDO_ILEGIBLE_MARCA = "Cobro con saldo sin revisar"
    /** D12 r4: «Cancelar folio» se revisa otra vez justo antes de mandar (el diálogo pudo abrirse antes de la duda). */
    val SALDO_NO_CANCELA = AvisoDeFolio(
        "No se canceló el folio",
        "Este folio puede tener un cobro con saldo sin confirmar. Revisa en el panel si ya está pagado antes de cancelarlo.",
    )
    /** D12: sin red, un folio de una terminal cuyo almacén de saldo no se lee. */
    const val SALDO_ILEGIBLE_SIN_RED = "No se pudo revisar si este folio tiene un cobro con saldo sin confirmar. No lo cobres aquí: " +
        "revisa en el panel si ya está pagado."
    /** Full-testing H5 (mockup 2e·3): arriba del monto, cuando ya hubo un abono. */
    const val FALTA = "Falta"
    val YA_CAMBIO = AvisoDeFolio("Este folio ya cambió", "Otra caja, el panel o la clienta lo cambió. La lista se está actualizando.")
    val SIN_PAGAR = AvisoDeFolio("Este folio no está pagado", "Cóbralo antes de entregarlo.")

    // D661: pagados con tarjeta en línea en «No, entran solos». Abrir = cobrar (lo cobra el SERVIDOR). Los mismos textos del POS.
    const val SECCION_PAGADOS_EN_LINEA = "Pagados en línea · ábrelo para cobrar"
    const val PAGADO_EN_LINEA_AYUDA = "Pagó con tarjeta en línea: se cobra al abrirlo y sale su comanda."
    const val ABRIR_Y_COBRAR = "Abrir y cobrar"
    const val ABRIENDO = "Abriendo…"
    /** Abrir es cobrar una tarjeta: necesita conexión. Se dice en el botón, antes de tocarlo. */
    const val ABRIR_NECESITA_CONEXION = "Abrir y cobrar necesita conexión"
    const val COBRANDO_EN_OTRA_CAJA = "Cobrándose en otra caja…"
    const val PAGADOS_EN_LINEA_SIN_PERMISO = "Pide a quien pueda atender pedidos que lo abra."
    /** Dónde quedó tras abrirlo (aquí u otra caja ya lo había abierto). */
    fun abiertoEnPorEntregar(folio: String) = "El pedido $folio ya está en «Por entregar»."
    /** Otra caja ya lo abrió y ya se cerró (entregado, «No lo recogió» o devuelto). */
    fun pedidoYaCerrado(folio: String) = "El pedido $folio ya está cerrado: no hay nada que abrir."
    const val YA_NO_DISPONIBLE = "Ese pedido ya no está disponible"
    /** Sin red al abrir: no se sabe si llegó; el SERVIDOR nunca lo cobra dos veces. */
    const val ABRIR_SIN_CONEXION = "Sin conexión: no se pudo confirmar. Al volver la conexión la lista dice dónde quedó; nunca se cobra dos veces."
    const val CONFIRMANDO_EL_PAGO = "Confirmando el pago con el banco. Espera unos segundos."
    fun yaAbierto(folio: String) = AvisoDeFolio(
        "Este pedido ya se abrió",
        abiertoEnPorEntregar(folio),
        boton = "Ver en «Por entregar»",
        irAPorEntregar = true,
    )
    fun yaCerrado(folio: String) = AvisoDeFolio("Este pedido ya se cerró", pedidoYaCerrado(folio))
    val YA_NO_DISPONIBLE_AVISO = AvisoDeFolio(YA_NO_DISPONIBLE, "Se liberó sin cobrarse o venció. No se cobró nada.")
    fun noSeAbrio(texto: String) = AvisoDeFolio("No se abrió el pedido", texto)
    /** Un 5xx no dice si se cobró: la relectura lo dice y el SERVIDOR nunca lo cobra dos veces. */
    val ABRIR_SIN_CONFIRMAR = AvisoDeFolio(
        "No se pudo confirmar",
        "La lista se está actualizando y dirá dónde quedó el pedido; nunca se cobra dos veces.",
    )
    /** Ruling 19 / 68: la banda de la app y la línea del recuadro mientras suena. */
    const val PAGADO_EN_LINEA_POR_ABRIR = "Pedido pagado en línea · ábrelo para cobrar"
    fun avisoDePagadosEnLinea(n: Int) = if (n > 1) "$PAGADO_EN_LINEA_POR_ABRIR ($n por abrir)" else PAGADO_EN_LINEA_POR_ABRIR
    fun pagadosEnLinea(n: Int) = if (n == 1) "1 pagado en línea" else "$n pagados en línea"
    const val VER = "Ver"
    /** Ruling 67: sin turno abierto (o sin entrada registrada), el recuadro y la banda dicen por dónde se entra. */
    fun abreTuTurno(n: Int) = if (n > 1) "Abre tu turno para abrir los $n pedidos pagados" else "Abre tu turno para abrir el pedido pagado"
    fun registraTuEntrada(n: Int) =
        if (n > 1) "Registra tu entrada para abrir los $n pedidos pagados" else "Registra tu entrada para abrir el pedido pagado"
    /** Ruling 69: esta terminal abrió el pedido y su comanda no salió aquí (sin impresora o falló el papel). */
    fun comandaNoSalio(folio: String) = "La comanda del pedido $folio no salió en esta terminal: avísale a cocina"
    fun comandasNoSalieron(n: Int) = "$n comandas no salieron en esta terminal: avísale a cocina"
    /** Ruling 70: el pedido abierto ya no vino en la lista: se relee antes de volver a ofrecer «Listo» o «Entregado». */
    const val ACTUALIZANDO = "Actualizando…"
}

/** Un diálogo con un solo botón. [irAPorEntregar]: al cerrarlo se vuelve a la lista, sin búsqueda, para ver «Por entregar». */
data class AvisoDeFolio(
    val titulo: String,
    val cuerpo: String,
    val boton: String = TextosPorPagar.ENTENDIDO,
    val irAPorEntregar: Boolean = false,
)

/**
 * Lo que necesita la pantalla de cobro de siempre para cobrar un folio (llaves de `AppNavigation`): lo que falta (nunca el total),
 * la cuenta, el folio y [folio], que el cobro usa al confirmarse (P4). Siempre pago completo: Nexgo no cobra en partes.
 */
data class AperturaDeCobro(
    val initialAmount: String,
    val orderId: String,
    val orderNumber: String,
    val splitType: String = SplitType.FULLPAYMENT.value,
    val folio: ComandaDeFolio,
) : java.io.Serializable

data class PorPagarUi(
    val folios: EstadoDeFolios = EstadoDeFolios(),
    val busqueda: String = "",
    /** La búsqueda tiene 3 caracteres o más: la lista es de resultados. */
    val buscando: Boolean = false,
    /** Lo que se pinta: filtrado por la búsqueda y sin lo que esta caja acaba de sacar. */
    val porPagar: List<Folio> = emptyList(),
    val porEntregar: List<Folio> = emptyList(),
    val numeroPorPagar: Int = 0,
    val numeroPorEntregar: Int = 0,
    /** `orders:update`: «Listo» y «Entregado». */
    val puedeEntregar: Boolean = false,
    /** `orders:cancel-unpaid`: «Cancelar folio». */
    val puedeCancelar: Boolean = false,
    /** El folio abierto (su versión más nueva de la lista, o la que se abrió si ya no está). */
    val seleccionado: Folio? = null,
    /** Folios con una acción en curso (releer, cancelar, Listo, Entregado): un segundo toque no hace nada. */
    val ocupados: Set<String> = emptySet(),
    val aviso: AvisoDeFolio? = null,
    val toast: String? = null,
    /** Parte 2: `giftCards:redeem`, «Saldo o tarjeta de regalo». */
    val puedeUsarSaldo: Boolean = false,
    /** Folios cuyo cobro con saldo guardado en el aparato se está comprobando con el SERVIDOR. */
    val comprobandoSaldo: Set<String> = emptySet(),
    /** Folios con un cobro con saldo que no se pudo confirmar: no se cobran ni se cancelan hasta comprobarlo. */
    val saldoSinConfirmar: Set<String> = emptySet(),
    /** D12: el almacén cifrado de los cobros con saldo no se dejó leer: CUALQUIER folio puede tener un cobro en duda. */
    val saldoIlegible: Boolean = false,
    /** D661: los pagados en línea por abrir (sin los que esta caja acaba de abrir). */
    val tarjetasPorAbrir: List<TarjetaPorAbrir> = emptyList(),
    /** D661 (R2-6): el SERVIDOR dijo «confirmando el pago»: esperan una lectura buena posterior antes de volver a ofrecerse. */
    val cobrandoEnOtraCaja: Set<String> = emptySet(),
    /** D661 (Ruling 70): el pedido abierto no vino en la última lista buena: se relee y, mientras, sin «Listo» ni «Entregado». */
    val seleccionadoActualizando: Boolean = false,
) {
    /** D12: el folio no se cobra ni se cancela aquí: tiene un cobro con saldo sin confirmar, o no se sabe porque el almacén no se lee. */
    fun saldoEnDuda(orderId: String): Boolean = orderId in saldoSinConfirmar || saldoIlegible
}

/**
 * «Pedidos por pagar» (P3): la lista viene del vigía (P1); aquí se busca, se abre un folio, se relee antes de cobrar y se
 * cancela, se marca «Listo» o «Entregado». Sin red no se cobra ni se toca un folio: la lista sólo se lee.
 */
@HiltViewModel
class PorPagarViewModel @Inject constructor(
    private val vigia: VigiaDeFolios,
    private val folios: FoliosRepository,
    private val permisos: PermissionsRepository,
    private val storage: SecureStorage,
    // R78/R83: los cobros de ESTE aparato que el SERVIDOR todavía no tiene (en cola, o rechazados sin revisar).
    private val cola: PendingPaymentDao,
    // R83: la tarjeta que esta terminal está cobrando o registrando ahora (todavía sin fila en la cola).
    private val cobroEnCurso: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder,
    // R89: la libreta de cobros con tarjeta (PAX y Nexgo): lo aprobado que el SERVIDOR todavía no registra.
    private val libreta: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger,
    // Parte 2 (P2): el cobro con saldo guardado en el aparato, y lo que se hace cuando un folio queda pagado.
    private val saldo: com.jaac.avoqado_tpv.features.giftcards.data.CobroConSaldoRepository,
    private val despuesDeCobrar: com.jaac.avoqado_tpv.features.onlineorders.data.DespuesDeCobrarFolio,
) : ViewModel() {

    private data class Local(
        val busqueda: String = "",
        val puedeEntregar: Boolean = false,
        val puedeCancelar: Boolean = false,
        val seleccionado: Folio? = null,
        val ocupados: Set<String> = emptySet(),
        /** Lo que esta caja canceló, marcó «Listo» o «Entregado» (orderId →), escondido mientras el SERVIDOR lo traiga igual (R94). */
        val quitados: Map<String, Quitado> = emptyMap(),
        val aviso: AvisoDeFolio? = null,
        val toast: String? = null,
        val puedeUsarSaldo: Boolean = false,
        val comprobandoSaldo: Set<String> = emptySet(),
        val saldoSinConfirmar: Set<String> = emptySet(),
        val saldoIlegible: Boolean = false,
        /** D661: pagados en línea que esta caja ya abrió: no se ofrecen otra vez mientras el SERVIDOR los siga trayendo por abrir. */
        val abiertosAqui: Set<String> = emptySet(),
        /** D661 (R2-6): «confirmando el pago» (409) → `actualizadoEnMs` de la lista que se veía; espera una lectura buena POSTERIOR. */
        val confirmando: Map<String, Long?> = emptyMap(),
        /** D661 (Ruling 70): lo que contestó la relectura del pedido abierto que la lista dejó de traer, y de qué lista salió. */
        val releido: Releido? = null,
        /** D661 (Ruling 70): la relectura en vuelo (orderId y la lista que la pidió): una por lista. */
        val releyendo: Pair<String, Long?>? = null,
    )

    private data class Releido(val deLaLista: Long?, val folio: Folio)

    /**
     * R92 M2 / R94 / R96 / R97: un folio que esta caja sacó, con la [seccion], el [estado] y lo que faltaba ([faltaCentavos]) ANTES
     * de mandar la acción. [marca] = `actualizadoEnMs` de la lista que se veía al confirmarse (su conteo todavía lo cuenta).
     */
    private data class Quitado(val marca: Long?, val seccion: SeccionDeFolios, val estado: String, val faltaCentavos: Long)

    private val local = MutableStateFlow(Local())

    /**
     * Parte 2 (Codex r1 #4): lo cobrado con saldo de cada folio en esta pantalla (centavos), venga de «Saldo o tarjeta de regalo» o de una
     * comprobación que lo recuperó. El cobro de lo que falta lo dice bajo el monto («ya pagó $X con saldo»).
     */
    private val pagadoConSaldo = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** R97: el número de esta pantalla en el vigía; al limpiarse sólo suelta lo suyo. */
    private val lista = vigia.abrirLista()

    val ui: StateFlow<PorPagarUi> = combine(vigia.estado, local, ::componer)
        .stateIn(viewModelScope, SharingStarted.Eagerly, componer(vigia.estado.value, local.value))

    /** La hora que ve la cajera es la del negocio. */
    val zona: ZoneId get() = VenueTimeZone.get(storage)

    /**
     * Full-testing H2: los folios con un cobro con saldo guardado en el aparato se ven «sin confirmar» desde que se abre la lista, también
     * sin red y sin haberlos abierto (la app pudo morir con la solicitud guardada).
     */
    private fun leerSaldosPendientes() {
        // D12 r3: el lector nunca lanza; null = el almacén no se deja leer y CUALQUIER folio puede tener un cobro en duda.
        val pendientes = saldo.pendientes()?.map { it.orderId }
        local.update {
            if (pendientes == null) it.copy(saldoIlegible = true)
            else it.copy(saldoIlegible = false, saldoSinConfirmar = it.saldoSinConfirmar + pendientes)
        }
    }

    init {
        leerSaldosPendientes()
        // R94: un folio que ya no viene en ninguna lista se olvida (el SERVIDOR ya confirmó lo que se hizo con él).
        viewModelScope.launch {
            vigia.estado.collect { e ->
                val vivos = (e.porPagar + e.porEntregar).mapTo(HashSet()) { it.orderId }
                val porAbrir = e.tarjetasPorAbrir.mapTo(HashSet()) { it.orderId }
                // R2-6: sólo una lectura BUENA que salió después del «confirmando» dice dónde quedó el pedido (sin red o tras un 4xx, no).
                val lecturaBuena = !e.sinConexion && !e.desactualizado
                local.update { l ->
                    l.copy(
                        quitados = if (l.quitados.keys.all { it in vivos }) l.quitados else l.quitados.filterKeys { it in vivos },
                        // D661: lo abierto aquí se olvida cuando el SERVIDOR ya no lo trae por abrir.
                        abiertosAqui = if (l.abiertosAqui.all { it in porAbrir }) l.abiertosAqui else l.abiertosAqui.filterTo(HashSet()) { it in porAbrir },
                        confirmando = if (lecturaBuena) l.confirmando.filterValues { it == e.actualizadoEnMs } else l.confirmando,
                    )
                }
                // Ruling 70: el pedido abierto ya no vino en esta lista buena (devuelto, cerrado u otra caja lo sacó): se relee para ver cómo
                // quedó. Mientras, el detalle no ofrece «Listo» ni «Entregado» (ver `componer`).
                val l = local.value
                val abierto = l.seleccionado?.orderId
                if (abierto != null && lecturaBuena && pideRelectura(e, l, abierto)) releerAbierto(abierto, e.actualizadoEnMs)
            }
        }
        viewModelScope.launch {
            val entregar = permisos.hasPermission("orders:update")
            val cancelar = permisos.hasPermission("orders:cancel-unpaid")
            // Parte 2: el mismo permiso exacto que el SERVIDOR pide para cobrar con saldo.
            val usarSaldo = permisos.hasPermission("giftCards:redeem")
            local.update { it.copy(puedeEntregar = entregar, puedeCancelar = cancelar, puedeUsarSaldo = usarSaldo) }
        }
    }

    /**
     * Cada vez que la pantalla vuelve a verse (también al regresar de un cobro): el vigía pregunta ya. Así un folio recién
     * cobrado aquí no sigue enseñando «Cobrar» hasta la siguiente vuelta.
     */
    fun pantallaVisible() {
        vigia.sondearYa()
        leerSaldosPendientes()
        // Parte 2: si se volvió del saldo con el cobro en duda, el folio abierto lo comprueba otra vez.
        local.value.seleccionado?.let { revisarSaldo(it) }
    }

    fun buscar(texto: String) = local.update { it.copy(busqueda = texto) }

    /** Parte 2 (P2): un folio con un cobro con saldo guardado en el aparato lo comprueba ANTES de dejar cobrarlo. */
    fun abrir(folio: Folio) {
        local.update { it.copy(seleccionado = folio) }
        revisarSaldo(folio)
    }
    fun cerrarFolio() = local.update { it.copy(seleccionado = null) }
    fun toastMostrado() = local.update { it.copy(toast = null) }
    fun reintentar() = vigia.sondearYa()

    /** Volvió de un cobro que terminó: a la lista, y que el vigía pregunte ya (el folio pasa a «Por entregar»). */
    fun cobroTerminado() {
        cerrarFolio()
        vigia.sondearYa()
    }

    fun cerrarAviso() = local.update {
        if (it.aviso?.irAPorEntregar == true) it.copy(aviso = null, seleccionado = null, busqueda = "") else it.copy(aviso = null)
    }

    fun cargarMas(seccion: SeccionDeFolios) {
        viewModelScope.launch { vigia.cargarMas(seccion) }
    }

    /** R92 M3: la lista se cierra: el vigía deja de releer las páginas extra (la página 1 sigue para el recuadro). */
    override fun onCleared() {
        vigia.soltarExtras(lista)
        super.onCleared()
    }

    /**
     * Relee el folio en el SERVIDOR antes de abrir el cobro (diseño §5): sólo si sigue por pagar devuelve la apertura, con lo
     * que falta AHORA, y llama [abrir] con ella. Vencido, ya pagado, cancelado o sin respuesta: deja su aviso y devuelve null
     * (no empieza ningún cobro). El folio sigue ocupado («Cobrar» en espera) hasta que [abrir] termina (el lector listo).
     */
    suspend fun cobrar(folio: Folio, abrir: suspend (AperturaDeCobro) -> Unit = {}): AperturaDeCobro? = cobrarFolio(folio, abrir)

    /**
     * Parte 2 (mockup 2e·3): el saldo no alcanzó. Abre el cobro de siempre del folio abierto por lo que falta AHORA (se relee, como
     * «Cobrar»), con «ya pagó $X con saldo» bajo el monto.
     */
    suspend fun cobrarResto(pagadoConSaldoCents: Long, abrir: suspend (AperturaDeCobro) -> Unit = {}): AperturaDeCobro? {
        val folio = ui.value.seleccionado ?: return null
        sumarPagadoConSaldo(folio.orderId, pagadoConSaldoCents)
        return cobrarFolio(folio, abrir)
    }

    private fun sumarPagadoConSaldo(orderId: String, cents: Long) {
        if (cents > 0) pagadoConSaldo.merge(orderId, cents, Long::plus)
    }

    private suspend fun cobrarFolio(folio: Folio, abrir: suspend (AperturaDeCobro) -> Unit): AperturaDeCobro? {
        if (vigia.estado.value.sinConexion) return null
        val venueId = storage.getVenueId()?.takeIf { it.isNotBlank() } ?: return null
        if (!ocupar(folio.orderId)) return null
        try {
            // Parte 2: un cobro con saldo de este folio guardado en el aparato se comprueba primero (misma llave); con cualquier
            // desenlace se avisa y NO se sigue a cobrar en el mismo toque (lo que falta cambió, o no se sabe si ya se cobró).
            comprobarSaldo(folio)?.let { return avisar(it) }
            // R78/R83/R89: el SERVIDOR sólo sabe del cobro cuando sube; hasta entonces la relectura lo daría «por pagar» otra vez.
            dineroSinConfirmar(venueId, folio.orderId)?.let { return avisar(it) }
            return releer(venueId, folio, pagadoConSaldo[folio.orderId])?.also { abrir(it) }
        } finally {
            local.update { it.copy(ocupados = it.ocupados - folio.orderId) }
        }
    }

    private fun revisarSaldo(folio: Folio) {
        if (saldo.saldoDe(folio.orderId) == com.jaac.avoqado_tpv.features.giftcards.data.SaldoGuardado.Ninguno) return
        viewModelScope.launch { comprobarSaldo(folio)?.let { aviso -> local.update { it.copy(aviso = aviso) } } }
    }

    /**
     * Parte 2 (P1/P2): si el folio tiene un cobro con saldo guardado en el aparato, se le pregunta al SERVIDOR por SU llave (y, si no
     * aparece, se reenvía el MISMO cobro: [com.jaac.avoqado_tpv.features.giftcards.data.CobroConSaldoRepository.resolver]). null = no había
     * ninguno; si no, el aviso de lo que pasó. Mientras se comprueba, el folio dice «Comprobando el cobro con saldo…».
     */
    private suspend fun comprobarSaldo(folio: Folio): AvisoDeFolio? {
        if (saldo.saldoDe(folio.orderId) == com.jaac.avoqado_tpv.features.giftcards.data.SaldoGuardado.Ninguno) return null
        var libre = false
        local.update {
            libre = folio.orderId !in it.comprobandoSaldo
            if (libre) it.copy(comprobandoSaldo = it.comprobandoSaldo + folio.orderId) else it
        }
        if (!libre) return TextosPorPagar.SALDO_COMPROBANDO
        val d = try {
            saldo.resolver(folio.orderId)
        } finally {
            local.update { it.copy(comprobandoSaldo = it.comprobandoSaldo - folio.orderId) }
        }
        val enDuda = d == com.jaac.avoqado_tpv.features.giftcards.data.DesenlaceDeSaldo.Incierto
        local.update {
            it.copy(saldoSinConfirmar = if (enDuda) it.saldoSinConfirmar + folio.orderId else it.saldoSinConfirmar - folio.orderId)
        }
        return when (d) {
            null -> null
            com.jaac.avoqado_tpv.features.giftcards.data.DesenlaceDeSaldo.Incierto -> TextosPorPagar.SALDO_SIN_CONFIRMAR
            com.jaac.avoqado_tpv.features.giftcards.data.DesenlaceDeSaldo.Ilegible -> {
                local.update { it.copy(saldoIlegible = true) }
                TextosPorPagar.SALDO_ILEGIBLE
            }
            is com.jaac.avoqado_tpv.features.giftcards.data.DesenlaceDeSaldo.NoSalio -> TextosPorPagar.saldoNoSalio(d.texto)
            is com.jaac.avoqado_tpv.features.giftcards.data.DesenlaceDeSaldo.Cobrado -> {
                vigia.sondearYa()
                sumarPagadoConSaldo(folio.orderId, d.montoCents)
                if (d.restanteCents == 0L) {
                    // El saldo pagó todo (la app murió antes de confirmarlo): se acepta y sale su comanda, una vez.
                    despuesDeCobrar.alConfirmarse(ComandaDeFolio(folio.orderId, folio.folio, folio.nombre, folio.nota, folio.productos))
                    TextosPorPagar.pagadoConSaldo(folio.folio)
                } else {
                    TextosPorPagar.saldoConfirmado(CurrencyFormatter.format(java.math.BigDecimal.valueOf(d.restanteCents, 2)))
                }
            }
        }
    }

    /**
     * R89: el ÚNICO lugar que responde «¿esta terminal tiene dinero de esta cuenta que el SERVIDOR todavía no confirma?» — si
     * sí, cobrar el folio otra vez sería cobrarle dos veces a la clienta. null = nada; si no, el aviso de la fuente que lo tiene:
     *  (b) un cobro con tarjeta en curso o registrándose en esta terminal ([PaymentStateHolder.isCharging]);
     *  (a) la cola de la terminal: rechazado sin revisar, o todavía subiendo (el mismo predicado que frena el cierre de caja);
     *  (c) la libreta (PAX y Nexgo): un cobro con tarjeta aprobado y sin registrar, aunque ya no tenga fila en la cola.
     * Falla CERRADO: si una fuente no se puede leer, tampoco se arriesga un segundo cobro.
     */
    private suspend fun dineroSinConfirmar(venueId: String, orderId: String): AvisoDeFolio? = try {
        when {
            cobroEnCurso.isCharging() -> TextosPorPagar.COBRO_EN_CURSO
            cola.hayCobroSinRegistrar(venueId, orderId) -> TextosPorPagar.COBRO_SIN_REGISTRAR
            cola.hayCobroSubiendose(venueId, orderId) -> TextosPorPagar.COBRO_SUBIENDOSE
            libreta.hayDineroEnJuegoDeLaVenta(orderId) -> TextosPorPagar.COBRO_CON_TARJETA_SIN_REGISTRAR
            else -> null
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        timber.log.Timber.e(e, "🧾 [Folios] No se pudo leer la cola de cobros de la terminal: no se abre el cobro")
        TextosPorPagar.noSePudoRevisar(sinRed = false)
    }

    fun cancelar(folio: Folio) {
        // D12 r4 (Codex r3 #2): el diálogo pudo abrirse antes de que el folio quedara en duda; se revisa otra vez justo antes de
        // mandar. Con un cobro con saldo en duda o el almacén ilegible no se cancela (el saldo pudo haberse cobrado).
        val guardado = saldo.saldoDe(folio.orderId)
        if (guardado != com.jaac.avoqado_tpv.features.giftcards.data.SaldoGuardado.Ninguno || ui.value.saldoEnDuda(folio.orderId)) {
            local.update {
                when (guardado) {
                    com.jaac.avoqado_tpv.features.giftcards.data.SaldoGuardado.Ilegible -> it.copy(saldoIlegible = true, aviso = TextosPorPagar.SALDO_NO_CANCELA)
                    is com.jaac.avoqado_tpv.features.giftcards.data.SaldoGuardado.EnDuda ->
                        it.copy(saldoSinConfirmar = it.saldoSinConfirmar + folio.orderId, aviso = TextosPorPagar.SALDO_NO_CANCELA)
                    com.jaac.avoqado_tpv.features.giftcards.data.SaldoGuardado.Ninguno -> it.copy(aviso = TextosPorPagar.SALDO_NO_CANCELA)
                }
            }
            return
        }
        accion(folio, Accion.CANCELAR)
    }
    fun listo(folio: Folio) = accion(folio, Accion.LISTO)
    fun entregado(folio: Folio) = accion(folio, Accion.ENTREGADO)

    /**
     * D661: «Abrir y cobrar» un pedido pagado con tarjeta en línea. Abrir ES cobrar: el SERVIDOR cobra la tarjeta apartada y lo pasa a
     * «Por entregar» (nunca el lector de esta terminal). Sale SÓLO de este toque: entrar, buscar o «Cargar más» nunca abren nada. Sólo
     * el que la lista de ahora trae por abrir, con red, con `orders:update` y si nadie más lo está cobrando; nunca se encola. Pase lo
     * que pase el vigía relee: el SERVIDOR dice dónde quedó, el timbre para y la comanda sale de esa lectura (no de esta respuesta).
     */
    fun abrirYCobrar(tarjeta: TarjetaPorAbrir) {
        val venueId = storage.getVenueId()?.takeIf { it.isNotBlank() } ?: return
        val pantalla = ui.value
        // La versión de la lista de AHORA (el «cobrando» pudo cambiar desde que se pintó el botón).
        val vigente = pantalla.tarjetasPorAbrir.firstOrNull { it.orderId == tarjeta.orderId } ?: return
        if (botonDeAbrir(vigente, pantalla)?.activo != true || !ocupar(vigente.orderId)) return
        viewModelScope.launch {
            // Ruling 69 (enmendado): ANTES de mandarlo. Una respuesta perdida, un 5xx o «confirmando» pueden ser una apertura real de ESTA
            // caja; el aviso sólo sale si después el SERVIDOR lista su comanda (la de un pedido que nadie abrió nunca viene).
            vigia.abiertoAqui(vigente.orderId, vigente.folio)
            val r = folios.aceptar(venueId, vigente.orderId, abrirPagadoEnLinea = true)
            // La lista que se veía al contestar: «confirmando» espera una lectura buena POSTERIOR a ésta.
            val marca = vigia.estado.value.actualizadoEnMs
            local.update { l ->
                val base = l.copy(ocupados = l.ocupados - vigente.orderId)
                r.fold(
                    onSuccess = {
                        base.copy(abiertosAqui = l.abiertosAqui + vigente.orderId, toast = TextosPorPagar.abiertoEnPorEntregar(vigente.folio))
                    },
                    onFailure = { e ->
                        if ((e as? BackendHttpException)?.errorCode == "ONLINE_ORDER_CONFIRMING_PAYMENT") {
                            base.copy(confirmando = l.confirmando + (vigente.orderId to marca), toast = TextosPorPagar.CONFIRMANDO_EL_PAGO)
                        } else {
                            base.copy(aviso = avisoDeAbrir(e, vigente))
                        }
                    },
                )
            }
            vigia.sondearYa()
        }
    }

    /** Dónde quedó el pedido según el SERVIDOR (409 `ORDER_STATE_CHANGED`), o por qué no se supo. */
    private fun avisoDeAbrir(e: Throwable, tarjeta: TarjetaPorAbrir): AvisoDeFolio = when {
        e is PedidoYaCambio -> when (e.estado) {
            "ACCEPTED", "READY" -> TextosPorPagar.yaAbierto(tarjeta.folio)
            "COMPLETED" -> TextosPorPagar.yaCerrado(tarjeta.folio)
            else -> TextosPorPagar.YA_NO_DISPONIBLE_AVISO
        }
        sinRed(e) -> AvisoDeFolio("Sin conexión", TextosPorPagar.ABRIR_SIN_CONEXION)
        // Un 4xx es una respuesta: se dice su texto. Un 5xx no dice si se cobró: el SERVIDOR nunca lo cobra dos veces y la relectura dice dónde quedó.
        e is BackendHttpException && e.statusCode in 400..499 -> TextosPorPagar.noSeAbrio(e.message)
        else -> TextosPorPagar.ABRIR_SIN_CONFIRMAR
    }

    private suspend fun releer(venueId: String, folio: Folio, pagadoConSaldoCents: Long? = null): AperturaDeCobro? {
        val primera = folios.listar(venueId, puedeCobrar = false).getOrElse { return avisar(TextosPorPagar.noSePudoRevisar(sinRed(it))) }
        val ahora = primera.horaDelServidor ?: Instant.now()
        val vivo = buscarEn(venueId, primera, SeccionDeFolios.POR_PAGAR, folio.orderId)
            .getOrElse { return avisar(TextosPorPagar.noSePudoRevisar(sinRed(it))) }
        if (vivo != null && vivo.falta.signum() > 0) {
            // Vencer es del trabajo del SERVIDOR (cada 30 s); la caja no empieza uno cuya hora ya pasó. Con abono nunca vence.
            if (!vivo.tieneAbono && vivo.venceEn?.isAfter(ahora) == false) return avisar(TextosPorPagar.VENCIDO)
            return AperturaDeCobro(
                initialAmount = vivo.falta.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                orderId = vivo.orderId,
                orderNumber = vivo.folio,
                folio = ComandaDeFolio(
                    vivo.orderId, vivo.folio, vivo.nombre, vivo.nota, vivo.productos,
                    pagadoConSaldo = pagadoConSaldoCents?.let { java.math.BigDecimal.valueOf(it, 2) },
                    yaPago = vivo.abono.takeIf { it.signum() > 0 },
                ),
            )
        }
        val pagado = vivo ?: buscarEn(venueId, primera, SeccionDeFolios.POR_ENTREGAR, folio.orderId)
            .getOrElse { return avisar(TextosPorPagar.noSePudoRevisar(sinRed(it))) }
        if (pagado != null) {
            vigia.sondearYa()
            return avisar(TextosPorPagar.yaPagado(pagado.pagadoEn?.let { hora(it, zona) }))
        }
        vigia.sondearYa()
        val vencio = !folio.tieneAbono && folio.venceEn?.isAfter(ahora) == false
        return avisar(if (vencio) TextosPorPagar.VENCIDO else TextosPorPagar.YA_NO_ESTA)
    }

    /** [orderId] en [seccion], desde la página ya leída y siguiendo sus cursores. */
    private suspend fun buscarEn(venueId: String, primera: PaginaDeFolios, seccion: SeccionDeFolios, orderId: String): Result<Folio?> {
        var pagina = primera
        // ponytail: tope de 20 páginas (2,000 folios); más allá se da por no encontrado y no se cobra.
        repeat(MAX_PAGINAS) {
            val lista = if (seccion == SeccionDeFolios.POR_PAGAR) pagina.porPagar else pagina.porEntregar
            lista.firstOrNull { it.orderId == orderId }?.let { return Result.success(it) }
            val cursor = pagina.siguientes[seccion] ?: return Result.success(null)
            pagina = folios.cargarMas(venueId, seccion, cursor).getOrElse { return Result.failure(it) }
        }
        return Result.success(null)
    }

    private enum class Accion(val sinRed: String, val fallo: String, val seccion: SeccionDeFolios) {
        CANCELAR("No se canceló el folio. Inténtalo con conexión.", "No se pudo cancelar el folio", SeccionDeFolios.POR_PAGAR),
        LISTO("No se marcó «Listo». Inténtalo con conexión.", "No se pudo marcar «Listo»", SeccionDeFolios.POR_ENTREGAR),
        ENTREGADO("No se marcó «Entregado». Inténtalo con conexión.", "No se pudo marcar «Entregado»", SeccionDeFolios.POR_ENTREGAR),
    }

    private fun accion(folio: Folio, accion: Accion) {
        val venueId = storage.getVenueId()?.takeIf { it.isNotBlank() } ?: return
        if (vigia.estado.value.sinConexion || !ocupar(folio.orderId)) return
        // R97: la huella es la del folio ANTES de mandar la acción (la versión más nueva de la lista ahora, o la que se tocó).
        // Nunca después de la respuesta: una vuelta que llega en medio ya lo trae cambiado (p. ej. READY) y lo escondería así.
        val antes = vigia.estado.value.let { e -> (e.porPagar + e.porEntregar).firstOrNull { it.orderId == folio.orderId } } ?: folio
        viewModelScope.launch {
            val r = when (accion) {
                Accion.CANCELAR -> folios.cancelar(venueId, folio.orderId)
                Accion.LISTO -> folios.listo(venueId, folio.orderId)
                Accion.ENTREGADO -> folios.entregado(venueId, folio.orderId)
            }
            // I1: la lista que se ve al confirmarse; mientras sea ésa, su conteo todavía cuenta el folio.
            val marca = vigia.estado.value.actualizadoEnMs
            local.update { l ->
                val base = l.copy(ocupados = l.ocupados - folio.orderId)
                r.fold(
                    onSuccess = {
                        base.copy(
                            // Cancelar sólo se ofrece por pagar; «Listo» y «Entregado», por entregar.
                            quitados = l.quitados + (folio.orderId to Quitado(marca, accion.seccion, antes.estado, antes.faltaCentavos)),
                            seleccionado = l.seleccionado?.takeIf { it.orderId != folio.orderId },
                            toast = when (accion) {
                                Accion.CANCELAR -> TextosPorPagar.cancelado(folio.folio)
                                Accion.LISTO -> TextosPorPagar.listoPara(folio.folio, folio.nombre)
                                Accion.ENTREGADO -> TextosPorPagar.entregadoA(folio.folio, folio.nombre)
                            },
                        )
                    },
                    onFailure = { e -> base.copy(aviso = avisoDeFallo(e, accion)) },
                )
            }
            vigia.sondearYa()
        }
    }

    /** Ruling 70: [orderId] no viene en la lista buena [e] y nadie lo releyó (ni lo está releyendo) para ESA lista. */
    private fun pideRelectura(e: EstadoDeFolios, l: Local, orderId: String): Boolean =
        e.actualizadoEnMs != null && (e.porPagar + e.porEntregar).none { it.orderId == orderId } &&
            l.releido?.let { it.folio.orderId == orderId && it.deLaLista == e.actualizadoEnMs } != true &&
            l.releyendo != (orderId to e.actualizadoEnMs)

    private fun releerAbierto(orderId: String, deLaLista: Long?) {
        val venueId = storage.getVenueId()?.takeIf { it.isNotBlank() } ?: return
        local.update { it.copy(releyendo = orderId to deLaLista) }
        viewModelScope.launch {
            val leido = folios.leerPedido(venueId, orderId).getOrNull()
            local.update { l ->
                val base = if (l.releyendo == orderId to deLaLista) l.copy(releyendo = null) else l
                // Sin respuesta, sigue sin acciones hasta la lista siguiente (que lo vuelve a pedir).
                if (leido != null && l.seleccionado?.orderId == orderId) base.copy(releido = Releido(deLaLista, leido)) else base
            }
        }
    }

    private fun avisoDeFallo(e: Throwable, accion: Accion): AvisoDeFolio = when {
        // D661 (Ruling 36 · 71): devuelto, devolviéndose o «No lo recogió» NO es «hecho» (con marca, o con `ORDER_CLOSED_NOT_DELIVERED`
        // aunque no la traiga): se dice el texto del SERVIDOR y la relectura lo pinta.
        e is PedidoYaCambio && e.cerradoSinEntregar -> AvisoDeFolio(TextosPorPagar.YA_CAMBIO.titulo, e.message)
        // Sin marca, como siempre (también un COMPLETED: otra caja ya lo entregó).
        e is PedidoYaCambio -> TextosPorPagar.YA_CAMBIO
        else -> when ((e as? BackendHttpException)?.errorCode) {
            "ORDER_HAS_PAYMENTS" -> TextosPorPagar.CON_COBRO
            "ORDER_STATE_CHANGED" -> TextosPorPagar.YA_CAMBIO
            "PAYMENT_REQUIRED" -> TextosPorPagar.SIN_PAGAR
            else -> if (sinRed(e)) AvisoDeFolio("Sin conexión", accion.sinRed) else AvisoDeFolio(accion.fallo, "Inténtalo de nuevo.")
        }
    }

    private fun sinRed(e: Throwable) = e is IOException

    /** false si el folio ya tiene una acción en curso. */
    private fun ocupar(orderId: String): Boolean {
        var libre = false
        local.update {
            libre = orderId !in it.ocupados
            if (libre) it.copy(ocupados = it.ocupados + orderId) else it
        }
        return libre
    }

    private fun avisar(aviso: AvisoDeFolio): AperturaDeCobro? {
        local.update { it.copy(aviso = aviso) }
        return null
    }

    private fun componer(e: EstadoDeFolios, l: Local): PorPagarUi {
        // R92 M2 / R94 / R96: lo que esta caja sacó sigue escondido sólo mientras el SERVIDOR lo traiga EXACTAMENTE como estaba
        // (misma sección, mismo estado y mismo saldo): una relectura vieja no lo regresa con sus botones. Si lo trae distinto, se ve:
        // un cancelado que revive por dinero tardío llega ACCEPTED en «Por entregar», o en «Por pagar» igual pero con un abono
        // (falta menos) y hay que cobrarle el resto. Si ya no viene, se olvida (ver `init`).
        fun escondido(f: Folio, seccion: SeccionDeFolios) =
            l.quitados[f.orderId]?.let { it.seccion == seccion && it.estado == f.estado && it.faltaCentavos == f.faltaCentavos } == true
        val pagar = e.porPagar.filterNot { escondido(it, SeccionDeFolios.POR_PAGAR) }
        val entregar = e.porEntregar.filterNot { escondido(it, SeccionDeFolios.POR_ENTREGAR) }
        // R92 I1: a la lista en que se sacó, el SERVIDOR todavía lo contaba; desde la vuelta siguiente ya viene descontado.
        fun sinDescontar(f: Folio, seccion: SeccionDeFolios) = escondido(f, seccion) && l.quitados[f.orderId]?.marca == e.actualizadoEnMs
        // Ruling 70: el abierto que la lista buena ya no trae se ve con lo que contestó su relectura (de ESA lista); mientras, sin acciones.
        val enLista = l.seleccionado?.let { s -> (e.porPagar + e.porEntregar).firstOrNull { it.orderId == s.orderId } }
        val releido = l.releido?.takeIf { it.folio.orderId == l.seleccionado?.orderId }
        val actualizando = l.seleccionado != null && enLista == null && !e.sinConexion && !e.desactualizado &&
            e.actualizadoEnMs != null && releido?.deLaLista != e.actualizadoEnMs
        return PorPagarUi(
            folios = e,
            busqueda = l.busqueda,
            buscando = textoDeBusqueda(l.busqueda).length >= MIN_BUSQUEDA,
            porPagar = filtrarFolios(pagar, l.busqueda),
            porEntregar = filtrarFolios(entregar, l.busqueda),
            numeroPorPagar = numeroDeSeccion(e.counts.porPagar, e.porPagar.count { sinDescontar(it, SeccionDeFolios.POR_PAGAR) }),
            numeroPorEntregar = numeroDeSeccion(e.counts.porEntregar, e.porEntregar.count { sinDescontar(it, SeccionDeFolios.POR_ENTREGAR) }),
            puedeEntregar = l.puedeEntregar,
            puedeCancelar = l.puedeCancelar,
            seleccionado = l.seleccionado?.let { s -> enLista ?: releido?.folio ?: s },
            ocupados = l.ocupados,
            aviso = l.aviso,
            toast = l.toast,
            puedeUsarSaldo = l.puedeUsarSaldo,
            comprobandoSaldo = l.comprobandoSaldo,
            saldoSinConfirmar = l.saldoSinConfirmar,
            saldoIlegible = l.saldoIlegible,
            tarjetasPorAbrir = e.tarjetasPorAbrir.filterNot { it.orderId in l.abiertosAqui },
            cobrandoEnOtraCaja = l.confirmando.keys,
            seleccionadoActualizando = actualizando,
        )
    }

    private companion object {
        const val MAX_PAGINAS = 20
    }
}

private const val MIN_BUSQUEDA = 3

private fun normalizar(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private fun textoDeBusqueda(texto: String) = normalizar(texto).replace("-", "").replace(" ", "")

/**
 * R61: con 3 o más caracteres (sin contar espacios ni guiones) busca por folio, sin importar mayúsculas ni la W, y por nombre
 * sin importar acentos. Con menos, no filtra. Igual que ANDROID (`FoliosPorPagar.filtrarFolios`).
 */
fun filtrarFolios(lista: List<Folio>, texto: String): List<Folio> {
    val folio = textoDeBusqueda(texto)
    if (folio.length < MIN_BUSQUEDA) return lista
    val nombre = normalizar(texto).trim()
    return lista.filter { normalizar(it.folio).contains(folio) || normalizar(it.nombre.orEmpty()).contains(nombre) }
}

/**
 * R92 I1: el número de una sección es el del SERVIDOR (`counts`), nunca el largo de la lista ni el mayor de los dos (las páginas
 * cargadas pueden traer folios viejos hasta releerse, R85 #3). Sólo se le resta lo que esta caja sacó y ese conteo todavía cuenta.
 */
fun numeroDeSeccion(conteoDelServidor: Int, sacadosAquiSinDescontar: Int): Int = (conteoDelServidor - sacadosAquiSinDescontar).coerceAtLeast(0)

/** «Cancelar folio» no existe en un folio con dinero ni sin `orders:cancel-unpaid`; sin red vuelve con la conexión. */
/** Full-testing H2: lo que dice el folio sin red. */
fun textoDelFolioSinRed(saldoEnDuda: Boolean, ilegible: Boolean = false): String = when {
    !saldoEnDuda -> TextosPorPagar.FOLIO_SIN_RED
    ilegible -> TextosPorPagar.SALDO_ILEGIBLE_SIN_RED
    else -> TextosPorPagar.SALDO_EN_DUDA_SIN_RED
}

/** D12 r3: la marca de la fila: el cobro con saldo de ESE folio sin confirmar, o el almacén que no se deja leer (cualquier folio). */
fun marcaDeSaldo(ui: PorPagarUi, orderId: String): String? = when {
    orderId in ui.saldoSinConfirmar -> TextosPorPagar.SALDO_SIN_CONFIRMAR_MARCA
    ui.saldoIlegible -> TextosPorPagar.SALDO_ILEGIBLE_MARCA
    else -> null
}

/** Full-testing H5 (mockup 2e·3): «Falta» arriba del monto del cobro cuando la cuenta ya tiene un abono. */
fun muestraFalta(folio: ComandaDeFolio): Boolean =
    (folio.yaPago?.signum() ?: 0) > 0 || (folio.pagadoConSaldo?.signum() ?: 0) > 0

/** Parte 2: tampoco con un cobro con saldo sin confirmar ([saldoEnDuda]): podría haberse cobrado. */
fun muestraCancelarFolio(folio: Folio, puedeCancelar: Boolean, sinConexion: Boolean, saldoEnDuda: Boolean = false): Boolean =
    puedeCancelar && !sinConexion && folio.abono.signum() == 0 && !saldoEnDuda

/** «Saldo o tarjeta de regalo» en el folio: [activo] o en gris con su [nota]. */
data class BotonDeSaldo(val activo: Boolean, val nota: String?)

/**
 * Parte 2 (P2): sólo con `giftCards:redeem` y en un folio que todavía debe; sin red sale en gris con «Necesita conexión» (el saldo es en
 * línea a propósito). null = no se muestra.
 */
fun botonDeSaldo(puedeUsarSaldo: Boolean, sinConexion: Boolean, pagado: Boolean): BotonDeSaldo? = when {
    !puedeUsarSaldo || pagado -> null
    sinConexion -> BotonDeSaldo(activo = false, nota = TextosPorPagar.NECESITA_CONEXION)
    else -> BotonDeSaldo(activo = true, nota = null)
}

/**
 * S14 (P2-3): «Con cuenta: Ana L. · Premio −$45.00» — de quién es la cuenta y qué beneficios trae, como lo dice el MCP. Nunca recorta en
 * silencio («y N más»). null sin cuenta ni beneficios (un folio sin cuenta o un SERVIDOR viejo): como hoy, nada.
 */
fun lineaDeLaCuenta(folio: Folio): String? {
    val partes = buildList {
        folio.cliente?.let { add(TextosPorPagar.conCuenta(it)) }
        folio.beneficios.forEach { add(TextosPorPagar.beneficio(it.nombre, it.monto)) }
        (folio.beneficiosTotal - folio.beneficios.size).takeIf { it > 0 }?.let { add(TextosPorPagar.yMas(it)) }
    }
    return partes.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** «Listo» y «Entregado» sólo con `orders:update` y con red. */
fun muestraListoYEntregado(puedeEntregar: Boolean, sinConexion: Boolean): Boolean = puedeEntregar && !sinConexion

/** D661 (Ruling 36): un pedido devuelto, devolviéndose o que nadie recogió ya no se entrega: no se ofrece «Listo» ni «Entregado». */
fun sePuedeEntregar(folio: Folio): Boolean = folio.marca == null

/** D661: cómo se ve el botón de un pagado en línea. [activo] = se puede tocar; [texto] dice qué hace o por qué no. */
data class BotonDeAbrir(val activo: Boolean, val texto: String)

/** D661: null = sin `orders:update` (no hay botón: se dice a quién pedírselo). Sin red, en gris y lo dice; nunca se encola. */
fun botonDeAbrir(tarjeta: TarjetaPorAbrir, ui: PorPagarUi): BotonDeAbrir? = when {
    !ui.puedeEntregar -> null
    // Primero lo de ESTA caja: su propio cobro en curso nunca se dice «en otra caja».
    tarjeta.orderId in ui.ocupados -> BotonDeAbrir(false, TextosPorPagar.ABRIENDO)
    tarjeta.cobrando || tarjeta.orderId in ui.cobrandoEnOtraCaja -> BotonDeAbrir(false, TextosPorPagar.COBRANDO_EN_OTRA_CAJA)
    ui.folios.sinConexion -> BotonDeAbrir(false, TextosPorPagar.ABRIR_NECESITA_CONEXION)
    else -> BotonDeAbrir(true, TextosPorPagar.ABRIR_Y_COBRAR)
}

/** P6 (R98): «Por entregar» = pagado y sin entregar = ACCEPTED + READY. Un folio READY ya está «Listo». */
fun estaListo(folio: Folio): Boolean = folio.estado == ESTADO_LISTO

/** «Listo» sólo en lo que todavía no lo está; un folio READY ofrece únicamente «Entregado». */
fun muestraBotonListo(folio: Folio, puedeEntregar: Boolean, sinConexion: Boolean): Boolean =
    muestraListoYEntregado(puedeEntregar, sinConexion) && !estaListo(folio)

private const val ESTADO_LISTO = "READY"

/** «Pagado a las HH:MM» en «Por entregar»; null si el SERVIDOR no manda la hora. */
fun textoDePago(folio: Folio, zona: ZoneId): String? = folio.pagadoEn?.let { TextosPorPagar.pagadoALas(hora(it, zona)) }

/** [pronto] = 5 min o menos: se pinta en ámbar (nunca en rojo). */
data class Vencimiento(val texto: String, val pronto: Boolean)

/**
 * Con red «Vence en N min» (N hacia arriba: nunca promete de más); sin red la hora fija «Vence a las HH:MM», porque sin red no se
 * sabe si ya venció; ya vencido «Venció a las HH:MM», sin color. null sin hora o con abono (un abono no vence solo).
 */
fun vencimiento(folio: Folio, ahora: Instant, sinConexion: Boolean, zona: ZoneId): Vencimiento? {
    if (folio.tieneAbono) return null
    val vence = folio.venceEn ?: return null
    if (sinConexion) return Vencimiento(TextosPorPagar.venceALas(hora(vence, zona)), pronto = false)
    if (!vence.isAfter(ahora)) return Vencimiento(TextosPorPagar.vencioALas(hora(vence, zona)), pronto = false)
    val minutos = (Duration.between(ahora, vence).toMillis() + 59_999) / 60_000
    return Vencimiento(TextosPorPagar.venceEn(minutos), pronto = minutos <= 5)
}

/** Sin red: «(de hace N min)» desde la última lista buena. */
fun deHace(actualizadoEnMs: Long, ahoraMs: Long): String {
    val minutos = (ahoraMs - actualizadoEnMs) / 60_000
    return if (minutos <= 0) "(de hace un momento)" else "(de hace $minutos min)"
}

private val HORA = DateTimeFormatter.ofPattern("HH:mm")

fun hora(instante: Instant, zona: ZoneId): String = HORA.format(instante.atZone(zona))
