package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Un rechazo, ya traducido a algo que un mesero puede leer y accionar — nunca
 * el `errorCode`/stack trace crudo del server (ver KDoc de
 * [resolutionHintFor]).
 */
data class QuarantineItem(
    val id: String,
    val type: String,
    val label: String,
    val resolutionHint: String,
    val message: String?,
    val createdAt: Long,
)

data class QuarantineUiState(
    val isLoading: Boolean = false,
    val items: List<QuarantineItem> = emptyList(),
)

/**
 * Cuarentena visible (Plan C, Task 9) — la mitad de la regla de oro offline
 * que faltaba: un `REJECTED` (rechazo de NEGOCIO permanente) tiene que ser
 * VISIBLE para un humano, nunca solo un contador que nadie mira. Puerto
 * deliberado de `avoqado-android/sync/presentation/QuarantineViewModel.kt`
 * (`describeType`/`resolutionHint`), recortado a lo que Mesas tiene hoy:
 * solo [SyncOutbox] — sin cola de pagos ni de reservas (no existen en este
 * módulo) — y sin gate de rol (`RoleManager` de android no tiene equivalente
 * en la TPV todavía; cualquier staff en esta terminal puede descartar).
 *
 * 🔴 NO confundir `REJECTED` con `RETRY` — ver
 * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.2. Esta
 * pantalla SOLO lee [SyncOutbox.rejectedIntents], que en Room es
 * `status = 'REJECTED'` (ver
 * [com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentDao.rejectedForVenue]).
 * Un intent en `RETRY` (hoy solo `VERSION_CONFLICT`) NUNCA aparece aquí — se
 * queda `PENDING` y el próximo replay lo reintenta solo; convertirlo en
 * `REJECTED` lo perdería para siempre (fue un P1 real). Un tipo que el
 * server no reconoce SÍ cae en `REJECTED` con `errorCode = UNKNOWN_INTENT_TYPE`
 * (`sync.mobile.service.ts`) — también termina visible aquí, nunca perdido
 * en silencio.
 */
@HiltViewModel
class QuarantineViewModel @Inject constructor(
    private val outbox: SyncOutbox,
    private val deviceInfoManager: DeviceInfoManager,
) : ViewModel() {

    private val _state = MutableStateFlow(QuarantineUiState())
    val state: StateFlow<QuarantineUiState> = _state.asStateFlow()

    private var venueId: String? = null

    /**
     * Carga explícita por venue. El id se guarda internamente para que
     * [dismiss] no lo vuelva a necesitar como parámetro.
     */
    fun load(venueId: String) {
        this.venueId = venueId
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val rejected = outbox.rejectedIntents(venueId)
            _state.update {
                QuarantineUiState(
                    isLoading = false,
                    items = rejected.map { intent -> intent.toUiItem() },
                )
            }
        }
    }

    /**
     * Conveniencia para [QuarantineSheet]: resuelve el venue de esta
     * terminal por su cuenta, para que la pantalla no lo tenga que conocer
     * (mismo criterio que `TablesViewModel`/`TableCheckoutViewModel` con
     * `deviceInfoManager.getVenueId()`).
     */
    fun load() {
        deviceInfoManager.getVenueId()?.let { load(it) }
    }

    /**
     * El mesero/gerente ya resolvió el rechazo a mano (recobró el efectivo,
     * repitió la acción…) y lo descarta. Recarga después de descartar — la
     * lista siempre refleja lo que Room tiene, nunca un borrado optimista
     * que se pueda desincronizar si [SyncOutbox.dismissRejected] no
     * encuentra la fila (p.ej. otro dispositivo ya la descartó).
     */
    fun dismiss(id: String) {
        val vId = venueId ?: return
        viewModelScope.launch {
            outbox.dismissRejected(vId, id)
            load(vId)
        }
    }

    private fun SyncOutbox.RejectedIntent.toUiItem() = QuarantineItem(
        id = id,
        type = type,
        label = labelFor(type),
        resolutionHint = resolutionHintFor(errorCode, type),
        message = message,
        createdAt = createdAt,
    )
}

/**
 * Texto humano por tipo de intent — un mesero no puede accionar sobre el
 * string crudo `"ADD_ITEMS"`. Mismas 14 etiquetas que
 * `avoqado-android`'s `describeType` donde el tipo existe en ambos clientes.
 * Un tipo NO reconocido (server más nuevo que esta build) nunca se esconde
 * detrás de un texto genérico — se muestra tal cual entre paréntesis para
 * que sea reportable.
 */
