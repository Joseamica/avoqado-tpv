package com.jaac.avoqado_tpv.features.onlineorders.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.features.onlineorders.data.FoliosRepository
import com.jaac.avoqado_tpv.features.onlineorders.data.VigiaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.domain.EstadoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.PaginaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
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
    fun folioDelCobro(folio: String, nombre: String?) =
        "Folio $folio" + (nombre?.trim()?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
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
    const val SIN_CONEXION_CUERPO = "los pedidos nuevos no se ven. Cobra en \"Cobrar\" como venta normal"
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
    val YA_CAMBIO = AvisoDeFolio("Este folio ya cambió", "Otra caja, el panel o la clienta lo cambió. La lista se está actualizando.")
    val SIN_PAGAR = AvisoDeFolio("Este folio no está pagado", "Cóbralo antes de entregarlo.")
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
)

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
)

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
    )

    /**
     * R92 M2 / R94 / R96 / R97: un folio que esta caja sacó, con la [seccion], el [estado] y lo que faltaba ([faltaCentavos]) ANTES
     * de mandar la acción. [marca] = `actualizadoEnMs` de la lista que se veía al confirmarse (su conteo todavía lo cuenta).
     */
    private data class Quitado(val marca: Long?, val seccion: SeccionDeFolios, val estado: String, val faltaCentavos: Long)

    private val local = MutableStateFlow(Local())

    /** R97: el número de esta pantalla en el vigía; al limpiarse sólo suelta lo suyo. */
    private val lista = vigia.abrirLista()

    val ui: StateFlow<PorPagarUi> = combine(vigia.estado, local, ::componer)
        .stateIn(viewModelScope, SharingStarted.Eagerly, componer(vigia.estado.value, local.value))

    /** La hora que ve la cajera es la del negocio. */
    val zona: ZoneId get() = VenueTimeZone.get(storage)

    init {
        // R94: un folio que ya no viene en ninguna lista se olvida (el SERVIDOR ya confirmó lo que se hizo con él).
        viewModelScope.launch {
            vigia.estado.collect { e ->
                val vivos = (e.porPagar + e.porEntregar).mapTo(HashSet()) { it.orderId }
                local.update { l -> if (l.quitados.keys.all { it in vivos }) l else l.copy(quitados = l.quitados.filterKeys { it in vivos }) }
            }
        }
        viewModelScope.launch {
            val entregar = permisos.hasPermission("orders:update")
            val cancelar = permisos.hasPermission("orders:cancel-unpaid")
            local.update { it.copy(puedeEntregar = entregar, puedeCancelar = cancelar) }
        }
    }

    /**
     * Cada vez que la pantalla vuelve a verse (también al regresar de un cobro): el vigía pregunta ya. Así un folio recién
     * cobrado aquí no sigue enseñando «Cobrar» hasta la siguiente vuelta.
     */
    fun pantallaVisible() = vigia.sondearYa()

    fun buscar(texto: String) = local.update { it.copy(busqueda = texto) }
    fun abrir(folio: Folio) = local.update { it.copy(seleccionado = folio) }
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
    suspend fun cobrar(folio: Folio, abrir: suspend (AperturaDeCobro) -> Unit = {}): AperturaDeCobro? {
        if (vigia.estado.value.sinConexion) return null
        val venueId = storage.getVenueId()?.takeIf { it.isNotBlank() } ?: return null
        if (!ocupar(folio.orderId)) return null
        try {
            // R78/R83/R89: el SERVIDOR sólo sabe del cobro cuando sube; hasta entonces la relectura lo daría «por pagar» otra vez.
            dineroSinConfirmar(venueId, folio.orderId)?.let { return avisar(it) }
            return releer(venueId, folio)?.also { abrir(it) }
        } finally {
            local.update { it.copy(ocupados = it.ocupados - folio.orderId) }
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

    fun cancelar(folio: Folio) = accion(folio, Accion.CANCELAR)
    fun listo(folio: Folio) = accion(folio, Accion.LISTO)
    fun entregado(folio: Folio) = accion(folio, Accion.ENTREGADO)

    private suspend fun releer(venueId: String, folio: Folio): AperturaDeCobro? {
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
                folio = ComandaDeFolio(vivo.orderId, vivo.folio, vivo.nombre, vivo.nota, vivo.productos),
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

    private fun avisoDeFallo(e: Throwable, accion: Accion): AvisoDeFolio = when ((e as? BackendHttpException)?.errorCode) {
        "ORDER_HAS_PAYMENTS" -> TextosPorPagar.CON_COBRO
        "ORDER_STATE_CHANGED" -> TextosPorPagar.YA_CAMBIO
        "PAYMENT_REQUIRED" -> TextosPorPagar.SIN_PAGAR
        else -> if (sinRed(e)) AvisoDeFolio("Sin conexión", accion.sinRed) else AvisoDeFolio(accion.fallo, "Inténtalo de nuevo.")
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
            seleccionado = l.seleccionado?.let { s -> (e.porPagar + e.porEntregar).firstOrNull { it.orderId == s.orderId } ?: s },
            ocupados = l.ocupados,
            aviso = l.aviso,
            toast = l.toast,
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
fun muestraCancelarFolio(folio: Folio, puedeCancelar: Boolean, sinConexion: Boolean): Boolean =
    puedeCancelar && !sinConexion && folio.abono.signum() == 0

/** «Listo» y «Entregado» sólo con `orders:update` y con red. */
fun muestraListoYEntregado(puedeEntregar: Boolean, sinConexion: Boolean): Boolean = puedeEntregar && !sinConexion

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
