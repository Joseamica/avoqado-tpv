package com.jaac.avoqado_campo.copia

import com.google.gson.JsonParser
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.cola.normalizarSerie
import com.jaac.avoqado_campo.cola.simsDeCustodia
import com.jaac.avoqado_campo.red.MiSimDto
import com.jaac.avoqado_campo.red.RespuestaModulos

/**
 * Lo que el celular necesita para vender sin señal: las SIMs de la persona y la configuración de la sucursal. Es de UNA
 * activación ([generacion]), de UNA sucursal y de UNA versión de la app; si no coincide, se tira (R7-1).
 */
data class Copia(
    val versionApp: Int,
    val venueId: String,
    val generacion: String,
    val sims: List<SimEnCopia>,
    val config: ConfigDeSucursal,
    val bajadaAt: Long,
    /**
     * D1: las piezas de SIM (id) que ya habían llegado al SERVIDOR ANTES de pedir esta lista: ésas ya vienen en ella. Lo que
     * llegó después o sigue en la cola manda sobre la lista ([loDelCelular]). null (copia de antes de D1) = no se sabe: manda
     * la lista en todo lo que ya llegó.
     */
    val yaLlegadas: List<Long>? = null,
)

data class SimEnCopia(
    val serialNumber: String,
    val custodyState: String,
    val custodyVersion: Int,
    val categoria: String,
    val precio: String?, /* category.suggestedPrice: null = el promotor escribe el importe (B7) */
    val localmente: String?, /* MarcaLocal.* */
    // R8-3: «Mis SIMs» muestra «Revisar» con los motivos (FAILED) o «En revisión» (PENDING).
    val verificationStatus: String?,
    val verificationId: String?,
    val paymentId: String?,
    val rejectionReasons: List<String>,
    val reviewNotes: String?,
)

/** Lo que el celular ya hizo con una SIM y todavía no sube (vive mientras su pieza siga por subir). */
object MarcaLocal {
    const val VENDIDA_EN_EL_CELULAR = "VENDIDA_EN_EL_CELULAR"
    const val ACEPTADA_EN_EL_CELULAR = "ACEPTADA_EN_EL_CELULAR"
    const val RECHAZADA_EN_EL_CELULAR = "RECHAZADA_EN_EL_CELULAR"
}

/** De `SERIALIZED_INVENTORY` y su `attendance` (B2). [modulo] = false: «Las ventas de SIM no están activas en esta sucursal». */
data class ConfigDeSucursal(
    val etiquetaArticulo: String,
    val etiquetaCodigo: String,
    val portabilidad: Boolean,
    val numeroDeVinculacion: Boolean,
    val fotoEntrada: Boolean,
    val gpsEntrada: Boolean,
    val fotoSalida: Boolean,
    val gpsSalida: Boolean,
    val modulo: Boolean,
)

const val MODULO_DE_SIMS = "SERIALIZED_INVENTORY"

fun configDe(r: RespuestaModulos): ConfigDeSucursal {
    val c = r.modules?.firstOrNull { it?.code == MODULO_DE_SIMS }?.config
    val modulo = r.modules?.any { it?.code == MODULO_DE_SIMS } == true
    return ConfigDeSucursal(
        etiquetaArticulo = c?.labels?.item ?: "SIM",
        etiquetaCodigo = c?.labels?.barcode ?: "ICCID",
        portabilidad = c?.features?.enablePortabilidad == true,
        numeroDeVinculacion = c?.features?.linkingNumber == true,
        fotoEntrada = c?.attendance?.requireClockInPhoto == true,
        gpsEntrada = c?.attendance?.requireClockInGps == true,
        fotoSalida = c?.attendance?.requireClockOutPhoto == true,
        gpsSalida = c?.attendance?.requireClockOutGps == true,
        modulo = modulo,
    )
}

/** Sólo sobre un `MisSimsDto` que pasó `valida()` (serie y estado no nulos). */
fun MiSimDto.aSim(localmente: String?) = SimEnCopia(
    serialNumber = serialNumber!!, custodyState = custodyState!!, custodyVersion = custodyVersion ?: 0,
    categoria = category?.name.orEmpty(), precio = category?.suggestedPrice, localmente = localmente,
    verificationStatus = verificationStatus, verificationId = verificationId, paymentId = paymentId,
    rejectionReasons = rejectionReasons.orEmpty(), reviewNotes = reviewNotes,
)

/** D1 (Codex r3): lo último que ESTE celular hizo con una SIM y que la lista todavía no trae. */
enum class LoDelCelular {
    VENTA_POR_SUBIR, VENTA_NO_SUBIO, VENDIDA, ACEPTADA_POR_SUBIR, RECHAZADA_POR_SUBIR, ACEPTADA, RECHAZADA;

    val esVenta get() = this == VENTA_POR_SUBIR || this == VENTA_NO_SUBIO || this == VENDIDA
}

/** HECHA o AVISO, o DESCARTADA con la respuesta del SERVIDOR (el «Entendido» de un AVISO la conserva). */
fun llegoAlServidor(p: Pieza) = p.estado == EstadoPieza.HECHA || p.estado == EstadoPieza.AVISO ||
    (p.estado == EstadoPieza.DESCARTADA && p.resultadoJson != null)

