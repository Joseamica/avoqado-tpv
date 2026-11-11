package com.jaac.avoqado_campo.cola

import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GraciaBody
import com.jaac.avoqado_campo.red.Llamada
import com.jaac.avoqado_campo.red.RespuestaDelServidor
import com.jaac.avoqado_campo.red.clasificar
import com.jaac.avoqado_campo.red.llamarSeguro
import com.jaac.avoqado_campo.red.objetoJson
import kotlinx.coroutines.sync.withLock

// La evidencia de la gracia nombra su venta por la llave (el celular desvinculado no siempre supo el pago); Gson omite los null.
private data class CuerpoEvidenciaDeGracia(
    val ventaLlave: String, val photoUrls: List<String>, val photoLabel: String, val replaceIndex: Int,
    val linkingNumber: String?, val portedNumber: String?, val idempotencyKey: String, val captureSeq: Long,
    val captureDeviceId: String, val capturedAt: String,
)

/**
 * La gracia (bloque 1, A5): lo que quedó en la cola de un celular desvinculado o dado de baja sube por `POST tpv/campo/gracia`
 * con la credencial y con el MISMO texto cuya huella reportó el último latido. Mismo [MutexDeSubida] que el [Subidor], mismas
 * barreras y fotos; sin la regla de la sucursal de la sesión. Lo que el SERVIDOR retiene (202) espera al supervisor y la pasada
 * sigue con la siguiente: el supervisor decide cada una.
 */
