package com.jaac.avoqado_tpv.features.tables.data

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** D86: la mesa tiene en la cola algo que no es abrir, pedir o cobrar (mover, cancelar, descuento…) o un rechazo. */
internal const val CAMBIOS_SIN_SINCRONIZAR = "Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen."

/** D86: falta algo para saber el saldo (la apertura, el valor de una ronda o un importe legible). */
internal const val SALDO_NO_RECUPERADO =
    "No se pudo saber el saldo de esta mesa sin conexión: cóbrala cuando vuelva la conexión. Lo pedido sigue guardado."

/** D86: la libreta y la cola no coinciden por un instante (una ronda acaba de subir o se descartó). */
internal const val CUENTA_SE_ACTUALIZA = "La cuenta se está actualizando. Intenta en un momento."

/** D86: «Cobrar» de una mesa del SERVIDOR que no pudo leer su cuenta (fuera del piloto `MESAS_SIN_RED`, como ANDROID). */
internal const val SIN_CUENTA_SIN_RED = "Sin conexión: esta mesa se cobra cuando vuelva la conexión."

/**
 * D86 (Codex r0 #1, como el D45 de ANDROID): un cobro en efectivo de ESTA terminal para esta cuenta sigue en la cola y el
 * SERVIDOR todavía no lo cuenta: su saldo es de antes de ese cobro.
 */
internal const val MESA_COBRO_SIN_SINCRONIZAR = "Esta mesa tiene un cobro sin sincronizar: cóbrala cuando se sincronice."

/** D86: aviso de «Cobrar» en una mesa que esta terminal abrió sin red. */
internal const val COBRO_CON_LO_DE_ESTA_TERMINAL =
    "Sin conexión: se cobra con lo que esta terminal le mandó a la mesa; se sube solo al volver la conexión."

/** D86: el saldo de una cuenta que esta terminal abrió sin red. */
sealed interface SaldoSinRed {
    /** [rondas] = lo que esta terminal le mandó (al precio al mandarlo); [cobrado] = el efectivo que ya encoló (sin propina). */
    data class Conocido(val rondas: BigDecimal, val cobrado: BigDecimal) : SaldoSinRed {
        val porPagar: BigDecimal get() = (rondas - cobrado).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
    }

    data class EnGris(val motivo: String) : SaldoSinRed
}

/**
 * D86 (encargo 3af794, como el B2a de ANDROID, `SaldoSinRed.kt`): el saldo de una cuenta que ESTA terminal abrió sin red,
 * calculado de la cola (en disco) y del valor de cada ronda al mandarla ([rondas], en memoria, por id del ADD_ITEMS). Es el
 * ÚNICO número con el que se cobra una mesa provisional. Todo lo que no se puede asegurar queda en gris con su razón:
 *
 * - sin su OPEN_TABLE PENDING (sin él, el SERVIDOR ya pudo conocerla, o la app murió a media apertura);
 * - cualquier fila de la cuenta que no sea OPEN_TABLE/ADD_ITEMS/PAY_CASH PENDING (mover, cancelar, descuento, liberar, un
 *   rechazo…): la cuenta ya no es la suma de lo que le mandó esta terminal;
 * - un ADD_ITEMS sin su valor (la app se reinició: la libreta murió con el proceso) o un valor sin su ADD_ITEMS PENDING;
 * - un PAY_CASH con un importe ilegible.
 *
 * [filas]: las vivas de la cola que mencionan la cuenta (filtro grueso); aquí se queda sólo con las que la NOMBRAN.
 */
internal fun saldoDeCuentaProvisional(cuenta: String, filas: List<SyncIntentEntity>, rondas: Map<String, BigDecimal>): SaldoSinRed {
    val deLaCuenta = filas.mapNotNull { fila -> parsear(fila.payloadJson)?.takeIf { nombra(it, cuenta) }?.let { fila to it } }
    val enCamino = deLaCuenta.filter { (fila, _) -> fila.status == SyncIntentEntity.STATUS_PENDING }
    val permitidos = setOf(SyncIntentTypes.OPEN_TABLE, SyncIntentTypes.ADD_ITEMS, SyncIntentTypes.PAY_CASH)
    if (deLaCuenta.any { (fila, _) -> fila.status != SyncIntentEntity.STATUS_PENDING || fila.type !in permitidos }) {
        return SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR)
    }
    if (enCamino.none { (fila, _) -> fila.type == SyncIntentTypes.OPEN_TABLE }) return SaldoSinRed.EnGris(SALDO_NO_RECUPERADO)
    val rondasEnCola = enCamino.filter { (fila, _) -> fila.type == SyncIntentTypes.ADD_ITEMS }.map { (fila, _) -> fila.id }.toSet()
    if (!rondas.keys.containsAll(rondasEnCola)) return SaldoSinRed.EnGris(SALDO_NO_RECUPERADO)
    if (!rondasEnCola.containsAll(rondas.keys)) return SaldoSinRed.EnGris(CUENTA_SE_ACTUALIZA)
    var cobradoCentavos = 0L
    for ((fila, payload) in enCamino) {
        if (fila.type != SyncIntentTypes.PAY_CASH) continue
        val importe = payload.asJsonObject.get("amountCents")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asBigDecimal.longValueExact() }.getOrNull() }
            ?.takeIf { it >= 0 }
            ?: return SaldoSinRed.EnGris(SALDO_NO_RECUPERADO)
        cobradoCentavos = Math.addExact(cobradoCentavos, importe)
    }
    return SaldoSinRed.Conocido(
        rondas = rondas.values.fold(BigDecimal.ZERO) { total, valor -> total + valor }.setScale(2, RoundingMode.HALF_UP),
        cobrado = BigDecimal.valueOf(cobradoCentavos, 2),
    )
}

