package com.jaac.avoqado_campo.cola

import com.jaac.avoqado_campo.BuildConfig
import com.jaac.avoqado_campo.identidad.HoraDeCampo
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** I2: el celular se está borrando; la pantalla dice «Espera un momento». No se escribió nada. */
class CelularBorrandose : Exception("El celular se está borrando")

/** Ya hay una VENTA viva (no HECHA ni DESCARTADA) de esa SIM en la cola: no se escribió nada. */
class SimYaEnCola : Exception("Esta SIM ya está vendida (o por subir)")

/** `replaceIndex` sale del `photoLabel` (Vinculacion → 0, Portabilidad → 1), nunca suelto. */
data class EvidenciaNueva(val photoLabel: String, val numero: String?, val archivoFoto: String)

data class VentaNueva(
    val serialNumber: String, val precio: BigDecimal, val isPortabilidad: Boolean, val venueId: String,
    val evidencias: List<EvidenciaNueva>, val resumen: String,
)

data class ChecadaNueva(
    val venueId: String, val lat: Double?, val lon: Double?, val accuracy: Double?, val ubicacionFalsa: Boolean?,
    val archivoFoto: String?, val resumen: String,
)

data class EvidenciaSueltaNueva(
    val venueId: String, val paymentId: String, val verificationId: String, val photoLabel: String,
    val numero: String?, val archivoFoto: String, val resumen: String,
)

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

/** Lo que guarda la checada APARTADA (r5) hasta completarse: de quién (generación) y la hora del toque. */
private data class Apartada(val generacion: String, val ocurrioAt: String, val origenHora: String)

/**
 * Guarda cada acción del promotor ANTES de cualquier red: una transacción de Room por llamada (también el turno y el
 * copiado de la foto: si algo falla, no queda nada y la foto original sigue donde estaba, para reintentar). El cuerpo se
 * serializa UNA vez y se guarda tal cual se manda. Cada escritura recibe la `generacion` que leyó quien llama al tocar
 * (junto con la sesión): si el celular se borró o se activó para otra persona desde entonces, no escribe nada.
 */
