package com.jaac.avoqado_campo.cola

import com.google.gson.JsonParser
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import com.jaac.avoqado_campo.red.MotivoSinSesion
import com.jaac.avoqado_campo.red.RespuestaDelServidor
import com.jaac.avoqado_campo.red.ResultadoConSesion
import com.jaac.avoqado_campo.red.clasificar
import com.jaac.avoqado_campo.red.objetoJson
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.File

/**
 * [cortadoPor]: `SIN_RED` · `REINTENTABLE` (una pieza espera) · `NECESITA_PIN` · `OTRA_SUCURSAL` · `GRACIA` (desvinculado o dado
 * de baja: sigue la Tarea 7) · `DESCONOCIDO` · `null` (se vació todo lo que se podía). [copiaPorRefrescar]: una VENTA o CUSTODIA quedó.
 */
data class ResultadoDeVaciado(
    val subidas: Int,
    val cortadoPor: String?,
    val modoGracia: Boolean,
    val copiaPorRefrescar: Boolean = false,
)

// La evidencia, en el orden del esquema del SERVIDOR; Gson omite los null.
private data class CuerpoEvidencia(
    val paymentId: String, val verificationId: String?, val photoUrls: List<String>, val photoLabel: String, val replaceIndex: Int,
    val linkingNumber: String?, val portedNumber: String?, val idempotencyKey: String, val captureSeq: Long,
    val captureDeviceId: String, val capturedAt: String,
)

/**
 * Vacía la cola por la ruta normal, en orden de `id`, una pieza a la vez y nunca dos veces a la vez ([MutexDeSubida]). Cada pieza
 * sale con el texto que se guardó al tocar (la EVIDENCIA se arma igual cada vez); lo que no se sabe si llegó se repite con la
 * MISMA llave. Lo que espera corta: lo de atrás nunca pasa antes (P1).
 */
