package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.PermisoRequeridoException
import com.jaac.avoqado_tpv.features.permissions.presentation.override.Autorizacion
import com.jaac.avoqado_tpv.features.permissions.presentation.override.AutorizacionDeGerente
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.SaldoDeMesaSinRed
import com.jaac.avoqado_tpv.features.tables.data.SaldoSinRed
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.etiquetaDeMesa
import com.jaac.avoqado_tpv.features.tables.domain.model.ActiveStaffMember
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import dagger.hilt.android.lifecycle.HiltViewModel
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * `TableOrderScreen` (Plan C, Task 7) — lo ya enviado a cocina (agrupado por
 * ronda) vs lo pendiente de enviar. Puerto RECORTADO de
 * `avoqado-android/tables/presentation/TableOrderViewModel.kt` (`sendRound`
 * :403, write-ahead offline :539-576) — sin cursos/tiempos (menú por
 * horario), sin comandas (la TPV no tiene `PrintConfigRepository`/
 * `ComandaPrinter` — infraestructura que este repo no tiene todavía, no un
 * recorte silencioso), sin lealtad/descuentos/cargos por servicio (Task 8,
 * `TableCheckoutScreen`). El alcance real de esta tarea: ver el cheque, armar
 * y enviar una ronda, ofline-first, respetando propiedad de mesa.
 *
 * **Provisional-aware a propósito** (hallazgo documentado en el reporte de la
 * Task 5): [TablesRepository.addItems] SIEMPRE construye la URL con
 * `orderId` real — nunca se le debe llamar mientras
 * [TableSession.Active.isProvisional] sea true (la orden aún no existe en el
 * server). Mientras la sesión sea provisional, [sendRound] rutea por
 * [TableSession.buildAddItemsPayload] + [SyncOutbox.enqueue] directo, nunca
 * por el repositorio.
 */
