package com.jaac.avoqado_campo.cola

import com.jaac.avoqado_campo.BuildConfig
import com.jaac.avoqado_campo.identidad.HoraDeCampo
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID

/** I2: el celular se está borrando; la pantalla dice «Espera un momento». No se escribió nada. */
class CelularBorrandose : Exception("El celular se está borrando")

data class EvidenciaNueva(val photoLabel: String, val replaceIndex: Int, val numero: String?, val archivoFoto: String)

data class VentaNueva(
    val serialNumber: String, val precio: BigDecimal, val isPortabilidad: Boolean, val venueId: String,
    val evidencias: List<EvidenciaNueva>, val resumen: String,
)

data class ChecadaNueva(
    val venueId: String, val lat: Double?, val lon: Double?, val accuracy: Double?, val ubicacionFalsa: Boolean?,
    val archivoFoto: String?, val resumen: String,
)

data class EvidenciaSueltaNueva(
    val venueId: String, val paymentId: String, val verificationId: String, val photoLabel: String, val replaceIndex: Int,
    val numero: String?, val archivoFoto: String, val resumen: String,
)

sealed interface RefEntrada {
    data class PorLlave(val llave: String) : RefEntrada
    data class PorId(val timeEntryId: String) : RefEntrada
}

// Los cuerpos, en el orden de sus campos; Gson omite los null (zod rechaza `null` en un `.optional()`).
private data class CuerpoVenta(
    val llave: String, val serialNumber: String, val precioVisto: BigDecimal, val ocurrioAt: String, val origenHora: String,
    val isPortabilidad: Boolean,
)

private data class CuerpoChecada(
    val llave: String, val ocurrioAt: String, val origenHora: String,
    val latitude: Double? = null, val longitude: Double? = null, val accuracy: Double? = null, val ubicacionFalsa: Boolean? = null,
    val checkInPhotoUrl: String? = null, val timeEntryId: String? = null, val llaveEntrada: String? = null,
    val checkOutPhotoUrl: String? = null,
)

private data class CuerpoAceptar(val sims: List<SimConVersion>)

/**
 * Guarda cada acción del promotor ANTES de cualquier red: una transacción de Room por llamada (también el turno y el
 * movimiento de la foto: si algo falla, no queda nada). El cuerpo se serializa UNA vez y se guarda tal cual se manda.
 */