class Subidor(
    private val dao: PiezaDao,
    private val api: CampoApi,
    private val guardia: GuardiaDeSesion,
    private val quienSoy: QuienSoy,
    private val fotos: SubidorDeFotos,
    private val mutex: MutexDeSubida,
    private val dirCola: File,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    suspend fun vaciar(): ResultadoDeVaciado = mutex.mutex.withLock {
        val r = pasada()
        dao.purgarTerminalesAntesDe(ahora() - SIETE_DIAS)
        barrer()
        r
    }

    private suspend fun pasada(): ResultadoDeVaciado {
        var subidas = 0
        var copia = false
        fun corte(motivo: String, gracia: Boolean = false) = ResultadoDeVaciado(subidas, motivo, gracia, copia)

        for (id in dao.siguientesParaSubir().map { it.id }) {
            // Releída: otro pudo cambiarla (corregir, quitar) desde que se armó la lista.
            var p = dao.porId(id)?.takeIf { it.estado == EstadoPieza.PENDIENTE || it.estado == EstadoPieza.RETENIDA } ?: continue

            // 0. R2-1 y R3-2: la identidad se lee JUSTO antes de decidir cada pieza.
            val yo = quienSoy.identidad.value
            if (yo.estado != EstadoDelCelular.VINCULADO) {
                val gracia = yo.estado == EstadoDelCelular.DESVINCULADO || yo.estado == EstadoDelCelular.DADO_DE_BAJA
                return corte(if (gracia) GRACIA else yo.estado.name, gracia)
            }
            if (yo.necesitaPin) return corte(NECESITA_PIN)

            // 1. B13: la sucursal ya no es suya → rechazo; es suya pero no la de la sesión → espera sin tocarla.
            if (yo.sucursales.none { it.venueId == p.venueId }) {
                rechazar(p, "YA_NO_ES_TU_SUCURSAL", "Ya no estás en esta tienda. Avísale a tu supervisor.")
                continue
            }
            if (p.venueId != yo.venueIdDeLaSesion) continue

            // 2. P1: una pieza en espera corta.
            // ponytail: una espera de más de 15 min sólo sale de un reloj que se movió; se ignora para no trabar la cola.
            val t = ahora()
            if (p.siguienteIntentoAt > t && p.siguienteIntentoAt - t <= ESPERA_MAXIMA) return corte(REINTENTABLE)

            // 3. Barrera: su venta (o su entrada) primero. Si quedó rechazada, «Mis pendientes» dice «espera a su venta».
            val padre = p.dependeDe?.let { dao.porLlave(it) }
            if (p.dependeDe != null && padre?.estado != EstadoPieza.HECHA) continue

            // Un tipo que esta versión no conoce no puede trabar la cola para siempre.
            if (p.tipo !in TIPOS) {
                rechazar(p, "TIPO_DESCONOCIDO", "Esta versión de la app no sabe subir esto. Avísale a tu supervisor.")
                continue
            }

            // 4. Fotos: cada una se marca subida en cuanto Storage la confirma.
            val (conFotos, foto) = dao.subirFotos(p, fotos)
            p = conFotos
            when (foto) {
                ResultadoFoto.Subida -> Unit
                ResultadoFoto.Reintentable -> { esperar(p, null); return corte(REINTENTABLE) }
                is ResultadoFoto.Definitiva -> { rechazar(p, "FOTO_NO_SUBIO", foto.mensaje); continue }
            }

            // 5. El cuerpo.
            val cuerpo = if (p.tipo == TipoPieza.EVIDENCIA) cuerpoDeEvidencia(p, padre) else p.cuerpoJson
            if (cuerpo == null) {
                rechazar(p, "SIN_PAGO", "No encontramos el pago de esta venta. Avísale a tu supervisor.")
                continue
            }

            // 6. Se manda.
            val s = mandar(p, cuerpo)
            if (s is ResultadoConSesion.SinSesion) {
                return when (s.motivo) {
                    MotivoSinSesion.ESPERA -> { esperar(p, s.reintentaEnMs); corte(REINTENTABLE) }
                    MotivoSinSesion.DESVINCULADO, MotivoSinSesion.DADO_DE_BAJA -> corte(GRACIA, gracia = true)
                    else -> corte(s.motivo.name) // SIN_RED, NECESITA_PIN, OTRA_SUCURSAL, DESCONOCIDO: la pieza no se toca
                }
            }
            val r = (s as ResultadoConSesion.Respuesta).r
            val texto = (r.body() ?: r.errorBody())?.use { it.string() }
            when (val k = clasificar(r.code(), texto, r.headers()["Retry-After"])) {
                is RespuestaDelServidor.Ok -> {
                    hecha(p, k.cuerpo)
                    subidas++
                    if (p.tipo == TipoPieza.VENTA || p.tipo.startsWith("CUSTODIA_")) copia = true
                }
                is RespuestaDelServidor.Reintentable -> when {
                    // R6-B2: el SERVIDOR dice «otra sucursal» de una evidencia de la sucursal de la sesión: la venta se trasladó.
                    k.code == "OTRA_SUCURSAL" && p.venueId == quienSoy.sesion?.venueId && p.tipo == TipoPieza.EVIDENCIA ->
                        rechazar(p, "VENTA_MOVIDA", "Esta venta se movió a otra tienda; tu supervisor sube la evidencia")
                    // En otro tipo no se espera: definitiva, con lo que dijo el SERVIDOR.
                    k.code == "OTRA_SUCURSAL" && p.venueId == quienSoy.sesion?.venueId -> rechazar(
                        p, "OTRA_SUCURSAL", objetoJson(texto)?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: "El servidor dice que esto es de otra sucursal.",
                        if (objetoJson(texto) != null) texto else "{}",
                    )
                    k.code == "OTRA_SUCURSAL" -> Unit // la sesión cambió de sucursal: espera, como la regla 1
                    else -> { esperar(p, k.reintentaEnMs); return corte(REINTENTABLE) }
                }
                is RespuestaDelServidor.Definitiva -> rechazar(p, k.code ?: "HTTP_${k.status}", k.message, k.datosJson)
                is RespuestaDelServidor.Sesion -> return corte(NECESITA_PIN) // la Guardia no lo deja pasar
                RespuestaDelServidor.SinRed -> return corte(MotivoSinSesion.SIN_RED.name)
            }
        }
        return ResultadoDeVaciado(subidas, null, false, copia)
    }

    /** R8-3: la suelta ya trae su pago; la de una venta usa el `paymentId` que dejó la venta (por la ruta normal o la gracia). */
    private fun cuerpoDeEvidencia(p: Pieza, venta: Pieza?): String? {
        val e = gsonCola.fromJson(p.evidenciaJson, EvidenciaPendiente::class.java)
        val pago = e.paymentId ?: campo(venta?.resultadoJson, "paymentId") ?: return null
        return gsonCola.toJson(
            CuerpoEvidencia(
                pago, e.verificationId, listOf(e.fotoUrl), e.photoLabel, e.replaceIndex, e.linkingNumber, e.portedNumber,
                p.llave, e.captureSeq, e.captureDeviceId, e.capturedAt,
            ),
        )
    }

    private suspend fun mandar(p: Pieza, cuerpo: String): ResultadoConSesion<ResponseBody> = guardia.conSesion(p.venueId) { b ->
        val rb = cuerpo.toRequestBody(JSON)
        val v = p.venueId
        when (p.tipo) {
            TipoPieza.VENTA -> api.ventas(b, v, rb)
            TipoPieza.ENTRADA -> api.entrada(b, v, rb)
            TipoPieza.SALIDA -> api.salida(b, v, rb)
            TipoPieza.CUSTODIA_ACEPTAR -> api.aceptarCustodia(b, v, rb)
            TipoPieza.CUSTODIA_RECHAZAR -> api.rechazarCustodia(b, v, rb)
            TipoPieza.EVIDENCIA -> api.evidencia(b, v, rb)
            else -> error("Tipo desconocido: ${p.tipo}") // no llega: se rechaza antes
        }
    }

    private suspend fun hecha(p: Pieza, cuerpo: String) = dao.hecha(p, cuerpo)
    private suspend fun rechazar(p: Pieza, code: String, mensaje: String, datosJson: String? = null) =
        dao.rechazar(p, code, mensaje, datosJson)
    private suspend fun esperar(p: Pieza, pedidaMs: Long?) = dao.esperar(p, pedidaMs, ahora())

    /** P2: sólo archivos de hace más de 24 h que ninguna pieza viva nombra (una foto recién copiada nunca tiene esa edad). */
    private suspend fun barrer() {
        val vivos = dao.archivosVivos().toSet()
        val limite = ahora() - UN_DIA
        dirCola.listFiles()?.filter { it.isFile && it.lastModified() < limite && it.absolutePath !in vivos }?.forEach { it.delete() }
    }

    private fun campo(json: String?, nombre: String): String? = runCatching {
        JsonParser.parseString(json).asJsonObject[nombre]?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val UN_DIA = 24 * 3_600_000L
        const val SIETE_DIAS = 7 * UN_DIA
        const val GRACIA = "GRACIA"
        const val NECESITA_PIN = "NECESITA_PIN"
        const val REINTENTABLE = "REINTENTABLE"
    }
}