/**
 * D86 (Codex r0 #1): una cuenta del SERVIDOR que esta terminal todavía tiene cosas sin subir (con su id real o, si se abrió sin
 * red, con su id local [cuentas]). El saldo que acaba de leer el SERVIDOR no las cuenta: un cobro propio sin subir haría
 * cobrar de más y una ronda propia sin subir, de menos. Sólo PENDING: un rechazo ya no va a llegar y el SERVIDOR manda.
 * `null` = nada propio sin subir.
 */
internal fun pendienteDeSubirEn(cuentas: Collection<String>, filas: List<SyncIntentEntity>): String? {
    val propias = filas.filter { fila ->
        fila.status == SyncIntentEntity.STATUS_PENDING &&
            parsear(fila.payloadJson)?.let { payload -> cuentas.any { nombra(payload, it) } } == true
    }
    return when {
        propias.isEmpty() -> null
        propias.any { it.type == SyncIntentTypes.PAY_CASH } -> MESA_COBRO_SIN_SINCRONIZAR
        else -> CAMBIOS_SIN_SINCRONIZAR
    }
}

/** D86: la apertura sin red de una cuenta ya confirmada por el SERVIDOR (lo que trae su ACK). */
data class AperturaConfirmada(val orderId: String, val orderNumber: String?, val version: Int?)

/** D86: de las filas ACKED de OPEN_TABLE, la de [cuenta] con su id real (del `result` del ACK); `null` si no hay. */
internal fun aperturaConfirmadaDe(cuenta: String, filas: List<SyncIntentEntity>): AperturaConfirmada? =
    filas.asSequence()
        .filter { it.type == SyncIntentTypes.OPEN_TABLE && it.status == SyncIntentEntity.STATUS_ACKED }
        .filter { fila -> parsear(fila.payloadJson)?.asJsonObject?.get("localOrderId")?.takeIf { it.isJsonPrimitive }?.asString == cuenta }
        .mapNotNull { fila ->
            val result = fila.resultJson?.let(::parsear)?.asJsonObject ?: return@mapNotNull null
            val orderId = result.get("orderId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AperturaConfirmada(
                orderId = orderId,
                orderNumber = result.get("orderNumber")?.takeIf { it.isJsonPrimitive }?.asString,
                version = result.get("version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt,
            )
        }
        .firstOrNull()

private fun parsear(json: String): JsonElement? = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }

/** ¿Algún texto del payload (a cualquier profundidad) es exactamente [cuenta]? */
private fun nombra(elemento: JsonElement, cuenta: String): Boolean = when {
    elemento.isJsonPrimitive -> elemento.asJsonPrimitive.isString && elemento.asString == cuenta
    elemento.isJsonObject -> elemento.asJsonObject.entrySet().any { nombra(it.value, cuenta) }
    elemento.isJsonArray -> elemento.asJsonArray.any { nombra(it, cuenta) }
    else -> false
}

/**
 * D86: dueño del saldo sin red de una cuenta que esta terminal abrió sin red. La [Libreta] guarda en memoria el valor de cada
 * ronda al mandarla (la cola no trae precios); el saldo se recalcula de la cola cada vez, nunca se acumula.
 */
@Singleton
class SaldoDeMesaSinRed @Inject constructor(private val syncOutbox: SyncOutbox) {

    /** Valor de cada ronda mandada sin red, por cuenta provisional y por id del ADD_ITEMS. Muere con el proceso a propósito. */
    class Libreta {
        private val porCuenta = ConcurrentHashMap<String, Map<String, BigDecimal>>()

        fun anotarRonda(cuenta: String, intentId: String, valor: BigDecimal) {
            porCuenta.compute(cuenta) { _, rondas -> (rondas ?: emptyMap()) + (intentId to valor) }
        }

        fun rondasDe(cuenta: String): Map<String, BigDecimal> = porCuenta[cuenta] ?: emptyMap()

        fun olvidar(cuenta: String) {
            porCuenta.remove(cuenta)
        }
    }

    val libreta = Libreta()

    suspend fun saldo(venueId: String, cuenta: String): SaldoSinRed =
        saldoDeCuentaProvisional(cuenta, syncOutbox.vivasQueMencionan(venueId, cuenta), libreta.rondasDe(cuenta))

    /**
     * D86 (Codex r0 #2): el ACK de la apertura pudo llegar por un camino que no avisa a la sesión (el cobro en efectivo, mover,
     * reasignar o editar reproducen la cola con su propio aviso). La cola en disco sí lo guardó: de ahí se promueve.
     */
    suspend fun aperturaConfirmada(venueId: String, cuenta: String): AperturaConfirmada? =
        aperturaConfirmadaDe(cuenta, syncOutbox.aperturasConfirmadasQueMencionan(venueId, cuenta))

    /** D86 (Codex r0 #1): ¿esta terminal tiene algo sin subir de esta cuenta del SERVIDOR? Ver [pendienteDeSubir]. */
    suspend fun pendienteDeSubir(venueId: String, cuentas: List<String>): String? =
        pendienteDeSubirEn(cuentas, cuentas.flatMap { syncOutbox.vivasQueMencionan(venueId, it) }.distinctBy { it.id })
}