fun labelFor(type: String): String = when (type) {
    SyncIntentTypes.OPEN_TABLE -> "Abrir mesa"
    SyncIntentTypes.ADD_ITEMS -> "Enviar ronda"
    SyncIntentTypes.PAY_CASH -> "Cobro en efectivo"
    SyncIntentTypes.APPLY_DISCOUNT -> "Aplicar descuento"
    SyncIntentTypes.APPLY_SERVICE_CHARGE -> "Cargo por servicio"
    SyncIntentTypes.COMP_ORDER -> "Cortesía de cuenta"
    SyncIntentTypes.UPDATE_DETAILS -> "Editar detalles"
    SyncIntentTypes.CANCEL_ORDER -> "Anular cuenta"
    SyncIntentTypes.MOVE_ORDER -> "Mover mesa"
    SyncIntentTypes.ASSIGN_ORDER -> "Asignar mesero"
    SyncIntentTypes.CLEAR_TABLE -> "Liberar mesa"
    SyncIntentTypes.SPLIT_ORDER -> "Dividir cuenta"
    SyncIntentTypes.SPLIT_BY_SEAT -> "Dividir por puesto"
    SyncIntentTypes.MERGE_ORDERS -> "Fusionar cuentas"
    else -> "Acción sin reconocer ($type)"
}

/**
 * Guía de resolución en español ACCIONABLE — nunca el `errorCode` crudo ni
 * un stack trace (la app ya repite ese error en otro lado con el
 * procesador de pagos; esta pantalla no debe sumarse a esa lista). Espejo de
 * `avoqado-android`'s `resolutionHint`, acotado a los `errorCode` que el
 * reducer realmente emite como `REJECTED`
 * (`avoqado-server/src/services/mobile/sync.mobile.service.ts`, confirmado
 * 2026-08-03): `ACTOR_MISMATCH`, `FEATURE_LOCKED`, `INVALID_PAYLOAD`,
 * `ITEMS_NOT_RESOLVED`, `ORDER_NOT_FOUND`, `OUTCOME_UNKNOWN`,
 * `PERMISSION_DENIED`, `STALE_DEVICE_SEQUENCE`, `TABLE_OWNED_BY_OTHER`,
 * `UNKNOWN_INTENT_TYPE`. `VERSION_CONFLICT`/`INTENT_IN_PROGRESS` NUNCA
 * llegan aquí — son `RETRY`, no `REJECTED` (ver KDoc de la clase).
 */
fun resolutionHintFor(errorCode: String?, type: String): String = when (errorCode) {
    "TABLE_OWNED_BY_OTHER" -> "Otro mesero ya tenía esta mesa abierta. Revisa con él antes de repetir la acción."
    "STALE_DEVICE_SEQUENCE" -> "Otro dispositivo ya mandó una acción más reciente sobre esta cuenta. Revisa el estado actual antes de repetir."
    "ACTOR_MISMATCH" -> "La cuenta cambió de dueño antes de sincronizar. Revisa quién la atiende ahora."
    "PERMISSION_DENIED" -> "Tu usuario no tiene permiso para esta acción — pide a un gerente que la haga."
    "ORDER_NOT_FOUND" -> "La cuenta ya no existe (probablemente ya se cerró). No requiere ninguna acción."
    "ITEMS_NOT_RESOLVED" -> "No se pudieron ubicar todos los productos al dividir la cuenta. Revisa el cheque dividido a mano."
    "INVALID_PAYLOAD" -> "Faltó información para completar la acción. Repítela desde la terminal."
    "FEATURE_LOCKED" -> "El plan de este local no tiene esta función activa. Contacta a administración."
    "OUTCOME_UNKNOWN" -> "El servidor no confirmó si se aplicó. Revisa la cuenta y la caja antes de repetir la acción."
    "UNKNOWN_INTENT_TYPE" -> "Esta versión de la app no reconoce esta acción. Actualiza la terminal."
    else -> if (type == SyncIntentTypes.PAY_CASH) {
        "El cobro no se registró. El efectivo sigue en caja: vuelve a cobrarlo desde la terminal y luego márcalo como resuelto."
    } else {
        "El servidor rechazó esta acción. Revísala y repítela a mano si sigue aplicando."
    }
}