class SubidorDeGracia(
    private val dao: PiezaDao,
    private val api: CampoApi,
    private val quienSoy: QuienSoy,
    private val fotos: SubidorDeFotos,
    private val mutex: MutexDeSubida,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    suspend fun vaciar(): ResultadoDeVaciado = mutex.mutex.withLock { pasada() }

    private suspend fun pasada(): ResultadoDeVaciado {
        var subidas = 0
        var copia = false
        fun corte(motivo: String) = ResultadoDeVaciado(subidas, motivo, false, copia)

        for (id in dao.siguientesParaSubir().map { it.id }) {
            var p = dao.porId(id)?.takeIf { it.estado == EstadoPieza.PENDIENTE || it.estado == EstadoPieza.RETENIDA } ?: continue

            // Se lee antes de cada pieza: un USA_TU_SESION o un 401 de la anterior ya la cambió.
            val estado = quienSoy.identidad.value.estado
            if (estado != EstadoDelCelular.DESVINCULADO && estado != EstadoDelCelular.DADO_DE_BAJA) return corte(estado.name)
            val credencial = quienSoy.credencial ?: return corte(EstadoDelCelular.DESCONOCIDO.name)

            // Lo retenido espera su turno sin trabar a las demás; lo que espera por red corta (P1).
            val t = ahora()
            if (p.siguienteIntentoAt > t && p.siguienteIntentoAt - t <= ESPERA_MAXIMA) {
                if (p.estado == EstadoPieza.RETENIDA) continue else return corte(REINTENTABLE)
            }

            // R8-3 + R9-B1: la corrección suelta no tiene `ventaLlave`; la gracia no la acepta.
            if (p.tipo == TipoPieza.EVIDENCIA && p.dependeDe == null) {
                dao.rechazar(p, CORRECCION_SIN_SESION, TEXTO_CORRECCION_SIN_SESION)
                continue
            }
            if (p.dependeDe != null && dao.porLlave(p.dependeDe!!)?.estado != EstadoPieza.HECHA) continue
            if (p.tipo !in TIPOS) {
                dao.rechazar(p, "TIPO_DESCONOCIDO", "Esta versión de la app no sabe subir esto. Avísale a tu supervisor.")
                continue
            }

            val (conFotos, foto) = dao.subirFotos(p, fotos)
            p = conFotos
            when (foto) {
                ResultadoFoto.Subida -> Unit
                ResultadoFoto.Reintentable -> { dao.esperar(p, null, ahora()); return corte(REINTENTABLE) }
                is ResultadoFoto.Definitiva -> { dao.rechazar(p, "FOTO_NO_SUBIO", foto.mensaje); continue }
            }

            // La EVIDENCIA se arma UNA vez y se guarda antes del primer envío: los reintentos mandan lo mismo.
            val cuerpo = p.cuerpoJson ?: cuerpoDeEvidencia(p).also { p = p.copy(cuerpoJson = it).also { c -> dao.actualizar(c) } }

            val generacion = quienSoy.generacion
            val r = when (val ll = llamarSeguro { api.gracia(credencial, GraciaBody(p.tipo, p.venueId, cuerpo)) }) {
                is Llamada.Contesto -> ll.r
                else -> return corte(SIN_RED) // sin red o un portal cautivo
            }
            val texto = (r.body() ?: r.errorBody())?.use { it.string() }
            when (val k = clasificar(r.code(), texto, r.headers()["Retry-After"])) {
                is RespuestaDelServidor.Ok -> {
                    val o = objetoJson(k.cuerpo)!!
                    when {
                        o.txt("retenido") == "true" -> dao.actualizar(
                            p.copy(
                                estado = EstadoPieza.RETENIDA, rechazoCode = o.txt("motivo"), rechazoMensaje = EN_REVISION,
                                rechazoDatosJson = null, siguienteIntentoAt = ahora() + CINCO_MIN,
                            ),
                        )
                        o.txt("resuelto") == "DESCARTADO" -> dao.enTransaccion {
                            dao.actualizar(p.copy(rechazoCode = null, rechazoMensaje = o.txt("nota"), rechazoDatosJson = null))
                            dao.descartarConHijos(
                                p.id, if (p.tipo == TipoPieza.ENTRADA) "SU_ENTRADA_SE_DESCARTO" else "SU_VENTA_SE_DESCARTO",
                            )
                        }
                        // H4: ni `aplicadoEnGracia` ni `resuelto: APLICADO` sin su resultado cuentan: espera, como sin red.
                        !traeSuResultado(p.tipo, k.cuerpo) -> { dao.esperar(p, null, ahora()); return corte(REINTENTABLE) }
                        else -> { // aplicadoEnGracia o resuelto: APLICADO (R2-3, R7-4)
                            dao.hecha(p, k.cuerpo)
                            subidas++
                            if (p.tipo == TipoPieza.VENTA || p.tipo.startsWith("CUSTODIA_")) copia = true
                        }
                    }
                }
                is RespuestaDelServidor.Reintentable -> { dao.esperar(p, k.reintentaEnMs, ahora()); return corte(REINTENTABLE) }
                is RespuestaDelServidor.Definitiva -> {
                    if (k.code == USA_TU_SESION) {
                        quienSoy.guardarSi(generacion) { volverDeLaBaja() } // R8-2: DESVINCULADO no cambia
                        return corte(USA_TU_SESION)
                    }
                    dao.rechazar(p, k.code ?: "HTTP_${k.status}", "Pídele a tu supervisor que lo revise", k.datosJson)
                }
                is RespuestaDelServidor.Sesion -> { // R6-B1: cualquier 401 corta sin tocar la pieza
                    if (k.code == "CELULAR_DESCONOCIDO") quienSoy.guardarSi(generacion) { marcarDesconocido() }
                    return corte(k.code ?: "HTTP_${k.status}")
                }
                RespuestaDelServidor.SinRed -> return corte(SIN_RED)
            }
        }
        return ResultadoDeVaciado(subidas, null, false, copia)
    }

    private fun cuerpoDeEvidencia(p: Pieza): String {
        val e = gsonCola.fromJson(p.evidenciaJson, EvidenciaPendiente::class.java)
        return gsonCola.toJson(
            CuerpoEvidenciaDeGracia(
                p.dependeDe!!, listOf(e.fotoUrl), e.photoLabel, e.replaceIndex, e.linkingNumber, e.portedNumber, p.llave,
                e.captureSeq, e.captureDeviceId, e.capturedAt,
            ),
        )
    }

    private fun com.google.gson.JsonObject.txt(campo: String): String? = get(campo)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
        /** Lo que dice la pantalla mientras sube la gracia. */
        const val TEXTO_DE_LA_PANTALLA =
            "Este celular ya no está vinculado. Lo que tenías guardado se está subiendo para que tu supervisor lo revise."
        const val CORRECCION_SIN_SESION = "CORRECCION_SIN_SESION"
        const val TEXTO_CORRECCION_SIN_SESION =
            "El celular se desvinculó antes de subir esta corrección. Cuando tu celular esté activado otra vez, la venta va a " +
                "volver a salir en «Mis SIMs» como «Revisar» y la corriges de nuevo."
        const val EN_REVISION = "En revisión de tu supervisor"
        const val USA_TU_SESION = "USA_TU_SESION"
        private const val SIN_RED = "SIN_RED"
        private const val REINTENTABLE = "REINTENTABLE"
        private const val CINCO_MIN = 5 * 60_000L
    }
}