/**
 * D1: serie normalizada → lo último que el celular hizo con esa SIM, si la lista [c] todavía no lo trae. [piezas]: las ventas
 * y custodias de la cola, también las terminadas (7 días). De cada tipo manda la última; la venta gana a la custodia. No
 * cuentan lo que llegó al SERVIDOR antes de pedir la lista (`yaLlegadas`: ya viene en ella) ni lo DESCARTADO sin respuesta
 * (Quitar, gracia descartada, la venta vieja de «corregir precio»: nunca existió). Sin ninguna pieza de la SIM (ya se purgó)
 * queda la marca de la copia. `SOLD` en la lista lo decide quien llama: es del SERVIDOR.
 */
fun loDelCelular(c: Copia, piezas: List<Pieza>): Map<String, LoDelCelular> {
    // Codex misventas r1 #2: una copia de antes de D1 no sabe qué ya venía en ella: manda la lista (como la 0.5.0).
    val vistas = c.yaLlegadas?.toSet()
    fun yaVenia(p: Pieza) = vistas?.contains(p.id) ?: true
    val enLaLista = c.sims.associateBy { normalizarSerie(it.serialNumber) }
    val ventas = mutableMapOf<String, Pieza>()
    val custodias = mutableMapOf<String, Pair<Pieza, Int>>()
    for (p in piezas.sortedBy { it.id }) when (p.tipo) {
        TipoPieza.VENTA -> serieDeVenta(p.cuerpoJson)?.let { ventas[it] = p }
        TipoPieza.CUSTODIA_ACEPTAR, TipoPieza.CUSTODIA_RECHAZAR ->
            simsDeCustodia(p.cuerpoJson).forEach { custodias[it.serialNumber] = p to it.custodyVersion }
    }
    val r = mutableMapOf<String, LoDelCelular>()
    for (s in c.sims) {
        val serie = normalizarSerie(s.serialNumber)
        if (serie in ventas || serie in custodias) continue
        r[serie] = when (s.localmente) {
            MarcaLocal.VENDIDA_EN_EL_CELULAR -> LoDelCelular.VENDIDA
            MarcaLocal.ACEPTADA_EN_EL_CELULAR -> LoDelCelular.ACEPTADA_POR_SUBIR
            MarcaLocal.RECHAZADA_EN_EL_CELULAR -> LoDelCelular.RECHAZADA_POR_SUBIR
            else -> continue
        }
    }
    for ((serie, kv) in custodias) {
        val (k, version) = kv
        val acepta = k.tipo == TipoPieza.CUSTODIA_ACEPTAR
        val fallidas = seriesDe(k.rechazoDatosJson, "fallidas")
        r[serie] = when {
            k.estado in POR_SUBIR -> if (acepta) LoDelCelular.ACEPTADA_POR_SUBIR else LoDelCelular.RECHAZADA_POR_SUBIR
            !llegoAlServidor(k) || yaVenia(k) -> continue
            // r1 #2: la lista trae OTRA asignación de esa SIM (la recogieron y se la volvieron a dar): esta decisión ya no es de ella.
            enLaLista[serie]?.custodyVersion != version -> continue
            // Un AVISO de aceptar: a las `fallidas` no se las aceptaron (lo que sigue lo dice la lista o «Te volvieron a
            // asignar»); las `faltan` no se pudieron confirmar y siguen como antes, «por subir». r1 #1: «en parte» sin saber
            // cuáles (así lo guardaba la gracia antes): ninguna se da por aceptada.
            serie in fallidas -> continue
            serie in seriesDe(k.rechazoDatosJson, "faltan") || (k.rechazoCode == EN_PARTE && fallidas.isEmpty()) ->
                LoDelCelular.ACEPTADA_POR_SUBIR
            acepta -> LoDelCelular.ACEPTADA
            else -> LoDelCelular.RECHAZADA
        }
    }
    for ((serie, v) in ventas) {
        r[serie] = when {
            v.estado == EstadoPieza.PENDIENTE || v.estado == EstadoPieza.RETENIDA -> LoDelCelular.VENTA_POR_SUBIR
            v.estado == EstadoPieza.RECHAZADA -> LoDelCelular.VENTA_NO_SUBIO
            llegoAlServidor(v) && !yaVenia(v) -> LoDelCelular.VENDIDA
            else -> continue
        }
    }
    return r
}

private val POR_SUBIR = setOf(EstadoPieza.PENDIENTE, EstadoPieza.RETENIDA, EstadoPieza.RECHAZADA)
private const val EN_PARTE = "CUSTODIA_EN_PARTE"

private fun serieDeVenta(cuerpo: String?): String? = runCatching {
    JsonParser.parseString(cuerpo).asJsonObject["serialNumber"]?.asString?.let(::normalizarSerie)
}.getOrNull()

/** Las series de `fallidas` (objetos con `serialNumber`) o de `faltan` (textos) de los datos de un AVISO de custodia. */
private fun seriesDe(datos: String?, lista: String): Set<String> = runCatching {
    JsonParser.parseString(datos).asJsonObject[lista].asJsonArray.mapNotNull { e ->
        (if (e.isJsonObject) e.asJsonObject["serialNumber"] else e)?.asString?.let(::normalizarSerie)
    }.toSet()
}.getOrDefault(emptySet())