// Los pasos que comparten la ruta normal y la gracia (Tarea 7).

internal const val ESPERA_MAXIMA = 15 * 60_000L
internal val TIPOS = setOf(
    TipoPieza.VENTA, TipoPieza.ENTRADA, TipoPieza.SALIDA, TipoPieza.CUSTODIA_ACEPTAR, TipoPieza.CUSTODIA_RECHAZAR, TipoPieza.EVIDENCIA,
)

/** Sube las fotos que faltan y marca cada una en Room en cuanto Storage la confirma. `Subida` = todas arriba. */
internal suspend fun PiezaDao.subirFotos(pieza: Pieza, fotos: SubidorDeFotos): Pair<Pieza, ResultadoFoto> {
    var p = pieza
    val lista = fotosDe(p.fotosJson).toMutableList()
    for ((i, f) in lista.withIndex()) {
        if (f.subida) continue
        val r = fotos.subir(f)
        if (r != ResultadoFoto.Subida) return p to r
        lista[i] = f.copy(subida = true)
        p = p.copy(fotosJson = gsonCola.toJson(lista)).also { actualizar(it) }
    }
    return p to ResultadoFoto.Subida
}

/** HECHA o AVISO; `resultadoJson` = el cuerpo efectivo (R7-4). Sus fotos ya no hacen falta. */
internal suspend fun PiezaDao.hecha(p: Pieza, cuerpo: String) {
    val pidioNumero = p.tipo == TipoPieza.EVIDENCIA &&
        gsonCola.fromJson(p.evidenciaJson, EvidenciaPendiente::class.java).let { it.linkingNumber != null || it.portedNumber != null }
    val l = desenlaceDe(p.tipo, cuerpo, pidioNumero)
    marcarHecha(p.copy(rechazoCode = l.code, rechazoMensaje = l.texto, rechazoDatosJson = l.datosJson), cuerpoEfectivo(cuerpo), l.desenlace.name)
    fotosDe(p.fotosJson).forEach { File(it.archivo).delete() }
}

internal suspend fun PiezaDao.rechazar(p: Pieza, code: String, mensaje: String, datosJson: String? = null) = actualizar(
    p.copy(estado = EstadoPieza.RECHAZADA, rechazoCode = code, rechazoMensaje = mensaje, rechazoDatosJson = datosJson),
)

/** `min(15 min, 5 s · 2^intentos)`, o lo que pidió el servidor (también tope de 15 min). */
internal suspend fun PiezaDao.esperar(p: Pieza, pedidaMs: Long?, ahora: Long) {
    val espera = (pedidaMs ?: (5_000L shl p.intentos.coerceAtMost(8))).coerceAtMost(ESPERA_MAXIMA)
    actualizar(p.copy(intentos = p.intentos + 1, siguienteIntentoAt = ahora + espera))
}