class Encolador(
    private val dao: PiezaDao,
    private val quienSoy: QuienSoy,
    private val reloj: RelojAnclado,
    private val secuencia: SecuenciaDeCaptura,
    private val dirCola: File,
    private val env: String = BuildConfig.STORAGE_ENV,
) {
    suspend fun venta(generacion: String, v: VentaNueva): String = llaveNueva().also { llave ->
        enTransaccion(generacion) {
            // Defensa en la base (Tarea 10): dentro de la transacción, dos registros de la misma SIM no pasan los dos.
            if (dao.ventaConSerieEnCola(v.serialNumber) != null) throw SimYaEnCola()
            val hora = reloj.ahora()
            val cuerpo = CuerpoVenta(llave, normalizarSerie(v.serialNumber), v.precio.setScale(2), hora.iso, hora.origen, v.isPortabilidad)
            dao.insertar(pieza(TipoPieza.VENTA, llave, staffId, v.venueId, cuerpo, hora, v.resumen))
            v.evidencias.forEach {
                evidencia(staffId, v.venueId, llave, hora, it.photoLabel, it.numero, it.archivoFoto, null, null, v.resumen)
            }
        }
    }

    /**
     * [reemplaza]: custodias RECHAZADA (`ASIGNACION_CAMBIO`) que esta decisión sustituye; quedan DESCARTADA en la misma
     * transacción, como `corregirVenta`. Una SIM que ya está en otra custodia viva → [SimYaEnCola] y nada escrito.
     */
    suspend fun aceptarCustodia(generacion: String, sims: List<SimConVersion>, venueId: String, reemplaza: List<Long> = emptyList()): String =
        llaveNueva().also { llave ->
            require(sims.size in 1..50) { "De 1 a 50 SIMs" }
            enTransaccion(generacion) {
                custodiaNueva(sims, reemplaza)
                val resumen = if (sims.size == 1) "Aceptar SIM ${sims[0].serialNumber}" else "Aceptar ${sims.size} SIMs"
                dao.insertar(pieza(TipoPieza.CUSTODIA_ACEPTAR, llave, staffId, venueId, CuerpoAceptar(sims), reloj.ahora(), resumen))
            }
        }

    suspend fun rechazarCustodia(generacion: String, sim: SimConVersion, venueId: String, reemplaza: List<Long> = emptyList()): String =
        llaveNueva().also { llave ->
            enTransaccion(generacion) {
                custodiaNueva(listOf(sim), reemplaza)
                dao.insertar(pieza(TipoPieza.CUSTODIA_RECHAZAR, llave, staffId, venueId, sim, reloj.ahora(), "Rechazar SIM ${sim.serialNumber}"))
            }
        }

    private suspend fun custodiaNueva(sims: List<SimConVersion>, reemplaza: List<Long>) {
        reemplaza.forEach { id ->
            val v = dao.porId(id)
            require(v != null && v.tipo in CUSTODIAS && v.estado == EstadoPieza.RECHAZADA) { "No es una custodia rechazada: $id" }
            dao.actualizar(v.copy(estado = EstadoPieza.DESCARTADA))
        }
        val nuevas = sims.map { normalizarSerie(it.serialNumber) }.toSet()
        if (dao.conSimsPorSubir().any { it.tipo in CUSTODIAS && seriesDeCustodia(it.cuerpoJson).any(nuevas::contains) }) throw SimYaEnCola()
    }

    /** Con un turno de esta persona ya abierto → `IllegalStateException` y nada escrito (B10: la pantalla no lo ofrece). */
    suspend fun entrada(generacion: String, e: ChecadaNueva): String = deUnaVez(generacion, TipoPieza.ENTRADA, e)

    /**
     * La salida nombra la entrada de ESTE celular, leída del turno (B10, R2-5): su `timeEntryId` si ya lo tiene (sin
     * `dependeDe`), si no su `llaveEntrada` (y depende de ella). Sin turno abierto de esta persona → `IllegalStateException`;
     * otra sucursal que la del turno → `IllegalArgumentException`. En los dos casos no se escribe nada.
     */
    suspend fun salida(generacion: String, s: ChecadaNueva): String = deUnaVez(generacion, TipoPieza.SALIDA, s)

    /** Apartar y completar en UNA transacción: si algo falla no queda nada y la selfie sigue donde estaba (para reintentar). */
    private suspend fun deUnaVez(generacion: String, tipo: String, c: ChecadaNueva): String {
        var llave = ""
        enTransaccion(generacion) { llave = completar(apartar(generacion, tipo, c.venueId, c.archivoFoto, c.resumen), c) }
        return llave
    }

    /**
     * r5 · lo que la persona tocó se guarda ANTES de esperar el GPS: una fila APARTADA con su llave, la hora del toque y la
     * selfie ya copiada a `cola/` (el barrido de `cacheDir/fotos` no la alcanza). No se manda ni lleva huella. Revisa el
     * turno con las mismas reglas que [completarChecada]: lo que no se podría checar no se aparta. Devuelve su id.
     */
    suspend fun apartarChecada(generacion: String, tipo: String, venueId: String, archivoFoto: String?, resumen: String): Long {
        var id = 0L
        enTransaccion(generacion) { id = apartar(generacion, tipo, venueId, archivoFoto, resumen) }
        return id
    }

    /**
     * La APARTADA [id] se vuelve la ENTRADA/SALIDA de siempre, en su misma fila y en UNA transacción: el cuerpo se serializa
     * una vez, con la hora del toque y la ubicación de [u] (sus `lat`/`lon`/`accuracy`/`ubicacionFalsa`; null = sin ubicación).
     */
    suspend fun completarChecada(generacion: String, id: Long, u: ChecadaNueva?): String {
        var llave = ""
        enTransaccion(generacion) { llave = completar(id, u) }
        return llave
    }

    private suspend fun Transaccion.apartar(generacion: String, tipo: String, venueId: String, archivoFoto: String?, resumen: String): Long {
        require(tipo == TipoPieza.ENTRADA || tipo == TipoPieza.SALIDA) { "No es una checada: $tipo" }
        turnoPara(tipo, staffId, venueId)
        val llave = llaveNueva()
        val hora = reloj.ahora()
        val foto = archivoFoto?.let { fotoPara(llave, tipo.lowercase(), venueId, "checada", hora, it) }
        val id = dao.insertar(
            Pieza(
                tipo = tipo, llave = llave, staffId = staffId, venueId = venueId,
                cuerpoJson = gsonCola.toJson(Apartada(generacion, hora.iso, hora.origen)), estado = EstadoPieza.APARTADA,
                fotosJson = gsonCola.toJson(listOfNotNull(foto)), creadaAt = hora.epochMs, resumen = resumen,
            ),
        )
        if (foto != null) porCopiar(archivoFoto, foto)
        return id
    }

    private suspend fun Transaccion.completar(id: Long, u: ChecadaNueva?): String {
        val a = checkNotNull(dao.porId(id)?.takeIf { it.estado == EstadoPieza.APARTADA }) { "La checada ya no está apartada" }
        val d = gsonCola.fromJson(a.cuerpoJson, Apartada::class.java)
        val hora = HoraDeCampo(d.ocurrioAt, d.origenHora, a.creadaAt)
        val foto = fotosDe(a.fotosJson).singleOrNull()
        val t = turnoPara(a.tipo, staffId, a.venueId)
        val entrada = a.tipo == TipoPieza.ENTRADA
        val llaveEntrada = if (!entrada && t!!.timeEntryId == null) t.llaveEntrada else null
        val cuerpo = CuerpoChecada(
            a.llave, hora.iso, hora.origen, u?.lat, u?.lon, u?.accuracy, u?.ubicacionFalsa,
            checkInPhotoUrl = foto?.url.takeIf { entrada }, timeEntryId = t?.timeEntryId.takeIf { !entrada },
            llaveEntrada = llaveEntrada, checkOutPhotoUrl = foto?.url.takeIf { !entrada },
        )
        dao.actualizar(pieza(a.tipo, a.llave, staffId, a.venueId, cuerpo, hora, a.resumen, llaveEntrada, listOfNotNull(foto)).copy(id = a.id))
        if (entrada) dao.guardarTurno(TurnoLocal(staffId = staffId, llaveEntrada = a.llave, timeEntryId = null, venueId = a.venueId, abiertoAt = hora.epochMs))
        else dao.guardarTurno(t!!.copy(llaveEntrada = null, timeEntryId = null))
        return a.llave
    }

    /**
     * r6: una APARTADA que no se pudo completar por algo que no es un borrado en curso (p. ej. el turno ya no la admite) NO se
     * borra: queda RECHAZADA en «Mis pendientes», con su foto y «Quitar» (la persona decide). Sin fila APARTADA, nada.
     */
    suspend fun rechazarApartada(id: Long, mensaje: String) {
        val a = dao.porId(id)?.takeIf { it.estado == EstadoPieza.APARTADA } ?: return
        dao.rechazar(a, CHECADA_SIN_TERMINAR, mensaje)
    }

    /**
     * r5 · al arrancar, antes de cualquier pantalla: la checada que quedó APARTADA (Android mató la app mientras esperaba el
     * GPS) se completa sin ubicación («Se checa sin ubicación», B9). Completar es idempotente: sólo una fila APARTADA se
     * completa. r6: con un borrado en curso se queda APARTADA (nada la borra); si no se puede por otra cosa queda RECHAZADA
     * en «Mis pendientes», nunca se pierde en silencio. Una generación distinta no debería existir (invariante 3 de
     * `BorradoDelCelular`): si apareciera, tampoco se completa a nombre de otra persona. Devuelve cuántas se completaron.
     */
    suspend fun completarApartadas(): Int {
        var n = 0
        for (a in dao.apartadas()) {
            val de = gsonCola.fromJson(a.cuerpoJson, Apartada::class.java).generacion
            try {
                if (de != quienSoy.generacion) throw CelularBorrandose()
                completarChecada(de, a.id, null)
                n++
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                // r7: un choque con la marca de un borrado (en curso, o que ya se negó y devolvió su generación) no la rechaza.
                if (x is CelularBorrandose && (quienSoy.limpiando || de == quienSoy.generacion)) continue
                rechazarApartada(a.id, "Esta checada no se pudo terminar. Avísale a tu supervisor.")
            }
        }
        return n
    }

    /** ENTRADA: no debe haber turno abierto de [staffId] (null). SALIDA: su turno abierto, de esa sucursal. Si no, lanza. */
    private suspend fun turnoPara(tipo: String, staffId: String, venueId: String): TurnoLocal? {
        val t = dao.turnoAhora()
        if (tipo == TipoPieza.ENTRADA) {
            check(t == null || !t.abierto || t.staffId != staffId) { "Ya hay un turno abierto" }
            return null
        }
        check(t != null && t.staffId == staffId && t.abierto) { "No hay turno abierto" }
        require(venueId == t.venueId) { "La entrada abierta es de otra sucursal" }
        return t
    }

    /** R8-3: «Corregir evidencia» de una venta `FAILED`; ya sabe su pago y su verificación. */
    suspend fun evidenciaSuelta(generacion: String, e: EvidenciaSueltaNueva): String {
        var llave = ""
        enTransaccion(generacion) {
            llave = evidencia(
                staffId, e.venueId, null, reloj.ahora(), e.photoLabel, e.numero, e.archivoFoto,
                e.paymentId, e.verificationId, e.resumen,
            )
        }
        return llave
    }

    /** `PRECIO_CAMBIO` (sólo una venta RECHAZADA): la vieja DESCARTADA, la nueva con llave nueva, y sus evidencias pasan a la nueva. */
    suspend fun corregirVenta(generacion: String, piezaId: Long, precioNuevo: BigDecimal): String = llaveNueva().also { llave ->
        enTransaccion(generacion) {
            val v = dao.porId(piezaId)
            require(v != null && v.tipo == TipoPieza.VENTA && v.estado == EstadoPieza.RECHAZADA) { "No es una venta rechazada: $piezaId" }
            val vieja = gsonCola.fromJson(v.cuerpoJson, CuerpoVenta::class.java)
            val texto = gsonCola.toJson(vieja.copy(llave = llave, precioVisto = precioNuevo.setScale(2)))
            dao.actualizar(v.copy(estado = EstadoPieza.DESCARTADA))
            dao.insertar(v.renacida(llave, reloj.ahora()).copy(cuerpoJson = texto, huella = huella(v.tipo, v.venueId, texto)))
            dao.hijosPorSubir(v.llave).forEach { dao.actualizar(it.copy(dependeDe = llave)) }
        }
    }

    /** Sólo una evidencia RECHAZADA. `numero`/`archivoFoto` en null = se queda el de antes. Llave y `captureSeq` nuevos. */
    suspend fun corregirEvidencia(generacion: String, piezaId: Long, numero: String?, archivoFoto: String?): String = llaveNueva().also { llave ->
        enTransaccion(generacion) {
            val ev = dao.porId(piezaId)
            require(ev != null && ev.tipo == TipoPieza.EVIDENCIA && ev.estado == EstadoPieza.RECHAZADA) { "No es una evidencia rechazada: $piezaId" }
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
            if (archivoFoto != null) porCopiar(archivoFoto, foto)
        }
    }

    /**
     * P4 + R2-4: la ENTRADA rechazada `YA_TIENE_ENTRADA` queda DESCARTADA y cada salida viva que dependía de ella se rehace
     * con llave nueva y `timeEntryId` (misma hora, ubicación y foto). Otra sucursal → `IllegalArgumentException`, sin cambios.
     */
    suspend fun usarEntrada(generacion: String, piezaEntradaId: Long, timeEntryId: String, venueIdDeLaEntrada: String) {
        enTransaccion(generacion) {
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

    /** Lo de UNA llamada: la persona y sus fotos por copiar. Nada compartido entre llamadas. */
    private class Transaccion(val staffId: String) {
        val fotos = mutableListOf<Pair<File, File>>()

        fun porCopiar(origen: String, foto: FotoPorSubir) {
            val o = File(origen)
            if (!o.isFile) throw IOException("La foto ya no está en el celular")
            fotos += o to File(foto.archivo)
        }
    }

    /**
     * P2: las filas primero; al final de la MISMA transacción se COPIA cada foto a `cola/`. El original se borra sólo
     * después del commit; si algo falla, se borran las copias y el original sigue ahí para reintentar.
     */
    private suspend fun enTransaccion(generacion: String, bloque: suspend Transaccion.() -> Unit) {
        var tx: Transaccion? = null
        try {
            dao.enTransaccion {
                // H2: también si desde el toque hubo un borrado (y quizá otra activación): el `staffId` de hoy no es el de quien tocó.
                // r3: marca, generación y `staffId` de UNA instantánea (nunca la generación de una persona y el id de otra).
                val yo = quienSoy.instantanea()
                if (yo.limpiando || yo.generacion != generacion) throw CelularBorrandose()
                val t = Transaccion(checkNotNull(yo.staffId) { "Este celular no tiene persona" })
                tx = t
                t.bloque()
                t.fotos.forEach { (o, d) -> copiar(o, d) }
            }
        } catch (e: Throwable) {
            tx?.fotos?.forEach { it.second.delete() }
            throw e
        }
        tx?.fotos?.forEach { it.first.delete() }
    }

    private suspend fun Transaccion.evidencia(
        staffId: String, venueId: String, dependeDe: String?, hora: HoraDeCampo, label: String,
        numero: String?, archivo: String, paymentId: String?, verificationId: String?, resumen: String,
    ): String {
        require(label == VINCULACION || label == PORTABILIDAD) { "photoLabel desconocido: $label" }
        val llave = llaveNueva()
        val foto = fotoPara(llave, label.lowercase(), venueId, "venta", hora, archivo)
        val pendiente = EvidenciaPendiente(
            label, if (label == VINCULACION) 0 else 1, numero.takeIf { label == VINCULACION }, numero.takeIf { label == PORTABILIDAD },
            secuencia.siguiente(), quienSoy.aparatoId, hora.iso, foto.url, paymentId, verificationId,
        )
        dao.insertar(
            Pieza(
                tipo = TipoPieza.EVIDENCIA, llave = llave, staffId = staffId, venueId = venueId, cuerpoJson = null,
                dependeDe = dependeDe, evidenciaJson = gsonCola.toJson(pendiente), fotosJson = gsonCola.toJson(listOf(foto)),
                creadaAt = hora.epochMs, resumen = resumen,
            ),
        )
        porCopiar(archivo, foto)
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

    private fun copiar(o: File, d: File) {
        dirCola.mkdirs()
        o.copyTo(d, overwrite = true)
        if (d.length() != o.length()) throw IOException("La foto no se guardó completa")
    }

    private fun Pieza.renacida(llave: String, hora: HoraDeCampo) = copy(
        id = 0, llave = llave, estado = EstadoPieza.PENDIENTE, rechazoCode = null, rechazoMensaje = null,
        rechazoDatosJson = null, resultadoJson = null, intentos = 0, siguienteIntentoAt = 0, creadaAt = hora.epochMs,
    )

    private fun llaveNueva() = UUID.randomUUID().toString()

    private companion object {
        const val CHECADA_SIN_TERMINAR = "CHECADA_SIN_TERMINAR"
        val CUSTODIAS = setOf(TipoPieza.CUSTODIA_ACEPTAR, TipoPieza.CUSTODIA_RECHAZAR)
        const val VINCULACION = "Vinculacion"
        const val PORTABILIDAD = "Portabilidad"
    }
}
