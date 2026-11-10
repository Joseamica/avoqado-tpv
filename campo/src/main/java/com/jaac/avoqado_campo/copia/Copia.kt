package com.jaac.avoqado_campo.copia

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
