package com.jaac.avoqado_campo.gps

import com.jaac.avoqado_campo.cola.MutexDeSubida
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import com.jaac.avoqado_campo.red.PingBody
import com.jaac.avoqado_campo.red.RespuestaDelServidor
import com.jaac.avoqado_campo.red.ResultadoConSesion
import com.jaac.avoqado_campo.red.clasificar
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Manda los pings de la sucursal de la sesión (B4), uno por uno y sin llave: lo que el SERVIDOR contestó (2xx o un rechazo
 * 4xx, p. ej. 403 de una sucursal sin seguimiento) se borra; sin red, 408/429 o 5xx se queda y la pasada se corta.
 * Lo de más de 24 h se tira sin mandarse, y con el celular desvinculado se tira todo. Lo llama `TrabajoDeSubida`, después
 * de soltar el Subidor. H3: corre entero bajo el [MutexDeSubida], como el Subidor: el borrado del celular espera a que
 * termine, así un ping leído antes del borrado nunca sale con la sesión de la persona que se active después.
 */
class EnviadorDePings(
    private val dao: PingDao,
    private val api: CampoApi,
    private val guardia: GuardiaDeSesion,
    private val quienSoy: QuienSoy,
    private val mutex: MutexDeSubida,
    private val ahora: () -> Long,
) {
    suspend fun mandar(): Unit = mutex.mutex.withLock {
        dao.borrarAntesDe(ahora() - UN_DIA)
        when (quienSoy.estado) {
            EstadoDelCelular.VINCULADO -> Unit
            EstadoDelCelular.DESVINCULADO, EstadoDelCelular.DESCONOCIDO -> { dao.borrarTodos(); return@withLock }
            else -> return@withLock
        }
        val venueId = quienSoy.sesion?.venueId ?: return@withLock
        for (p in dao.deSucursal(venueId, LOTE)) {
            val cuerpo = PingBody(p.lat, p.lon, p.precision.toDouble(), Instant.ofEpochMilli(p.capturadoAt).toString())
            val r = (guardia.conSesion(venueId) { api.ubicacion(it, venueId, cuerpo) } as? ResultadoConSesion.Respuesta)?.r
                ?: return@withLock
            val texto = if (r.isSuccessful) r.body()?.string() else r.errorBody()?.string()
            when (clasificar(r.code(), texto, null)) {
                is RespuestaDelServidor.Ok, is RespuestaDelServidor.Definitiva -> dao.borrar(p.id)
                else -> return@withLock
            }
        }
    }

    private companion object {
        const val UN_DIA = 24 * 3_600_000L
        const val LOTE = 200 // 48 por día a uno cada 30 min
    }
}