class Encolador(
    private val dao: PiezaDao,
    private val quienSoy: QuienSoy,
    private val reloj: RelojAnclado,
    private val secuencia: SecuenciaDeCaptura,
    private val dirCola: File,
    private val env: String = BuildConfig.STORAGE_ENV,
) {
    suspend fun venta(v: VentaNueva): String = llaveNueva().also { llave ->
        enTransaccion { staffId ->
            val hora = reloj.ahora()
            val cuerpo = CuerpoVenta(llave, normalizarSerie(v.serialNumber), v.precio.setScale(2), hora.iso, hora.origen, v.isPortabilidad)
            dao.insertar(pieza(TipoPieza.VENTA, llave, staffId, v.venueId, cuerpo, hora, v.resumen))
            v.evidencias.forEach {
                evidencia(staffId, v.venueId, llave, hora, it.photoLabel, it.replaceIndex, it.numero, it.archivoFoto, null, null, v.resumen)
            }
        }
    }

    suspend fun aceptarCustodia(sims: List<SimConVersion>, venueId: String): String = llaveNueva().also { llave ->
        require(sims.size in 1..50) { "De 1 a 50 SIMs" }
        enTransaccion { staffId ->
            val resumen = if (sims.size == 1) "Aceptar SIM ${sims[0].serialNumber}" else "Aceptar ${sims.size} SIMs"
            dao.insertar(pieza(TipoPieza.CUSTODIA_ACEPTAR, llave, staffId, venueId, CuerpoAceptar(sims), reloj.ahora(), resumen))
        }
    }

    suspend fun rechazarCustodia(sim: SimConVersion, venueId: String): String = llaveNueva().also { llave ->
        enTransaccion { staffId ->
            dao.insertar(pieza(TipoPieza.CUSTODIA_RECHAZAR, llave, staffId, venueId, sim, reloj.ahora(), "Rechazar SIM ${sim.serialNumber}"))
        }
    }

    suspend fun entrada(e: ChecadaNueva): String = llaveNueva().also { llave ->
        enTransaccion { staffId ->
            val hora = reloj.ahora()
            val foto = e.archivoFoto?.let { fotoPara(llave, "entrada", e.venueId, "checada", hora, it) }
            val cuerpo = CuerpoChecada(
                llave, hora.iso, hora.origen, e.lat, e.lon, e.accuracy, e.ubicacionFalsa, checkInPhotoUrl = foto?.url,
            )
            dao.insertar(pieza(TipoPieza.ENTRADA, llave, staffId, e.venueId, cuerpo, hora, e.resumen, fotos = listOfNotNull(foto)))
            dao.guardarTurno(TurnoLocal(staffId = staffId, llaveEntrada = llave, timeEntryId = null, venueId = e.venueId, abiertoAt = hora.epochMs))
            if (foto != null) mover(e.archivoFoto, foto)
        }
    }

    /**
     * La salida nombra la entrada de ESTE celular (B10). Si el caller trae `PorLlave` pero la entrada ya subió (el turno
     * tiene su `timeEntryId`, R2-5), va `PorId` y ya no depende de ella.
     */
    suspend fun salida(s: ChecadaNueva, entrada: RefEntrada): String = llaveNueva().also { llave ->
        enTransaccion { staffId ->
            val t = dao.turnoAhora()
            check(t != null && t.staffId == staffId && t.abierto) { "No hay turno abierto" }
            val ref = if (entrada is RefEntrada.PorLlave && entrada.llave == t.llaveEntrada && t.timeEntryId != null) {
                RefEntrada.PorId(t.timeEntryId)
            } else entrada
            val hora = reloj.ahora()
            val foto = s.archivoFoto?.let { fotoPara(llave, "salida", s.venueId, "checada", hora, it) }
            val cuerpo = CuerpoChecada(
                llave, hora.iso, hora.origen, s.lat, s.lon, s.accuracy, s.ubicacionFalsa,
                timeEntryId = (ref as? RefEntrada.PorId)?.timeEntryId, llaveEntrada = (ref as? RefEntrada.PorLlave)?.llave,
                checkOutPhotoUrl = foto?.url,
            )
            val dependeDe = (ref as? RefEntrada.PorLlave)?.llave
            dao.insertar(pieza(TipoPieza.SALIDA, llave, staffId, s.venueId, cuerpo, hora, s.resumen, dependeDe, listOfNotNull(foto)))
            dao.guardarTurno(t.copy(llaveEntrada = null, timeEntryId = null))
            if (foto != null) mover(s.archivoFoto, foto)
        }
    }

    /** R8-3: «Corregir evidencia» de una venta `FAILED`; ya sabe su pago y su verificación. */
    suspend fun evidenciaSuelta(e: EvidenciaSueltaNueva): String {
        var llave = ""
        enTransaccion { staffId ->
            llave = evidencia(
                staffId, e.venueId, null, reloj.ahora(), e.photoLabel, e.replaceIndex, e.numero, e.archivoFoto,
                e.paymentId, e.verificationId, e.resumen,
            )
        }
        return llave
    }

    /** `PRECIO_CAMBIO`: la vieja DESCARTADA, la nueva con llave nueva, y sus evidencias pasan a la nueva. */
    suspend fun corregirVenta(piezaId: Long, precioNuevo: BigDecimal): String = llaveNueva().also { llave ->
        enTransaccion {
            val v = dao.porId(piezaId)
            require(v != null && v.tipo == TipoPieza.VENTA) { "No es una venta: $piezaId" }
            val vieja = gsonCola.fromJson(v.cuerpoJson, CuerpoVenta::class.java)
            val texto = gsonCola.toJson(vieja.copy(llave = llave, precioVisto = precioNuevo.setScale(2)))
            dao.actualizar(v.copy(estado = EstadoPieza.DESCARTADA))
            dao.insertar(v.renacida(llave, reloj.ahora()).copy(cuerpoJson = texto, huella = huella(v.tipo, v.venueId, texto)))
            dao.hijosPorSubir(v.llave).forEach { dao.actualizar(it.copy(dependeDe = llave)) }
        }
    }

    /** `numero`/`archivoFoto` en null = se queda el de antes. Llave y `captureSeq` nuevos. */
    suspend fun corregirEvidencia(piezaId: Long, numero: String?, archivoFoto: String?): String = llaveNueva().also { llave ->
        enTransaccion {
            val ev = dao.porId(piezaId)
            require(ev != null && ev.tipo == TipoPieza.EVIDENCIA) { "No es una evidencia: $piezaId" }
            val p = gsonCola.fromJson(ev.evidenciaJson, EvidenciaPendiente::class.java)
            val hora = reloj.ahora()
            val foto = archivoFoto?.let { fotoPara(llave, p.photoLabel.lowercase(), ev.venueId, "venta", hora, it) }
                ?: fotosDe(ev.fotosJson).single()
            val nueva = p.copy(
                linkingNumber = if (p.photoLabel == VINCULACION) numero ?: p.linkingNumber else null,
                portedNumber = if (p.photoLabel == PORTABILIDAD) numero ?: p.portedNumber else null,
                captureSeq = secuencia.siguiente(), capturedAt = hora.iso, fotoUrl = foto.url,
            )
            dao.actualizar(ev.copy(estado = EstadoPieza.DESCARTADA))
            dao.insertar(ev.renacida(llave, hora).copy(evidenciaJson = gsonCola.toJson(nueva), fotosJson = gsonCola.toJson(listOf(foto))))
            if (archivoFoto != null) mover(archivoFoto, foto)
        }
    }

    /**
     * P4 + R2-4: la ENTRADA rechazada `YA_TIENE_ENTRADA` queda DESCARTADA y cada salida viva que dependía de ella se rehace
     * con llave nueva y `timeEntryId` (misma hora, ubicación y foto). Otra sucursal → `IllegalArgumentException`, sin cambios.
     */
    suspend fun usarEntrada(piezaEntradaId: Long, timeEntryId: String, venueIdDeLaEntrada: String) {
        enTransaccion {
            val e = dao.porId(piezaEntradaId)
            require(e != null && e.tipo == TipoPieza.ENTRADA) { "No es una entrada: $piezaEntradaId" }
            require(e.venueId == venueIdDeLaEntrada) { "La entrada abierta es de otra sucursal" }
            val hora = reloj.ahora()
            dao.actualizar(e.copy(estado = EstadoPieza.DESCARTADA))
            dao.hijosPorSubir(e.llave).filter { it.tipo == TipoPieza.SALIDA }.forEach { s ->
                val llave = llaveNueva()
                val c = gsonCola.fromJson(s.cuerpoJson, CuerpoChecada::class.java)
                val texto = gsonCola.toJson(c.copy(llave = llave, llaveEntrada = null, timeEntryId = timeEntryId))
                dao.actualizar(s.copy(estado = EstadoPieza.DESCARTADA))
                dao.insertar(s.renacida(llave, hora).copy(cuerpoJson = texto, huella = huella(s.tipo, s.venueId, texto), dependeDe = null))
            }
            val t = dao.turnoAhora()
            if (t?.llaveEntrada == e.llave) dao.guardarTurno(t.copy(llaveEntrada = null, timeEntryId = timeEntryId))
        }
    }

    private suspend fun enTransaccion(bloque: suspend (staffId: String) -> Unit) = dao.enTransaccion {
        if (quienSoy.limpiando) throw CelularBorrandose()
        bloque(checkNotNull(quienSoy.staffId) { "Este celular no tiene persona" })
    }

    private suspend fun evidencia(
        staffId: String, venueId: String, dependeDe: String?, hora: HoraDeCampo, label: String, replaceIndex: Int,
        numero: String?, archivo: String, paymentId: String?, verificationId: String?, resumen: String,
    ): String {
        require(label == VINCULACION || label == PORTABILIDAD) { "photoLabel desconocido: $label" }
        val llave = llaveNueva()
        val foto = fotoPara(llave, label.lowercase(), venueId, "venta", hora, archivo)
        val pendiente = EvidenciaPendiente(
            label, replaceIndex, numero.takeIf { label == VINCULACION }, numero.takeIf { label == PORTABILIDAD },
            secuencia.siguiente(), quienSoy.aparatoId, hora.iso, foto.url, paymentId, verificationId,
        )
        dao.insertar(
            Pieza(
                tipo = TipoPieza.EVIDENCIA, llave = llave, staffId = staffId, venueId = venueId, cuerpoJson = null,
                dependeDe = dependeDe, evidenciaJson = gsonCola.toJson(pendiente), fotosJson = gsonCola.toJson(listOf(foto)),
                creadaAt = hora.epochMs, resumen = resumen,
            ),
        )
        mover(archivo, foto)
        return llave
    }

    private fun pieza(
        tipo: String, llave: String, staffId: String, venueId: String, cuerpo: Any, hora: HoraDeCampo, resumen: String,
        dependeDe: String? = null, fotos: List<FotoPorSubir> = emptyList(),
    ): Pieza {
        val texto = gsonCola.toJson(cuerpo)
        return Pieza(
            tipo = tipo, llave = llave, staffId = staffId, venueId = venueId, cuerpoJson = texto,
            huella = huella(tipo, venueId, texto), dependeDe = dependeDe, fotosJson = gsonCola.toJson(fotos),
            creadaAt = hora.epochMs, resumen = resumen,
        )
    }

    /** P2: la fila ya trae este destino; el archivo se mueve después, dentro de la misma transacción. */
    private fun fotoPara(llave: String, slot: String, venueId: String, clase: String, hora: HoraDeCampo, origen: String): FotoPorSubir {
        val ruta = rutaDe(env, venueId, clase, fechaEnMexico(hora.epochMs), llave, slot)
        return FotoPorSubir(File(dirCola, "${llave}_$slot.jpg").absolutePath, ruta, urlDe(ruta), File(origen).length(), subida = false)
    }

    // ponytail: si una inserción posterior falla, la foto ya movida queda huérfana en `cola/`; el barrido de la Tarea 6 la tira.
    private fun mover(origen: String, foto: FotoPorSubir) {
        val o = File(origen)
        val d = File(foto.archivo)
        if (!o.isFile) throw IOException("La foto ya no está en el celular")
        dirCola.mkdirs()
        if (!o.renameTo(d)) {
            o.copyTo(d, overwrite = true)
            o.delete()
        }
        if (d.length() != foto.bytes) throw IOException("La foto no se guardó completa")
    }

    private fun Pieza.renacida(llave: String, hora: HoraDeCampo) = copy(
        id = 0, llave = llave, estado = EstadoPieza.PENDIENTE, rechazoCode = null, rechazoMensaje = null,
        rechazoDatosJson = null, resultadoJson = null, intentos = 0, siguienteIntentoAt = 0, creadaAt = hora.epochMs,
    )

    private fun llaveNueva() = UUID.randomUUID().toString()

    private companion object {
        const val VINCULACION = "Vinculacion"
        const val PORTABILIDAD = "Portabilidad"
    }
}