@HiltViewModel
class TableOrderViewModel @Inject constructor(
    private val repository: TablesRepository,
    val tableSession: TableSession,
    val pendingCart: PendingRoundCart,
    private val tableSyncCoordinator: TableSyncCoordinator,
    private val syncOutbox: SyncOutbox,
    private val deviceInfoManager: DeviceInfoManager,
    private val secureStorage: SecureStorage,
    private val networkMonitor: NetworkMonitor,
    private val autorizacionDeGerente: AutorizacionDeGerente,
    /** D86: el saldo sin red de una cuenta que esta terminal abrió sin red (rondas propias menos efectivo propio). */
    private val saldoDeMesaSinRed: SaldoDeMesaSinRed,
    /** D1 (hz:163ad26c:D1): los avisos del SERVIDOR sobre ESTA cuenta (otro aparato abona o manda una ronda). */
    private val socketManager: SocketManager,
) : ViewModel() {
    /** La hora que se le enseña al mesero es la del negocio, no el desfase que trae el servidor. */
    val zonaDelNegocio: java.time.ZoneId get() = com.jaac.avoqado_tpv.core.util.VenueTimeZone.get(secureStorage)


    /**
     * Fase 1 — solo consumido hoy por [compItems] (UI): cortesía de artículo
     * puntual es online-only (ver KDoc de esa función) y
     * [CompOrderSheet][com.jaac.avoqado_tpv.features.tables.presentation.CompOrderSheet]
     * necesita saber ANTES del tap si debe deshabilitar esa mitad del sheet —
     * mismo patrón que `TableCheckoutViewModel.networkMonitor`.
     */
    private val _isOnline = MutableStateFlow(networkMonitor.isConnected())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _check = MutableStateFlow<OrderDetail?>(null)
    val check: StateFlow<OrderDetail?> = _check.asStateFlow()

    private val _isLoadingCheck = MutableStateFlow(false)
    val isLoadingCheck: StateFlow<Boolean> = _isLoadingCheck.asStateFlow()

    /**
     * 🔴 Bug real (Mesa M1, `cmsds40wp000cc9ixmm8u71f4`, encontrado en vivo en
     * el PAX del founder): [loadCheck] fallando (blip de red, timeout, un
     * 500) dejaba [_check] en `null` SIN ninguna señal — `TableOrderScreen`
     * no tenía forma de distinguir "cuenta genuinamente vacía" de "no se pudo
     * cargar", así que caía siempre en `EmptyOrderState`
     * ("Todavía no hay nada en esta cuenta"), IDÉNTICO a una mesa realmente
     * vacía. El server tenía los 3 artículos y $199.00 completos — la falla
     * era puramente de UI, y bloqueaba al mesero de cobrar una cuenta con
     * dinero real. `onRetryLoad` ya viajaba hasta `TableOrderScreenContent`
     * pero nunca se usaba ahí — el candado de retry existía a medias.
     *
     * Solo se enciende cuando [_check] TODAVÍA es `null` (primera carga) —
     * un refresh fallido sobre una cuenta YA visible nunca debe borrarla ni
     * disfrazarla de "no se pudo cargar" (ver el `_error` toast existente
     * para ese caso).
     */
    private val _checkLoadFailed = MutableStateFlow(false)
    val checkLoadFailed: StateFlow<Boolean> = _checkLoadFailed.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /**
     * 🔴 Hallazgo real (founder, PAX en vivo — reporte "se quedó trabado" /
     * "tardó mucho" en "sincronizando orden"): [sendRound] SÍ tenía candado
     * anti-doble-tap (`if (_isSending.value) return`, ver abajo) y el envío
     * en sí medía ~1s en condiciones sanas — el problema no era duplicar la
     * ronda, era la EXPERIENCIA durante la espera. El único cambio visible
     * mientras `isSending` es `true` es un `CircularProgressIndicator` mudo
     * ([AvoqadoButton][com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton]
     * con `loading=true`) — sin texto, sin tiempo transcurrido, sin ninguna
     * señal que distinga "sigue trabajando" de "se congeló". Contra el
     * sandbox (ngrok + servidor local), o cualquier bache real de red, el
     * `OkHttpClient` de `NetworkModule` no falla hasta su `callTimeout` de
     * 25s — así que un envío lento puede dejar ESE spinner mudo girando
     * hasta 25 segundos SIN que el mesero tenga forma de saber si algo va
     * a pasar.
     *
     * Esta bandera NO es otro candado de negocio — [_isSending] sigue
     * siendo la única fuente de verdad para eso. Es puramente de
     * PRESENTACIÓN: pasado [SEND_ROUND_REASSURANCE_DELAY_MS] sin que el
     * envío haya terminado, `TableOrderScreen` pinta un texto de
     * reafirmación ("Sigue enviando…") junto al botón — nunca reemplaza al
     * spinner, nunca toca [AvoqadoButton] (compartido con "Cobrar",
     * intocable por regla dura), vive enteramente en `features/tables/`.
     */
    private val _sendTakingLong = MutableStateFlow(false)
    val sendTakingLong: StateFlow<Boolean> = _sendTakingLong.asStateFlow()

    /** Líneas en el carrito, SIN enviar todavía — ver [PendingRoundCart]. */
    val pendingLines: StateFlow<List<PendingRoundCart.Line>> = pendingCart.lines

    /**
     * Rondas "enviadas" SIN red — ya salieron del carrito, esperan el ack del
     * replay del outbox. `TableOrderScreen` las pinta con la etiqueta "Por
     * sincronizar", NUNCA como error (offline es estado normal — ver
     * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.3).
     */
    private val _queuedLines = MutableStateFlow<List<PendingRoundCart.Line>>(emptyList())
    val queuedLines: StateFlow<List<PendingRoundCart.Line>> = _queuedLines.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Propiedad de mesa ("Solo el propietario puede modificar sus mesas") —
     * el cheque es de OTRO mesero, el switch del venue está encendido, y yo no
     * tengo `canManageAllTables` → la pantalla se pinta read-only. El server
     * refuerza la regla en el reducer de replay (`assertOwnership` en
     * `sync.mobile.service.ts`) de todos modos — esto es UX, no el candado real.
     *
     * `check?.servedBy?.id == null` (mesa recién abierta / sesión provisional
     * sin cheque cargado todavía) siempre da `false` aquí — [TableOwnership.isLockedForMe]
     * exige `ownerId != null` — nunca bloquea antes de saber quién es el dueño.
     */
    val readOnly: StateFlow<Boolean> = combine(_check, repository.ownership) { check, ownership ->
        ownership.isLockedForMe(check?.servedBy?.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * ¿Ni siquiera puedo COBRARLA?
     *
     * 🔴 Distinto de [readOnly] A PROPÓSITO. El server exime la ruta de cobro de
     * la propiedad de mesa con `tables:pay-any` (`PAYMENT_OWNERSHIP_OVERRIDES`,
     * aplicado también por el reducer de `PAY_CASH`), así que el CAJERO liquida
     * el cheque de un mesero sin poder editarlo. Mientras "Pagar" colgó de
     * [readOnly], el botón **ni se pintaba** para un cajero — la llamada nunca
     * salía, el 403 nunca llegaba, y el gate del cliente era lo ÚNICO que
     * bloqueaba (la forma cara del bug: no deja rastro en el log del server).
     *
     * Sólo gobierna el botón "Pagar". Todo lo que EDITA —enviar ronda,
     * descontar, cortesiar, cancelar, mover, fusionar, separar, reasignar—
     * sigue colgando de [readOnly] y de [blockedByOwnership].
     */
    val readOnlyForPayment: StateFlow<Boolean> = combine(_check, repository.ownership) { check, ownership ->
        ownership.isLockedForPayment(check?.servedBy?.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Nombre del dueño para el banner "Mesa de {mesero} — solo lectura". */
    val lockOwnerName: StateFlow<String?> = combine(_check, repository.ownership) { check, ownership ->
        if (!ownership.isLockedForMe(check?.servedBy?.id)) return@combine null
        val served = check?.servedBy ?: return@combine null
        "${served.firstName} ${served.lastName}".trim().ifBlank { null }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val venueId: String? get() = deviceInfoManager.getVenueId()

    /**
     * D86: el saldo de una cuenta que ESTA terminal abrió sin red (`null` si la sesión no es provisional). Es el único número
     * con el que «Pagar» de una mesa provisional se pinta; en gris trae su razón. Ver [SaldoDeMesaSinRed].
     */
    private val _saldoSinRed = MutableStateFlow<SaldoSinRed?>(null)
    val saldoSinRed: StateFlow<SaldoSinRed?> = _saldoSinRed.asStateFlow()

    /**
     * D16 (hz:033d44ca:D16): una ronda que esta terminal mandó sin red a ESTA cuenta y que el SERVIDOR puso en una cuenta aparte
     * porque otra caja ya la había cobrado (D7). Sin el aviso, la ronda desaparecía de la cuenta cobrada y la mesera la creía
     * perdida (acta de D7, N86). Se arma de la cola en disco tras cada lectura de la cuenta, buena o fallida (Codex r1 #1); `null`
     * si no hay.
     *
     * Sólo dice lo que el ACK guardó y ya no cambia (Codex r2, regla de parada): a dónde fue la ronda y por cuánto quedó esa
     * cuenta. Nunca si la cuenta aparte se debe ahora ni en qué mesa está ahora: eso lo cambian otras cajas en cualquier momento
     * (la cobran, la mueven, la juntan) y un aviso guardado mentía. Eso lo dice la cuenta misma al abrirla.
     */
    private val _cuentaAparte = MutableStateFlow<AvisoDeCuentaAparte?>(null)
    val cuentaAparte: StateFlow<AvisoDeCuentaAparte?> = _cuentaAparte.asStateFlow()

    private val _abriendoCuentaAparte = MutableStateFlow(false)
    val abriendoCuentaAparte: StateFlow<Boolean> = _abriendoCuentaAparte.asStateFlow()

    /** D16: sólo la revisión más nueva del aviso se aplica (dos lecturas seguidas de la cuenta). Antes de `init`. */
    private var revisionesDeCuentaAparte = 0L

    /**
     * D16: «Abrir cuenta aparte». Lee la cuenta aparte del SERVIDOR en ese momento y la abre en la mesa donde está ahora (otro
     * aparato pudo moverla, Codex r1 #3 y r2 #2), sin salir de esta pantalla. Lo que la mesera tenía sin mandar en el carrito se va
     * con ella (en la cobrada no se podía mandar). Si no se puede leer, no cambia nada y lo dice. Si mientras leía la mesera salió
     * o entró a otra cuenta, no la mueve de ahí. Mientras abre no se puede «Enviar ronda», ni abrir mientras se envía (Codex r4).
     *
     * Codex r3: mientras esta terminal tenga algo sin subir (o por revisar) de la cobrada, no se abre: eso nombra la cobrada y en la
     * cuenta aparte nadie lo vería ni frenaría su cobro. Como «Abrir» y «Enviar ronda» se excluyen durante TODA la apertura (Codex
     * r4), nada nuevo para la cobrada nace entre esa revisión y el cambio de cuenta.
     */
    fun abrirCuentaAparte() {
        val aviso = _cuentaAparte.value ?: return
        val vId = venueId ?: return
        val original = tableSession.current() ?: return
        if (_abriendoCuentaAparte.value || _isSending.value) return
        _abriendoCuentaAparte.value = true
        val epocaAlTocar = tableSession.epoch
        viewModelScope.launch {
            try {
                val leida = repository.getOrder(vId, aviso.orderId).getOrNull()?.takeIf { it.id == aviso.orderId }
                val actual = tableSession.current()
                if (tableSession.epoch != epocaAlTocar || actual == null || actual.orderId != original.orderId) return@launch
                val mesa = leida?.tableId
                if (leida == null || mesa == null) {
                    _error.value = NO_SE_ABRIO_LA_CUENTA_APARTE
                    return@launch
                }
                if (pendienteDeLaMesa(vId, actual) != null) {
                    _error.value = ESPERA_A_QUE_SE_SUBA_PARA_ABRIR
                    return@launch
                }
                if (tableSession.epoch != epocaAlTocar || tableSession.current()?.orderId != original.orderId) return@launch
                val mismaMesa = mesa == actual.tableId
                pendingCart.promote(actual.orderId, leida.id)
                tableSession.start(
                    actual.copy(
                        tableId = mesa,
                        tableNumber = if (mismaMesa) actual.tableNumber else leida.tableName?.removePrefix("Mesa ")?.takeIf { it.isNotBlank() },
                        areaName = actual.areaName.takeIf { mismaMesa },
                        orderId = leida.id,
                        orderNumber = leida.orderNumber.takeIf { it.isNotBlank() },
                        version = leida.version,
                        total = leida.total,
                        mode = TableSession.Mode.ORDERING,
                        isProvisional = false,
                        localOrderId = null,
                    ),
                )
                _cuentaAparte.value = null
                _check.value = leida
                loadCheck()
            } finally {
                _abriendoCuentaAparte.value = false
            }
        }
    }

    /** D16: revisa en la cola (en disco) si una ronda de [session] cayó en una cuenta aparte. Sin red: no lee nada del SERVIDOR. */
    private fun revisarCuentaAparte(venueId: String, session: TableSession.Active) {
        val miRevision = ++revisionesDeCuentaAparte
        viewModelScope.launch {
            val aparte = try {
                saldoDeMesaSinRed.cuentaAparte(venueId, session.orderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [TableOrder] No se pudo revisar la cola por una cuenta aparte")
                return@launch // se conserva el aviso que hubiera
            }
            if (miRevision != revisionesDeCuentaAparte || tableSession.current()?.orderId != session.orderId) return@launch
            _cuentaAparte.value = aparte?.let {
                AvisoDeCuentaAparte(
                    orderId = it.orderId,
                    texto = textoDeCuentaAparte(mesa = session.tableNumber?.let { n -> etiquetaDeMesa(n) }, monto = it.total),
                )
            }
        }
    }

    /** D86 (Codex r4): sólo el cálculo más nuevo del saldo sin red se aplica (uno viejo puede llegar después). Antes de `init`. */
    private var calculosDeSaldo = 0L

    /** D86 (Codex r5 #2): relectura de la cuenta mientras una ronda propia no sube (con tope). Antes de `init`. */
    private var relecturaDeLaMesa: kotlinx.coroutines.Job? = null
    private var relecturasDeLaMesa = 0

    /**
     * D86 (Codex r6 #2) y D1 (Codex D88-D90 r6): cuántas veces se ha PEDIDO la cuenta y cuál es la petición de la cuenta que se
     * ve. El número se toma justo al pedir (antes de pedir, una lectura espera la cola y otra puede adelantársele) y una
     * respuesta se tira sólo si una petición POSTERIOR ya pintó con éxito: con «la lectura que empezó al último manda», la
     * lectura buena tras una ronda propia se tiraba si la del aviso del SERVIDOR fallaba después, y con avisos seguidos
     * ninguna pintaba. Antes de `init`.
     */
    private var cargasDeLaMesa = 0L
    private var cuentaPintada = 0L

    /** D86 (Codex r7 H4): la última lectura vio algo propio sin subir de esta cuenta (aunque «Por sincronizar» esté vacío). */
    private var habiaPendienteSinSubir = false

    init {
        tableSession.current()?.let { pendingCart.ensureOrder(it.orderId) }
        viewModelScope.launch {
            // D86: al volver la red, la cuenta se relee sola (antes «Por sincronizar» se quedaba hasta otra acción).
            var conectadaAntes = networkMonitor.isConnected()
            networkMonitor.networkStateFlow.collect { info ->
                _isOnline.value = info.isConnected
                if (info.isConnected && !conectadaAntes) {
                    relecturasDeLaMesa = 0
                    loadCheck()
                }
                conectadaAntes = info.isConnected
            }
        }
        viewModelScope.launch {
            // D86: el ACK de OPEN_TABLE promueve la sesión (mismo epoch): la cuenta ya existe en el SERVIDOR y se lee sola;
            // mientras siga provisional, cualquier cambio de la sesión (p. ej. un cobro en «Cobrar») recalcula su saldo.
            var antes = tableSession.current()
            tableSession.active.collect { ahora ->
                val previa = antes
                val promovida = previa != null && previa.isProvisional && ahora != null && !ahora.isProvisional && previa.tableId == ahora.tableId
                antes = ahora
                if (promovida) {
                    _saldoSinRed.value = null
                    loadCheck()
                } else if (ahora?.isProvisional == true) {
                    recalcularSaldoSinRed()
                }
            }
        }
        venueId?.let { vId ->
            viewModelScope.launch {
                // D86 (Codex r7 H2–H4): cada cambio de la cola (un ACK, un rechazo, una ronda que sube, también del trabajador)
                // vuelve a calcular: el saldo sin red de una mesa provisional, o la cuenta si algo propio estaba sin subir.
                // r8 H2: sin condiciones (una lectura vieja pudo apagar las banderas antes de que llegara el aviso), y r8 R1:
                // sin reproducir la cola (si no, la escritura del replay vuelve a avisar sin fin).
                saldoDeMesaSinRed.cambiosDeLaCola(vId).collect {
                    val actual = tableSession.current() ?: return@collect
                    if (actual.isProvisional) recalcularSaldoSinRed() else cargar(reproducir = false)
                }
            }
        }
        viewModelScope.launch {
            // D1 (hz:163ad26c:D1, 16-nov): si OTRO aparato le cambia esta cuenta con la pantalla abierta (abona, manda una
            // ronda), el SERVIDOR avisa con el id de la orden y la cuenta se relee sola; antes sólo al salir y volver, y «Pagar»
            // seguía con el restante viejo mientras «Cobrar» ya decía otro. Sin reproducir la cola (D86 r8). Un aviso por ráfaga.
            var relecturaPorAviso: kotlinx.coroutines.Job? = null
            socketManager.events.collect { event ->
                val orden = when (event) {
                    is SocketEvent.OrderUpdated -> event.orderId
                    is SocketEvent.OrderStatusChanged -> event.orderId
                    is SocketEvent.PaymentCompleted -> event.orderId
                    else -> null
                }
                val actual = tableSession.current()
                if (orden == null || actual == null || actual.isProvisional || orden != actual.orderId) return@collect
                relecturaPorAviso?.cancel()
                relecturaPorAviso = launch {
                    delay(RELECTURA_POR_AVISO_MS)
                    cargar(reproducir = false)
                }
            }
        }
        // D86: después de los colectores: si la carga promueve la sesión en el acto (apertura ya confirmada), alguien la ve.
        loadCheck()
    }

    /** D86: recalcula [saldoSinRed] de la cola y la libreta. Sólo para una sesión provisional; si no, `null`. */
    fun recalcularSaldoSinRed() {
        val session = tableSession.current()
        val vId = venueId
        if (session == null || !session.isProvisional || vId == null) {
            _saldoSinRed.value = null
            return
        }
        val miCalculo = ++calculosDeSaldo
        viewModelScope.launch {
            val saldo = try {
                // D86 (Codex r0 #2): si el ACK de la apertura llegó por otro camino (p. ej. el cobro en efectivo reprodujo la
                // cola), la cola en disco lo guardó: se promueve aquí y la pantalla lee la cuenta del SERVIDOR.
                val apertura = saldoDeMesaSinRed.aperturaConfirmada(vId, session.orderId)
                if (apertura != null) {
                    tableSyncCoordinator.onAck(SyncIntentTypes.OPEN_TABLE, session.orderId, apertura.orderId, apertura.orderNumber, apertura.version)
                    return@launch
                }
                saldoDeMesaSinRed.saldo(vId, session.orderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [TableOrder] No se pudo leer la cola para el saldo sin red")
                SaldoSinRed.EnGris(com.jaac.avoqado_tpv.features.tables.data.SALDO_NO_RECUPERADO)
            }
            // Sólo si la sesión sigue siendo esa misma cuenta provisional.
            val actual = tableSession.current()
            if (miCalculo == calculosDeSaldo && actual != null && actual.orderId == session.orderId && actual.isProvisional) {
                _saldoSinRed.value = saldo
            }
        }
    }

    /** Carga (o recarga) el cheque desde el server. Sesión provisional = nada que cargar todavía. */
    fun loadCheck() = cargar(reproducir = true)

    /**
     * D86 (Codex r8 R1): con [reproducir] en `false` sólo lee (sin reproducir la cola). Es la que usa el aviso de la cola: el
     * replay escribe la cola (p. ej. la referencia del turno en cada intento) y, con él, el aviso volvería a disparar la lectura
     * sin fin. La cola ya la reproduce el trabajador; aquí sólo se lee lo que cambió.
     */
    private fun cargar(reproducir: Boolean) {
        val session = tableSession.current() ?: return
        pendingCart.ensureOrder(session.orderId)
        if (session.isProvisional) {
            _check.value = null
            recalcularSaldoSinRed()
            return
        }
        val vId = venueId ?: return
        viewModelScope.launch {
            _isLoadingCheck.value = true
            // Nueva carga en vuelo — cualquier fallo VIEJO ya no aplica.
            _checkLoadFailed.value = false
            // Best-effort: drena el outbox antes de leer, para que el GET ya
            // vea las rondas que se mandaron sin red. Nunca lanza salvo
            // CancellationException — se blinda igual (mismo patrón que
            // TablesViewModel.loadTables).
            if (reproducir) {
                try {
                    tableSyncCoordinator.replay(vId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ [TableOrder] replay() del outbox falló antes de cargar la cuenta")
                }
            }

            // D86 (Codex r5 #2, r6 #2): «Por sincronizar» se va sólo si esta terminal no tenía nada sin subir de esta cuenta
            // ANTES de leer (así la lectura ya trae todo lo propio) ni lo tiene después. Si no, se conserva y se relee sola.
            val colaAntes = ultimaDeLaCola(vId)
            val pendienteAntes = pendienteDeLaMesa(vId, session)
            val miCarga = ++cargasDeLaMesa // sin suspensión entre este número y la petición: siguen el mismo orden
            val leida = repository.getOrder(vId, session.orderId)
            // D16: si mientras se leía la sesión pasó a otra cuenta («Abrir cuenta aparte»), esta lectura ya no es de la que se ve:
            // pintarla ponía la cuenta y la versión de la vieja sobre la nueva.
            if (tableSession.current()?.orderId != session.orderId) return@launch
            if (miCarga < cuentaPintada) {
                // Una petición posterior ya pintó la cuenta (y apagó la carga): ésa manda.
                return@launch
            }
            leida.fold(
                onSuccess = { detail ->
                    cuentaPintada = miCarga
                    _check.value = detail
                    _checkLoadFailed.value = false
                    tableSession.updateVersion(detail.version)
                    revisarCuentaAparte(vId, session)
                    // D1 (Codex D88-D90 r7): algo propio que nació Y subió mientras esta lectura viajaba no está en ella y no lo ve
                    // ninguna de las dos revisiones. Si la cola recibió algo nuevo, la lectura pinta pero no da lo propio por
                    // incluido: «Por sincronizar» se queda y se relee sola.
                    val pendiente = pendienteAntes ?: pendienteDeLaMesa(vId, session)
                        ?: if (ultimaDeLaCola(vId) != colaAntes) com.jaac.avoqado_tpv.features.tables.data.CUENTA_SE_ACTUALIZA else null
                    // D86 (Codex r7 H2): si mientras se revisaba la cola una petición posterior ya pintó, ésa decide.
                    if (miCarga < cuentaPintada) return@fold
                    habiaPendienteSinSubir = pendiente != null
                    if (pendiente == null) {
                        // El replay pudo haber confirmado rondas que estaban en
                        // "Por sincronizar" — el cheque fresco ya las incluye.
                        _queuedLines.value = emptyList()
                        relecturaDeLaMesa?.cancel()
                        relecturasDeLaMesa = 0
                    } else {
                        programarRelecturaDeLaMesa()
                    }
                },
                onFailure = { e ->
                    // 🔴 Sin red NO es un error — es el estado normal de un
                    // apagón. Avisar aquí hacía que el mesero viera "no se
                    // pudo cargar la cuenta" cada vez que entraba a una mesa
                    // con la cuenta perfectamente bien.
                    if (!esFallaDeRedEnMesas(e)) {
                        _error.value = "No se pudo actualizar la cuenta"
                    }
                    // Ver KDoc de [checkLoadFailed]: SOLO si esta es la
                    // primera carga (todavía no hay cheque visible) — un
                    // refresh fallido sobre una cuenta YA cargada la deja
                    // intacta, nunca la reemplaza por la pantalla de "no se
                    // pudo cargar".
                    if (_check.value == null) {
                        _checkLoadFailed.value = true
                    }
                    // D16 (Codex r1 #1): el ACK ya está en la cola aunque la cuenta no se pudo leer: el aviso sale igual.
                    revisarCuentaAparte(vId, session)
                    // D86 (Codex r6 #3, r7 H4): con algo propio sin subir, una lectura fallida no detiene la relectura.
                    if (_queuedLines.value.isNotEmpty() || habiaPendienteSinSubir) programarRelecturaDeLaMesa()
                },
            )
            _isLoadingCheck.value = false
        }
    }

    /** D86 (Codex r6 #3): «Actualizar» de «Por sincronizar»: vuelve a leer y rearma las relecturas automáticas. */
    fun actualizarCuenta() {
        relecturasDeLaMesa = 0
        loadCheck()
    }

    /** D1: la secuencia más alta de la cola (`-1` si no se pudo leer: dos fallos se ven iguales y no bloquean de más). */
    private suspend fun ultimaDeLaCola(venueId: String): Long = try {
        syncOutbox.ultimaSecuencia(venueId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "⚠️ [TableOrder] No se pudo leer la secuencia de la cola")
        -1L
    }

    private suspend fun pendienteDeLaMesa(venueId: String, session: TableSession.Active): String? = try {
        saldoDeMesaSinRed.pendienteDeSubir(venueId, session.orderId, listOfNotNull(session.localOrderId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "⚠️ [TableOrder] No se pudo revisar la cola de esta cuenta")
        com.jaac.avoqado_tpv.features.tables.data.SALDO_NO_RECUPERADO
    }

    private fun programarRelecturaDeLaMesa() {
        if (relecturaDeLaMesa?.isActive == true || relecturasDeLaMesa >= MAX_RELECTURAS) return
        relecturasDeLaMesa++
        relecturaDeLaMesa = viewModelScope.launch {
            kotlinx.coroutines.delay(RELECTURA_MS)
            relecturaDeLaMesa = null
            loadCheck()
        }
    }

    /**
     * "Enviar ronda" — manda TODO el carrito en un solo `ADD_ITEMS` atómico.
     * Ver KDoc de la clase para el ruteo provisional-aware.
     */
    fun sendRound() {
        val session = tableSession.current()
        if (session == null) {
            _error.value = "No hay mesa activa"
            return
        }
        if (readOnly.value) {
            _error.value = "Mesa de ${lockOwnerName.value ?: "otro mesero"} — solo lectura"
            return
        }
        if (_isSending.value || _abriendoCuentaAparte.value) return // D16 (Codex r4): no mientras se abre la cuenta aparte
        val lines = pendingCart.lines.value
        if (lines.isEmpty()) {
            _error.value = "Agrega productos para enviar"
            return
        }
        val vId = venueId
        if (vId == null) {
            _error.value = "No hay venue configurado en este terminal"
            return
        }
        val items = pendingCart.toAddItemsRequests()

        _isSending.value = true
        // Puramente de presentación — ver KDoc de [_sendTakingLong]. Vive en
        // su propio job para poder cancelarlo en cuanto el envío termine
        // (éxito, encolado o error): si el envío fue rápido, este job nunca
        // llega a disparar y la bandera nunca se enciende.
        val reassuranceJob = viewModelScope.launch {
            delay(SEND_ROUND_REASSURANCE_DELAY_MS)
            _sendTakingLong.value = true
        }
        viewModelScope.launch {
            try {
                if (session.isProvisional) {
                    // La orden de esta mesa AÚN no existe en el server (se abrió
                    // offline) — jamás se intenta el PATCH online, que necesita un
                    // orderId real. Ver TableSession.buildAddItemsPayload.
                    val payload = tableSession.buildAddItemsPayload(items)
                    val intentId = syncOutbox.enqueue(vId, SyncIntentTypes.ADD_ITEMS, payload)
                    // D86: la cola no trae precios; el valor de la ronda al mandarla se anota aparte, por id del intent, para
                    // que «Pagar» de esta mesa abierta sin red sepa cuánto cobrar (ver SaldoDeMesaSinRed).
                    saldoDeMesaSinRed.libreta.anotarRonda(
                        session.orderId,
                        intentId,
                        lines.fold(java.math.BigDecimal.ZERO) { total, line -> total + line.lineTotal },
                    )
                    recalcularSaldoSinRed()
                    _queuedLines.value = _queuedLines.value + lines
                    pendingCart.removeSent(lines) // lo agregado durante el envío se queda
                    _isSending.value = false
                    _notice.value = "Sin conexión — ronda guardada; se sincronizará sola"
                    return@launch
                }

                repository.addItems(vId, session.orderId, items, session.version).fold(
                    onSuccess = { detail ->
                        tableSession.updateVersion(detail.version)
                        val queuedOffline = TablesRepository.wasQueuedOffline(detail)
                        if (queuedOffline) {
                            // Ronda encolada por TablesRepository (sin red) — se ve
                            // como enviada, marcada "Por sincronizar", NUNCA como error.
                            _queuedLines.value = _queuedLines.value + lines
                            _notice.value = "Sin conexión — ronda guardada; se sincronizará sola"
                        } else {
                            _notice.value = "Ronda enviada a cocina"
                        }
                        pendingCart.removeSent(lines) // lo agregado durante el envío se queda
                        _isSending.value = false
                        if (!queuedOffline) loadCheck()
                    },
                    onFailure = { e ->
                        _isSending.value = false
                        if (e is BackendHttpException && e.statusCode == 409) {
                            // VERSION_CONFLICT — otro dispositivo cambió la cuenta.
                            // El carrito NO se pierde: el mesero puede reintentar
                            // tras ver la cuenta actualizada.
                            _notice.value = "La cuenta cambió en otro dispositivo — se actualizó, revisa antes de reenviar"
                            loadCheck()
                        } else {
                            _error.value = (e as? BackendHttpException)?.message ?: "No se pudo enviar la ronda"
                        }
                    },
                )
            } finally {
                // SIEMPRE se apaga junto con el fin del envío — éxito, encolado
                // o error — nunca se queda pegada en pantalla.
                reassuranceJob.cancel()
                _sendTakingLong.value = false
            }
        }
    }

    companion object {
        private const val MOTIVO_PIN_CORTESIA = "Para dar esta cortesía"
        /**
         * Ver KDoc de [_sendTakingLong]. No-privado a propósito — el test
         * (`TableOrderViewModelTest`) lo referencia para no hardcodear el
         * umbral dos veces y desincronizarse en silencio si cambia.
         */
        const val SEND_ROUND_REASSURANCE_DELAY_MS = 4_000L

        /** D1: espera tras un aviso del SERVIDOR antes de releer (una ronda manda un aviso por renglón). Igual que el plano. */
        private const val RELECTURA_POR_AVISO_MS = 800L

        /** D16: «Abrir cuenta aparte» no pudo leer esa cuenta del SERVIDOR (sin red, o ya no existe). */
        const val NO_SE_ABRIO_LA_CUENTA_APARTE = "No se pudo abrir la cuenta aparte: vuelve a intentarlo o búscala en el plano de mesas."

        /** D16 (Codex r3): esta terminal todavía tiene algo sin subir (o un cobro por revisar) de la cuenta cobrada. */
        const val ESPERA_A_QUE_SE_SUBA_PARA_ABRIR =
            "Esta cuenta todavía tiene algo sin sincronizar o por revisar: abre la cuenta aparte cuando se resuelva."

    }

    fun consumeNotice() {
        _notice.value = null
    }

    /**
     * Vacía el aviso que la pantalla acaba de mostrar, sólo si sigue siendo ése y no terminó otra cortesía mientras se
     * mostraba. Vaciar a ciegas se llevaba un aviso más nuevo sin mostrarlo: otro texto que llegó al final, o la 2.ª
     * cortesía negada con el mismo texto justo cuando vencía el 1.er aviso (D4, Codex r3).
     */
    fun consumeError(mostrado: String, cortesias: Int) {
        if (_compTerminadas.value == cortesias) _error.compareAndSet(mostrado, null)
    }

    fun exitToFloor() {
        pendingCart.clear()
        tableSession.clear()
    }

    // ══════════════════════════════════════════════════════════════════
    // Fase 1 (2026-08-03) — SPLIT_ORDER · COMP_ORDER · MOVE_ORDER
    // Las 3 acciones de esta pantalla que la auditoría de completitud
    // encontró sin UI (APPLY_DISCOUNT vive en TableCheckoutViewModel —
    // afecta el TOTAL de la cuenta, su lugar natural es "Cobrar").
    // ══════════════════════════════════════════════════════════════════

    private val _isSplitting = MutableStateFlow(false)
    val isSplitting: StateFlow<Boolean> = _isSplitting.asStateFlow()

    private val _isComping = MutableStateFlow(false)
    val isComping: StateFlow<Boolean> = _isComping.asStateFlow()

    /**
     * Sube cada vez que termina una cortesía (aplicada, negada, cancelada o con error). La hoja se cierra con esto y no con
     * la bajada de [isComping]: con el PIN de gerente apagado y ya guardado, la cortesía termina dentro del mismo toque,
     * true→false pasa antes de que Compose mire, y la hoja se quedaba abierta tapando el aviso (D4, N86, 10-nov).
     */
    private val _compTerminadas = MutableStateFlow(0)
    val compTerminadas: StateFlow<Int> = _compTerminadas.asStateFlow()

    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private val _availableTargetTables = MutableStateFlow<List<DiningTable>>(emptyList())
    val availableTargetTables: StateFlow<List<DiningTable>> = _availableTargetTables.asStateFlow()

    private val _isLoadingTargetTables = MutableStateFlow(false)
    val isLoadingTargetTables: StateFlow<Boolean> = _isLoadingTargetTables.asStateFlow()

    /**
     * 🔴 Misma clase de bug que [checkLoadFailed] (Mesa M1, `7df5819`), aquí en
     * el picker de "Mover mesa": [loadAvailableTargetTables] fallando (sin red,
     * timeout, 500) dejaba la lista en `emptyList()` SIN ninguna señal —
     * [MoveTableSheet][com.jaac.avoqado_tpv.features.tables.presentation.MoveTableSheet]
     * no tenía forma de distinguir "no hay mesas libres" de "no se pudo
     * verificar", así que un mesero sin señal veía "no hay mesas libres" y
     * concluía que el salón estaba lleno — un hecho plausible pero falso.
     *
     * A propósito NO se sirve una lista vieja aquí (a diferencia de
     * [activeStaffStale] más abajo): "mesa libre" es ocupación en tiempo real
     * — otro mesero pudo haber sentado ahí hace un minuto. Confirmar el
     * movimiento contra un dato caducado es el mismo riesgo de colisión que
     * documenta `offline-first-y-hub-lan.md` §3 para el hub LAN. Por eso
     * [loadAvailableTargetTables] BORRA la lista en cada fallo en vez de dejar
     * la del intento anterior — sin esto, reabrir el sheet una segunda vez sin
     * red mostraría mesas "libres" tal como estaban en el último éxito, no
     * como están ahora.
     */
    private val _targetTablesUnavailable = MutableStateFlow(false)
    val targetTablesUnavailable: StateFlow<Boolean> = _targetTablesUnavailable.asStateFlow()

    /** Bloqueo compartido — mismo mensaje que [sendRound] cuando la mesa es de otro mesero. */
    private fun blockedByOwnership(): Boolean {
        if (!readOnly.value) return false
        _error.value = "Mesa de ${lockOwnerName.value ?: "otro mesero"} — solo lectura"
        return true
    }

    /**
     * "Separar cuenta" — manda TODOS los [itemIds] seleccionados en UNA sola
     * llamada (todo-o-nada, ver KDoc de [TablesRepository.splitOrder]). Solo
     * alcanzable con el cheque cargado — [check] siempre trae un `orderId`
     * real en ese momento (ver KDoc de la clase).
     */
    fun splitOrder(itemIds: Set<String>) {
        if (blockedByOwnership()) return
        val session = tableSession.current() ?: run { _error.value = "No hay mesa activa"; return }
        if (itemIds.isEmpty()) {
            _error.value = "Selecciona al menos un artículo para separar"
            return
        }
        val vId = venueId ?: run { _error.value = "No hay venue configurado en este terminal"; return }
        if (_isSplitting.value) return

        _isSplitting.value = true
        viewModelScope.launch {
            repository.splitOrder(vId, session.orderId, itemIds.toList()).fold(
                onSuccess = { outcome ->
                    _isSplitting.value = false
                    _notice.value = if (outcome.queued) {
                        "Sin conexión — separación guardada; se sincronizará sola"
                    } else {
                        "Cuenta separada" + (outcome.createdOrderNumber?.let { " — nueva cuenta $it" } ?: "")
                    }
                    if (!outcome.queued) loadCheck()
                },
                onFailure = { e ->
                    _isSplitting.value = false
                    if (e is CancellationException) throw e
                    _error.value = (e as? BackendHttpException)?.message ?: "No se pudo separar la cuenta"
                },
            )
        }
    }

    /**
     * "Cortesía de toda la cuenta" — offline-capable (intent `COMP_ORDER`).
     * Ver KDoc de [TablesRepository.compOrder] para la asimetría con
     * [compItems].
     */
    fun compWholeOrder(reason: String) {
        compOrderInternal(itemIds = emptySet(), reason = reason)
    }

    /**
     * "Cortesía de artículo(s) puntual(es)" — SIEMPRE online (regla dura de
     * `offline-first-y-hub-lan.md` §5). Ver KDoc de [TablesRepository.compOrder].
     */
    fun compItems(itemIds: Set<String>, reason: String) {
        if (itemIds.isEmpty()) {
            _error.value = "Selecciona al menos un artículo para dar de cortesía"
            return
        }
        compOrderInternal(itemIds = itemIds, reason = reason)
    }

    private fun compOrderInternal(itemIds: Set<String>, reason: String) {
        if (blockedByOwnership()) return
        val session = tableSession.current() ?: run { _error.value = "No hay mesa activa"; return }
        if (reason.isBlank()) {
            _error.value = "Selecciona una razón para la cortesía"
            return
        }
        val vId = venueId ?: run { _error.value = "No hay venue configurado en este terminal"; return }
        val staffId = secureStorage.getStaffId()
        if (staffId == null) {
            _error.value = "No se pudo identificar al mesero en este terminal"
            return
        }
        if (_isComping.value) return

        _isComping.value = true
        viewModelScope.launch {
            try {
                val auth = autorizacionDeGerente.paraCortesia(vId, MOTIVO_PIN_CORTESIA)
                if (auth is Autorizacion.Cancelada) return@launch
                if (auth is Autorizacion.Negada) { _error.value = auth.texto; return@launch }
                var token = (auth as? Autorizacion.ConPin)?.token
                // Un 403 con token no es un permiso que falta: el servidor ya gastó el token en un primer envío
                // (OkHttp pudo reenviar el POST) y la cortesía pudo aplicarse. Se trata como "no confirmada".
                fun revisar(r: Result<TablesRepository.CompOutcome>, tokenEnviado: String?) =
                    if (tokenEnviado != null && r.exceptionOrNull() is PermisoRequeridoException) {
                        Result.failure(TablesRepository.NoConfirmadaException())
                    } else {
                        r
                    }
                var resultado = revisar(repository.compOrder(vId, session.orderId, itemIds.toList(), reason, staffId, token), token)
                // La lista de permisos local pudo estar vieja: un 403 por cortesía sin PIN pide el PIN y reintenta UNA vez.
                val e403 = resultado.exceptionOrNull() as? PermisoRequeridoException
                if (token == null && e403?.required == "orders:comp") {
                    val forzada = autorizacionDeGerente.paraCortesia(vId, MOTIVO_PIN_CORTESIA, forzar = true)
                    if (forzada is Autorizacion.Cancelada) return@launch
                    if (forzada is Autorizacion.Negada) { _error.value = forzada.texto; return@launch }
                    token = (forzada as? Autorizacion.ConPin)?.token
                    resultado = revisar(repository.compOrder(vId, session.orderId, itemIds.toList(), reason, staffId, token), token)
                }
                resultado.fold(
                    onSuccess = { outcome ->
                        _notice.value = if (outcome.queued) {
                            "Sin conexión — cortesía guardada; se sincronizará sola"
                        } else {
                            "Cortesía aplicada"
                        }
                        if (!outcome.queued) loadCheck()
                    },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        _error.value = when (e) {
                            is TablesRepository.ItemCompRequiresConnectionException -> e.message
                            is TablesRepository.NoConfirmadaException -> e.message
                            is PermisoRequeridoException -> e.message
                            is BackendHttpException -> e.message
                            else -> "No se pudo aplicar la cortesía"
                        }
                        if (e is TablesRepository.NoConfirmadaException) loadCheck()
                    },
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = "No se pudo aplicar la cortesía"
            } finally {
                _isComping.value = false
                _compTerminadas.update { it + 1 }
            }
        }
    }

    /**
     * Carga las mesas LIBRES del venue (excluyendo la actual) para el picker
     * de [com.jaac.avoqado_tpv.features.tables.presentation.MoveTableSheet].
     * Lectura pura — mismo criterio que [TablesViewModel.loadTables], sin
     * offline-first de plano completo (esta pantalla no mantiene un caché del
     * plano; un fallo simplemente deja la lista vacía con un error visible).
     */
    fun loadAvailableTargetTables() {
        val vId = venueId ?: return
        val currentTableId = tableSession.current()?.tableId
        viewModelScope.launch {
            _isLoadingTargetTables.value = true
            _targetTablesUnavailable.value = false
            repository.getTables(vId).fold(
                onSuccess = { tables ->
                    _availableTargetTables.value = tables.filter { it.isAvailable && it.id != currentTableId }
                    _isLoadingTargetTables.value = false
                },
                onFailure = { e ->
                    _isLoadingTargetTables.value = false
                    if (e is CancellationException) throw e
                    // Ver KDoc de [targetTablesUnavailable]: nunca se deja una
                    // lista vieja en pantalla — "libre" caduca rápido.
                    _availableTargetTables.value = emptyList()
                    _targetTablesUnavailable.value = true
                    _error.value = "No se pudo cargar la lista de mesas"
                },
            )
        }
    }

    /**
     * "Mover mesa" — SIEMPRE vía intent (ver KDoc de [TablesRepository.moveOrder]).
     * Al confirmar, la sesión activa se actualiza a la mesa DESTINO — el
     * mesero se queda en esta pantalla (misma cuenta, mesa nueva en el título)
     * en vez de navegar de regreso al plano, igual que Toast/Square.
     */
    fun moveOrder(target: DiningTable) {
        if (blockedByOwnership()) return
        val session = tableSession.current() ?: run { _error.value = "No hay mesa activa"; return }
        val vId = venueId ?: run { _error.value = "No hay venue configurado en este terminal"; return }
        if (_isMoving.value) return

        _isMoving.value = true
        viewModelScope.launch {
            repository.moveOrder(vId, session.orderId, session.isProvisional, target.id).fold(
                onSuccess = { outcome ->
                    _isMoving.value = false
                    // Sobre la sesión VIGENTE, no sobre la copia de antes de esperar: si el ack de OPEN_TABLE la promovió
                    // mientras tanto, la copia la regresaba al id local (y el carrito, ya del id real, se vaciaba). Si ya
                    // no es esta mesa (se salió), no se resucita.
                    tableSession.current()?.takeIf { it.tableId == session.tableId }?.let { actual ->
                        tableSession.start(
                            actual.copy(
                                tableId = target.id,
                                tableNumber = target.number,
                                areaName = target.areaName,
                                version = outcome.version ?: actual.version,
                            ),
                        )
                    }
                    _notice.value = if (outcome.queued) {
                        "Sin conexión — movimiento a ${etiquetaDeMesa(target.number)} guardado; se sincronizará solo"
                    } else {
                        "Cuenta movida a ${etiquetaDeMesa(target.number)}"
                    }
                },
                onFailure = { e ->
                    _isMoving.value = false
                    if (e is CancellationException) throw e
                    _error.value = (e as? TablesRepository.MoveOrderRejectedException)?.message ?: "No se pudo mover la mesa"
                },
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Fase 2 (2026-08-03) — MERGE_ORDERS · CANCEL_ORDER · ASSIGN_ORDER
    // ══════════════════════════════════════════════════════════════════

    private val _isMerging = MutableStateFlow(false)
    val isMerging: StateFlow<Boolean> = _isMerging.asStateFlow()

    private val _mergeCandidateTables = MutableStateFlow<List<DiningTable>>(emptyList())
    val mergeCandidateTables: StateFlow<List<DiningTable>> = _mergeCandidateTables.asStateFlow()

    private val _isLoadingMergeCandidates = MutableStateFlow(false)
    val isLoadingMergeCandidates: StateFlow<Boolean> = _isLoadingMergeCandidates.asStateFlow()

    /**
     * Misma clase de bug que [targetTablesUnavailable], aquí para "Fusionar
     * cuentas". El riesgo de servir una lista vieja es TODAVÍA mayor que en
     * "Mover mesa": una fila caducada puede apuntar a una cuenta que ya se
     * COBRÓ hace un minuto — fusionarla movería dinero contra un cheque
     * cerrado. Por eso [loadMergeCandidateTables] también borra la lista en
     * cada fallo, nunca sirve la del intento anterior.
     */
    private val _mergeCandidatesUnavailable = MutableStateFlow(false)
    val mergeCandidatesUnavailable: StateFlow<Boolean> = _mergeCandidatesUnavailable.asStateFlow()

    private val _isCancelling = MutableStateFlow(false)
    val isCancelling: StateFlow<Boolean> = _isCancelling.asStateFlow()

    /**
     * Señal de "la cuenta ya se canceló, sal al plano" — [cancelOrder] la
     * enciende DESPUÉS de limpiar [tableSession]/[pendingCart]. `TableOrderScreen`
     * ya tiene su propio guard `activeSession == null -> onNavigateBack()`
     * (ver su KDoc), pero ese guard depende de recomposición sobre
     * `tableSession.active` — esta señal es explícita para que el test de
     * ViewModel pueda verificar el efecto sin depender de Compose.
     */
    private val _cancelled = MutableStateFlow(false)
    val cancelled: StateFlow<Boolean> = _cancelled.asStateFlow()

    private val _isAssigning = MutableStateFlow(false)
    val isAssigning: StateFlow<Boolean> = _isAssigning.asStateFlow()

    private val _activeStaff = MutableStateFlow<List<ActiveStaffMember>>(emptyList())
    val activeStaff: StateFlow<List<ActiveStaffMember>> = _activeStaff.asStateFlow()

    private val _isLoadingActiveStaff = MutableStateFlow(false)
    val isLoadingActiveStaff: StateFlow<Boolean> = _isLoadingActiveStaff.asStateFlow()

    /**
     * A diferencia de [targetTablesUnavailable]/[mergeCandidatesUnavailable],
     * "Reasignar mesero" SÍ sirve la última lista conocida cuando un refresh
     * falla — el riesgo es mucho más bajo: reasignar a alguien que ya salió de
     * turno es corregible después (nadie perdió dinero), y `ASSIGN_ORDER` ya
     * es offline-capable en el server (KDoc de [assignOrder]). Bloquear al
     * mesero aquí solo por un blip de red sería más dañino que dejarlo elegir
     * de una lista con unos minutos de vida — mismo espíritu que el fix de
     * `PrintConfigRepository` (`offline-first-y-hub-lan.md` §4): "fail-safe"
     * NO puede significar "no muestres nada".
     *
     * `true` SOLO cuando ya había una lista de una carga anterior en esta
     * sesión y el refresh falló — [MergeOrdersSheet] la pinta con un aviso de
     * "puede no estar actualizada", nunca oculta las filas.
     */
    private val _activeStaffStale = MutableStateFlow(false)
    val activeStaffStale: StateFlow<Boolean> = _activeStaffStale.asStateFlow()

    /**
     * `true` cuando NUNCA hubo una carga exitosa en esta sesión (primer
     * intento sin red) — sin caché que servir, el picker debe decir
     * "no se pudo cargar", nunca "no hay personal en turno".
     */
    private val _activeStaffUnavailable = MutableStateFlow(false)
    val activeStaffUnavailable: StateFlow<Boolean> = _activeStaffUnavailable.asStateFlow()

    /**
     * Carga las mesas OCUPADAS del venue (excluyendo la actual) para el
     * picker de [com.jaac.avoqado_tpv.features.tables.presentation.MergeOrdersSheet]
     * — a diferencia de [loadAvailableTargetTables] (mesas LIBRES, para
     * "Mover"), aquí se necesita lo opuesto: mesas con una cuenta abierta
     * ([DiningTable.hasOpenCheck]) que se pueda absorber. Mismo criterio de
     * lectura pura sin caché que `loadAvailableTargetTables`.
     */
    fun loadMergeCandidateTables() {
        val vId = venueId ?: return
        val currentTableId = tableSession.current()?.tableId
        viewModelScope.launch {
            _isLoadingMergeCandidates.value = true
            _mergeCandidatesUnavailable.value = false
            repository.getTables(vId).fold(
                onSuccess = { tables ->
                    _mergeCandidateTables.value = tables.filter { it.hasOpenCheck && it.id != currentTableId }
                    _isLoadingMergeCandidates.value = false
                },
                onFailure = { e ->
                    _isLoadingMergeCandidates.value = false
                    if (e is CancellationException) throw e
                    // Ver KDoc de [mergeCandidatesUnavailable]: nunca se deja
                    // una lista vieja en pantalla — podría apuntar a una
                    // cuenta que ya se cobró.
                    _mergeCandidateTables.value = emptyList()
                    _mergeCandidatesUnavailable.value = true
                    _error.value = "No se pudo cargar la lista de mesas"
                },
            )
        }
    }

    /**
     * "Fusionar cuentas" — la cuenta de ESTA mesa absorbe la de [source].
     * Solo alcanzable con el cheque cargado ([check] real, ver KDoc de
     * [TablesRepository.mergeOrders]) — [source] siempre trae una cuenta real
     * también, porque viene de [loadMergeCandidateTables].
     */
    fun mergeOrders(source: DiningTable) {
        if (blockedByOwnership()) return
        val session = tableSession.current() ?: run { _error.value = "No hay mesa activa"; return }
        val sourceOrderId = source.primaryCheck?.id
        if (sourceOrderId == null) {
            _error.value = "${etiquetaDeMesa(source.number)} ya no tiene una cuenta abierta"
            return
        }
        val vId = venueId ?: run { _error.value = "No hay venue configurado en este terminal"; return }
        if (_isMerging.value) return

        _isMerging.value = true
        viewModelScope.launch {
            repository.mergeOrders(vId, session.orderId, sourceOrderId).fold(
                onSuccess = { outcome ->
                    _isMerging.value = false
                    _notice.value = if (outcome.queued) {
                        "Sin conexión — fusión guardada; se sincronizará sola"
                    } else {
                        "Cuenta de ${etiquetaDeMesa(source.number)} fusionada" + (outcome.mergedOrderNumber?.let { " ($it)" } ?: "")
                    }
                    if (!outcome.queued) loadCheck()
                },
                onFailure = { e ->
                    _isMerging.value = false
                    if (e is CancellationException) throw e
                    _error.value = (e as? BackendHttpException)?.message ?: "No se pudo fusionar la cuenta"
                },
            )
        }
    }

    /**
     * "Cancelar cuenta" — destructivo, confirmado por
     * [com.jaac.avoqado_tpv.features.tables.presentation.CancelOrderDialog]
     * ANTES de llegar aquí (esta función ya asume que el mesero confirmó).
     * Ver KDoc de [TablesRepository.cancelOrder] para el rechazo permanente
     * si la cuenta ya está pagada y la liberación de mesa del server.
     *
     * Funciona con la sesión provisional también (una mesa recién abierta
     * offline se puede cancelar sin haber sincronizado nunca) — mismo
     * criterio que [moveOrder].
     *
     * El rechazo puede llegar por DOS caminos desde el fix online-primero
     * (2026-08-07, ver KDoc del repositorio): [BackendHttpException] si la
     * ruta online rechazó directo (mismo patrón que [mergeOrders]), o
     * [TablesRepository.CancelOrderRejectedException] si vino de un ack
     * `REJECTED` reproducido offline (sesión provisional, o sin red en el
     * momento del intento).
     */
    fun cancelOrder(reason: String?) {
        if (blockedByOwnership()) return
        val session = tableSession.current() ?: run { _error.value = "No hay mesa activa"; return }
        val vId = venueId ?: run { _error.value = "No hay venue configurado en este terminal"; return }
        if (_isCancelling.value) return

        _isCancelling.value = true
        viewModelScope.launch {
            repository.cancelOrder(vId, session.orderId, session.isProvisional, reason).fold(
                onSuccess = { outcome ->
                    _isCancelling.value = false
                    _notice.value = if (outcome.queued) {
                        "Sin conexión — cancelación guardada; se sincronizará sola"
                    } else {
                        "Cuenta cancelada"
                    }
                    // La mesa ya se liberó (o se re-apuntó) del lado del server —
                    // limpiar la sesión local es lo que dispara la salida al
                    // plano (ver KDoc de [cancelled]).
                    pendingCart.clear()
                    tableSession.clear()
                    _cancelled.value = true
                },
                onFailure = { e ->
                    _isCancelling.value = false
                    if (e is CancellationException) throw e
                    _error.value = (e as? TablesRepository.CancelOrderRejectedException)?.message
                        ?: (e as? BackendHttpException)?.message
                        ?: "No se pudo cancelar la cuenta"
                },
            )
        }
    }

    /**
     * Carga el personal EN TURNO ahora mismo para el picker de
     * [com.jaac.avoqado_tpv.features.tables.presentation.AssignWaiterSheet].
     * Ver concern de permisos en KDoc de [TablesRepository.getActiveStaff]:
     * un WAITER puede fallar aquí con 403 aunque SÍ tenga permiso para
     * ejecutar la reasignación misma.
     */
    fun loadActiveStaff() {
        val vId = venueId ?: return
        viewModelScope.launch {
            _isLoadingActiveStaff.value = true
            _activeStaffStale.value = false
            _activeStaffUnavailable.value = false
            repository.getActiveStaff(vId).fold(
                onSuccess = { staff ->
                    _activeStaff.value = staff
                    _isLoadingActiveStaff.value = false
                },
                onFailure = { e ->
                    _isLoadingActiveStaff.value = false
                    if (e is CancellationException) throw e
                    // Ver KDoc de [activeStaffStale]/[activeStaffUnavailable]:
                    // con caché de una carga previa, se sigue sirviendo
                    // marcada como posiblemente vieja; sin caché, es
                    // "no disponible", nunca "no hay personal".
                    if (_activeStaff.value.isNotEmpty()) {
                        _activeStaffStale.value = true
                    } else {
                        _activeStaffUnavailable.value = true
                        _error.value = "No se pudo cargar el personal en turno"
                    }
                },
            )
        }
    }

    /**
     * "Reasignar mesero" — SIEMPRE vía intent (ver KDoc de
     * [TablesRepository.assignOrder]). [staff] siempre viene de una fila
     * tocada en [AssignWaiterSheet][com.jaac.avoqado_tpv.features.tables.presentation.AssignWaiterSheet],
     * nunca tecleado a mano — la atribución de propina/comisión de la
     * plataforma cuelga de este valor.
     */
    fun assignOrder(staff: ActiveStaffMember) {
        if (blockedByOwnership()) return
        val session = tableSession.current() ?: run { _error.value = "No hay mesa activa"; return }
        val vId = venueId ?: run { _error.value = "No hay venue configurado en este terminal"; return }
        if (_isAssigning.value) return

        _isAssigning.value = true
        viewModelScope.launch {
            repository.assignOrder(vId, session.orderId, session.isProvisional, staff.staffId).fold(
                onSuccess = { outcome ->
                    _isAssigning.value = false
                    _notice.value = if (outcome.queued) {
                        "Sin conexión — reasignación a ${staff.name} guardada; se sincronizará sola"
                    } else {
                        "Cuenta reasignada a ${staff.name}"
                    }
                    // Refresca `check.servedBy` — si la propiedad de mesa está
                    // encendida, quien reasignó puede quedar en solo-lectura
                    // de inmediato (mismo criterio que splitOrder/compOrder/applyDiscount).
                    if (!outcome.queued) loadCheck()
                },
                onFailure = { e ->
                    _isAssigning.value = false
                    if (e is CancellationException) throw e
                    _error.value = (e as? TablesRepository.AssignOrderRejectedException)?.message ?: "No se pudo reasignar la cuenta"
                },
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Fase 3 (2026-08-03) — SPLIT_BY_SEAT · UPDATE_DETAILS. Los últimos 2
    // intents que vivían aquí (APPLY_SERVICE_CHARGE quedó en
    // TableCheckoutViewModel — afecta el total, ver su KDoc).
    // ══════════════════════════════════════════════════════════════════

    private val _isSplittingBySeat = MutableStateFlow(false)
    val isSplittingBySeat: StateFlow<Boolean> = _isSplittingBySeat.asStateFlow()

    private val _isUpdatingDetails = MutableStateFlow(false)
    val isUpdatingDetails: StateFlow<Boolean> = _isUpdatingDetails.asStateFlow()

    /**
     * "Dividir por puesto" — todo-o-nada garantizado por el server (ver KDoc de
     * [TablesRepository.splitOrderBySeat]). Sin selección de artículos —
     * [com.jaac.avoqado_tpv.features.tables.presentation.SplitBySeatDialog] solo
     * pide confirmación, el server decide los grupos por `seat`.
     */
    fun splitOrderBySeat() {
        if (blockedByOwnership()) return
        val session = tableSession.current() ?: run { _error.value = "No hay mesa activa"; return }
        if (session.isProvisional) {
            _error.value = "La mesa aún no se sincroniza"
            return
        }
        val vId = venueId ?: run { _error.value = "No hay venue configurado en este terminal"; return }
        if (_isSplittingBySeat.value) return

        _isSplittingBySeat.value = true
        viewModelScope.launch {
            repository.splitOrderBySeat(vId, session.orderId).fold(
                onSuccess = { outcome ->
                    _isSplittingBySeat.value = false
                    _notice.value = if (outcome.queued) {
                        "Sin conexión — división guardada; se sincronizará sola"
                    } else {
                        "Cuenta dividida por puesto" + (outcome.createdCount?.let { " — $it cuenta(s) nueva(s)" } ?: "")
                    }
                    if (!outcome.queued) loadCheck()
                },
                onFailure = { e ->
                    _isSplittingBySeat.value = false
                    if (e is CancellationException) throw e
                    _error.value = (e as? BackendHttpException)?.message ?: "No se pudo dividir por puesto"
                },
            )
        }
    }

    /**
     * "Editar detalles" (comensales, nombre en el cheque) — SIEMPRE vía intent
     * (ver KDoc de [TablesRepository.updateOrderDetails]). Funciona con sesión
     * provisional también, mismo criterio que [moveOrder]/[cancelOrder].
     */
    fun updateOrderDetails(name: String?, covers: Int?) {
        if (blockedByOwnership()) return
        val session = tableSession.current() ?: run { _error.value = "No hay mesa activa"; return }
        val vId = venueId ?: run { _error.value = "No hay venue configurado en este terminal"; return }
        if (_isUpdatingDetails.value) return

        _isUpdatingDetails.value = true
        viewModelScope.launch {
            repository.updateOrderDetails(vId, session.orderId, session.isProvisional, name, covers).fold(
                onSuccess = { outcome ->
                    _isUpdatingDetails.value = false
                    _notice.value = if (outcome.queued) {
                        "Sin conexión — detalles guardados; se sincronizarán solos"
                    } else {
                        "Detalles actualizados"
                    }
                    if (!outcome.queued) loadCheck()
                },
                onFailure = { e ->
                    _isUpdatingDetails.value = false
                    if (e is CancellationException) throw e
                    _error.value = (e as? TablesRepository.UpdateDetailsRejectedException)?.message ?: "No se pudieron actualizar los detalles"
                },
            )
        }
    }
}

/** D16: la ronda de esta terminal que cayó en una cuenta aparte de la mesa, ya con su texto para la mesera. */
data class AvisoDeCuentaAparte(val orderId: String, val texto: String)

/**
 * D16: el texto del aviso: lo que pasó cuando llegó la ronda (lo dice el ACK y ya no cambia), no el estado de ahora de la cuenta
 * aparte. [monto] es el total de esa cuenta en el ACK (un reintento no lo trae); sin [mesa], «esta mesa».
 */
internal fun textoDeCuentaAparte(mesa: String?, monto: java.math.BigDecimal?): String {
    val pesos = monto?.let { " por $" + it.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() } ?: ""
    return "Tu ronda no se perdió: como esta cuenta ya estaba cobrada, quedó en una cuenta aparte de ${mesa?.let { "la $it" } ?: "esta mesa"}$pesos."
}

/** Una ronda ya enviada — todas las líneas que comparten el mismo `sentToKitchenAt`. */
data class KitchenRound(val sentAt: String?, val items: List<OrderDetailItem>)

/**
 * Agrupa `check.items` por ronda (`sentToKitchenAt`, compartido por todas las
 * filas de un mismo "Enviar" — ver KDoc de [OrderDetailItem.sentToKitchenAt]),
 * más reciente primero. Un `null` (línea sin timestamp — no debería pasar en
 * datos nuevos, pero un server viejo o una migración podría no tenerlo) cae en
 * su propio grupo al final, nunca se pierde silenciosamente.
 */
fun OrderDetail.roundsSentToKitchen(): List<KitchenRound> = items
    .groupBy { it.sentToKitchenAt }
    .map { (sentAt, lines) -> KitchenRound(sentAt, lines) }
    .sortedWith(compareByDescending { it.sentAt ?: "" })

/** ¿El fallo es de la red (sin conexión, servidor caído o saturado) y no un rechazo del SERVIDOR? Lo usan la cuenta y el cobro de Mesas. */
internal fun esFallaDeRedEnMesas(e: Throwable): Boolean = when (e) {
    is IOException -> true
    is BackendHttpException -> e.statusCode in setOf(502, 503, 504, 408, 429)
    else -> false
}
