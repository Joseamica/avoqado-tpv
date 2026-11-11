package com.jaac.avoqado_campo.cola

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * Una operación del promotor, guardada ANTES de tocar la red. `cuerpoJson` es el texto exacto que se manda (salvo en la
 * EVIDENCIA, cuyo cuerpo arma el `Subidor` con `evidenciaJson` porque necesita el `paymentId` de su venta).
 */
@Entity(tableName = "piezas", indices = [Index(value = ["llave"], unique = true)])
data class Pieza(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tipo: String, /* VENTA|ENTRADA|SALIDA|CUSTODIA_ACEPTAR|CUSTODIA_RECHAZAR|EVIDENCIA */
    val llave: String,
    val staffId: String,
    val venueId: String,
    val cuerpoJson: String?, /* null sólo en EVIDENCIA */
    val huella: String? = null,
    val dependeDe: String? = null, /* llave de su venta o de su entrada */
    val evidenciaJson: String? = null, /* EVIDENCIA: EvidenciaPendiente */
    val fotosJson: String = "[]", /* List<FotoPorSubir> */
    val estado: String = EstadoPieza.PENDIENTE,
    val rechazoCode: String? = null,
    val rechazoMensaje: String? = null,
    val rechazoDatosJson: String? = null,
    val resultadoJson: String? = null,
    val intentos: Int = 0,
    val siguienteIntentoAt: Long = 0,
    val creadaAt: Long,
    val resumen: String,
)

object TipoPieza {
    const val VENTA = "VENTA"
    const val ENTRADA = "ENTRADA"
    const val SALIDA = "SALIDA"
    const val CUSTODIA_ACEPTAR = "CUSTODIA_ACEPTAR"
    const val CUSTODIA_RECHAZAR = "CUSTODIA_RECHAZAR"
    const val EVIDENCIA = "EVIDENCIA"
}

/**
 * Terminales = HECHA, DESCARTADA. «Por subir» = PENDIENTE, RETENIDA, RECHAZADA (AVISO no cuenta). APARTADA (r5): la checada
 * tocada que espera el GPS; no se manda ni lleva huella, pero está viva (el borrado la cuenta) y al arrancar se completa.
 */
object EstadoPieza {
    const val APARTADA = "APARTADA"
    const val PENDIENTE = "PENDIENTE"
    const val RETENIDA = "RETENIDA"
    const val RECHAZADA = "RECHAZADA"
    const val AVISO = "AVISO"
    const val HECHA = "HECHA"
    const val DESCARTADA = "DESCARTADA"
}

data class FotoPorSubir(
    val archivo: String, /* ruta absoluta en filesDir/cola */
    val ruta: String, /* la de Storage */
    val url: String, /* sin token, B1 */
    val bytes: Long,
    val subida: Boolean,
)

data class EvidenciaPendiente(
    val photoLabel: String, /* Vinculacion|Portabilidad */
    val replaceIndex: Int, /* 0|1 */
    val linkingNumber: String?,
    val portedNumber: String?,
    val captureSeq: Long,
    val captureDeviceId: String,
    val capturedAt: String,
    val fotoUrl: String,
    /** Sólo la evidencia suelta (R8-3, «Corregir evidencia» de «Mis SIMs»): ya sabe su pago y su verificación. */
    val paymentId: String? = null,
    val verificationId: String? = null,
)

/** Una sola fila (P3). La escriben sólo el `Encolador` (entrada, salida, usarEntrada) y el `PiezaDao`, en transacción. */
@Entity(tableName = "turno")
data class TurnoLocal(
    @PrimaryKey val id: Int = 1,
    val staffId: String?, /* R5-2 */
    val llaveEntrada: String?,
    val timeEntryId: String?,
    val venueId: String?,
    val abiertoAt: Long?,
) {
    val abierto: Boolean get() = llaveEntrada != null || timeEntryId != null
}

data class SimConVersion(val serialNumber: String, val custodyVersion: Int)

/** Sin escapar `=`, `<`, `&`: la URL de la foto va tal cual en el cuerpo. */
internal val gsonCola: Gson = GsonBuilder().disableHtmlEscaping().create()

private val tipoFotos = object : TypeToken<List<FotoPorSubir>>() {}.type

fun fotosDe(fotosJson: String): List<FotoPorSubir> = gsonCola.fromJson(fotosJson, tipoFotos) ?: emptyList()

/** Como lo guarda el inventario (y como lo deja el SERVIDOR): sin espacios y en mayúsculas. */
fun normalizarSerie(serie: String): String = serie.replace(Regex("\\s+"), "").uppercase()

/** Las SIMs (serie normalizada y versión) del cuerpo de una custodia: `{"sims":[…]}` al aceptar, `{"serialNumber",…}` al rechazar. */
fun simsDeCustodia(cuerpoJson: String?): List<SimConVersion> = runCatching {
    val o = com.google.gson.JsonParser.parseString(cuerpoJson).asJsonObject
    val lista = o["sims"]?.asJsonArray?.toList() ?: listOf(o)
    lista.mapNotNull { e ->
        val s = e.asJsonObject
        s["serialNumber"]?.asString?.let { SimConVersion(normalizarSerie(it), s["custodyVersion"]?.asInt ?: 0) }
    }
}.getOrDefault(emptyList())

fun seriesDeCustodia(cuerpoJson: String?): List<String> = simsDeCustodia(cuerpoJson).map { it.serialNumber }
