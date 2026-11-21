package com.avoqado.cobropropio.motor

import com.avoqado.cobropropio.blumon.ClaseDelReverso
import com.avoqado.cobropropio.blumon.Clasificador
import com.avoqado.cobropropio.blumon.CuerpoCongelado
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.libreta.Arbitraje
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.EstadoDelReverso
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.OrigenDeLaAprobacion
import com.avoqado.cobropropio.libreta.ResultadoParaReportar
import com.avoqado.cobropropio.red.Respuesta
import com.avoqado.cobropropio.servidor.PermisoDeReverso
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

private val AUTORIZACION = Regex("^[A-Za-z0-9]{1,12}$")
private val PAYMENT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
private const val TX_008 = "TX_008"

/**
 * r2 familia B: el plazo del permiso lo decide UN reloj, el de [com.avoqado.cobropropio.servidor.ReversoDelServidor.pedirPermiso],
 * que recibe `esperaPermiso` entero y guarda el código que llegó (un 409 o un 500 con el cuerpo atorado veta). Esta cerca sólo
 * cuida de una implementación que no cumpla su plazo: como no sabe si llegó un código, nunca es `SinRespuesta`, sino
 * `ErrorDelServidor` (no reversa, por confirmar). En la nuestra no se alcanza.
 */
internal const val CERCA_DEL_PERMISO = 2_000L

/**
 * B a F de la tabla de decisiones: arbitraje, permiso del SERVIDOR, vueltas del reverso con su techo, el acuse y el plazo.
 * El techo es un esfuerzo de puntualidad (parada 2): la corrección sale de la clasificación de cada respuesta, también la
 * que llega tarde.
 */
internal suspend fun MotorDeCobro.caminoDelReverso(r: MotorDeCobro.Registro, salida: MotorDeCobro.Salida, t0: Long) {
    val id = r.id
    val tope = t0 + relojes.techoDelReverso
    // Si el reverso ni se puede armar, nunca sale un byte: no se pide permiso ni se compromete.
    val armable = runCatching { salida.cuerpo.conMotivoDeReverso() }.isSuccess
    // B: sin tiempo para el permiso no se compromete (dejaría una intención huérfana); la CAS a NO_SE hace de arbitraje.
    if (!armable || ahora() >= tope - relojes.esperaPermiso) return alPlazo(r)
    when (val a = enLibreta { libreta.comprometerAReversar(id) }) {
        Arbitraje.Comprometido -> Unit
        // Un `YaResuelta` con la fila todavía ENVIADO + COMPROMETIDO no es una resolución: ya estaba comprometida.
        is Arbitraje.YaResuelta ->
            if (a.fila.estado != EstadoDelIntento.ENVIADO || a.fila.reverso != EstadoDelReverso.COMPROMETIDO) return emitirFila(r)
        else -> return emitirFila(r)
    }

    // C. Permiso. V = el origen congelado al anotar, o el permiso concedido (que se guarda durable antes del primer POST).
    var vinculado = r.venta.origen == Origen.VINCULADO_AL_POS
    val permiso = withTimeoutOrNull(relojes.esperaPermiso + CERCA_DEL_PERMISO) {
        intentar { servidor.pedirPermiso(r.solicitud.venueId, id, relojes.esperaPermiso.milliseconds) } ?: PermisoDeReverso.SinRespuesta
    } ?: PermisoDeReverso.ErrorDelServidor
    when (permiso) {
        is PermisoDeReverso.Concedido -> {
            enLibreta { libreta.guardarPermisoConcedido(id) }
            vinculado = true
        }
        is PermisoDeReverso.YaCobrado -> {
            // Ola final, 4ª parte · 7 (r25): importe y propina ≥ 0 y una suma que no desborde; si no, es como si no cuadrara.
            val propina = permiso.tipCents ?: 0
            val total = permiso.amountCents?.takeIf { it >= 0 && propina >= 0 }
                ?.let { runCatching { Math.addExact(it, propina) }.getOrNull() }
            // Lo del SERVIDOR también se coteja contra la tarjeta de la lectura (r1 R1-8): un paymentId con dígitos del PAN no
            // se guarda ni cuenta como evidencia; una autorización así se descarta.
            val deLaTarjeta = salida.esperado::contieneDatoDeLaTarjeta
            if (total != r.venta.importeCentavos || !PAYMENT_ID.matches(permiso.paymentId) || deLaTarjeta(permiso.paymentId)) {
                return sinReversar(r)
            }
            val autorizacion = permiso.authorizationNumber?.takeIf { AUTORIZACION.matches(it) && !deLaTarjeta(it) }
            return cerrarDesdeLaDuda(r) {
                it.copy(
                    estado = EstadoDelIntento.APROBADO, origenDeLaAprobacion = OrigenDeLaAprobacion.SERVIDOR,
                    paymentId = permiso.paymentId, autorizacion = autorizacion,
                    reverso = EstadoDelReverso.NO_CONCLUYENTE,
                )
            }
        }
        is PermisoDeReverso.YaResuelto -> {
            if (permiso.estado != EstadoDelReversoEnServidor.REVERSED) return sinReversar(r)
            return cerrarDesdeLaDuda(r) {
                it.copy(
                    estado = EstadoDelIntento.NO_SE_COBRO, estadoEnServidor = EstadoDelReversoEnServidor.REVERSED,
                    reverso = EstadoDelReverso.NO_CONCLUYENTE,
                )
            }
        }
        is PermisoDeReverso.EvidenciaPendiente, PermisoDeReverso.NoEsBlumon, is PermisoDeReverso.Invalido,
        PermisoDeReverso.ErrorDelServidor, PermisoDeReverso.SinCredencial -> return sinReversar(r)
        PermisoDeReverso.NoEncontrado, PermisoDeReverso.ServidorSinSdkPropio -> if (vinculado) return sinReversar(r)
        PermisoDeReverso.SinRespuesta -> Unit
    }

    // D. Cada vuelta: plazo ⇒ reclamo durable ⇒ plazo ⇒ POST ⇒ clasificación, y su cierre en una transacción. El cuerpo es
    // siempre el congelado; sólo el token cambia si una vuelta contestó 401/403 y se pudo renovar a tiempo.
    var congelado = salida.congelado
    // Si la venta misma contestó 401/403, ya la primera vuelta pide token nuevo; si no, sale con el congelado.
    var renovarToken = r.ventaConTokenMalo
    while (ahora() < tope) {
        if (renovarToken) {
            renovarToken = false
            tokenNuevo(congelado.token, tope - ahora())?.let { congelado = CuerpoCongelado(congelado.cuerpo, it) }
            if (ahora() >= tope) break
        }
        if (!enLibreta { libreta.reclamarReverso(id) }) break
        val (clase, tokenMalo) = if (ahora() >= tope) ClaseDelReverso.NoEnviado to false else unaVuelta(congelado, salida.esperado, tope)
        if (tokenMalo) {
            tokenVencido()
            renovarToken = true
        }
        when (clase) {
            is ClaseDelReverso.Confirmado -> {
                enLibreta {
                    libreta.cerrarVuelta(id, clase, ResultadoParaReportar.CONFIRMADO, if (vinculado) null else EstadoDelIntento.NO_SE_COBRO)
                }
                return if (vinculado) esperarAcuse(r, t0) else emitirFila(r)
            }
            is ClaseDelReverso.Rechazado -> {
                enLibreta { libreta.cerrarVuelta(id, clase, ResultadoParaReportar.RECHAZADO, EstadoDelIntento.NO_SE) }
                return emitirFila(r)
            }
            is ClaseDelReverso.Reintentar -> {
                enLibreta { libreta.cerrarVuelta(id, clase, null, null) }
                if (ahora() + relojes.pausaEntreReversos >= tope) break
                delay(relojes.pausaEntreReversos)
            }
            ClaseDelReverso.NoEnviado -> {
                enLibreta { libreta.cerrarVuelta(id, clase, null, null) }
                break
            }
        }
    }
    alPlazo(r)
}

/**
 * Una excepción del puerto pudo pasar con bytes escritos: es un «sin respuesta» (pudo entrar), nunca un «no salió». La vuelta
 * se clasifica igual que siempre; el segundo valor dice si contestó 401/403 (el token congelado ya no sirve).
 *
 * R3-2: lo que llegó nunca lo tira el reloj. El transporte deja la respuesta YA CLASIFICADA en el [buzon] en cuanto la lee,
 * antes de reanudar esta corrutina; si el reloj vence después, se devuelve lo del buzón (un `REVERSADA` leído confirma).
 */
private suspend fun MotorDeCobro.unaVuelta(
    congelado: CuerpoCongelado,
    esperado: Clasificador.Esperado,
    tope: Long,
): Pair<ClaseDelReverso, Boolean> {
    fun clasificar(r: Respuesta): Pair<ClaseDelReverso, Boolean> {
        if (Clasificador.pideLlavesNuevas(r)) pedirLlavesNuevas() // T8 arreglos 1 (D): sólo una bandera
        return Clasificador.reverso(r, esperado, catalogo, relojDePared) to (r is Respuesta.Http && r.codigo in MotorDeCobro.TOKEN_VENCIDO)
    }
    val buzon = AtomicReference<Pair<ClaseDelReverso, Boolean>?>(null)
    val respuesta = withTimeoutOrNull(relojes.esperaReverso) {
        intentar {
            blumon.reversar(congelado, relojes.esperaReverso.milliseconds, tope) { buzon.compareAndSet(null, clasificar(it)) }
        } ?: Respuesta.SinRespuesta("EXCEPCION")
    }
    return buzon.get() ?: clasificar(respuesta ?: Respuesta.SinRespuesta("TIEMPO"))
}

/** E con V: el `reversed` del SERVIDOR, dentro de lo que le queda a la cajera; si llega después, se publica tarde. */
private suspend fun MotorDeCobro.esperarAcuse(r: MotorDeCobro.Registro, t0: Long) {
    val id = r.id
    val entrega = fondo.async { intentar { cola.entregar(id) } }
    val listo = withTimeoutOrNull(t0 + relojes.peorCasoCajera - ahora()) { entrega.await() }
    if (listo?.fila?.estado != EstadoDelIntento.NO_SE_COBRO) {
        enLibreta { ponerNoSe(id) }
        if (listo == null) {
            // Por el candado del Registro (r1 R1-7): si gana al «por confirmar» de abajo, éste ya no se publica viejo.
            fondo.launch { if (entrega.await()?.fila?.estado == EstadoDelIntento.NO_SE_COBRO) r.emitir(EventoDeCobro.NoSeCobro) }
        }
    }
    emitirFila(r)
}

/** F. Al vencer el plazo, o si no alcanzó para pedir permiso. Siempre `NO_SE` + `PorConfirmar`. */
private suspend fun MotorDeCobro.alPlazo(r: MotorDeCobro.Registro) {
    val id = r.id
    enLibreta {
        val f = libreta.buscar(id)
        when {
            f == null -> Unit
            f.reverso == EstadoDelReverso.COMPROMETIDO -> libreta.cerrarSinVuelta(id)
            f.reverso == EstadoDelReverso.NO_CONCLUYENTE && f.pudoEntrar ->
                libreta.cerrarVuelta(id, ClaseDelReverso.NoEnviado, ResultadoParaReportar.PUDO_ENTRAR, EstadoDelIntento.NO_SE)
            f.reverso == EstadoDelReverso.NO_CONCLUYENTE && f.ultimoCodigoReverso == TX_008 ->
                libreta.cerrarVuelta(id, ClaseDelReverso.NoEnviado, ResultadoParaReportar.RECHAZADO, EstadoDelIntento.NO_SE)
            else -> ponerNoSe(id) // ningún reverso salió
        }
        Unit
    }
    emitirFila(r)
}

/** C sin reverso: se cierra desde `COMPROMETIDO`, sin resultado bancario ni reporte. */
private suspend fun MotorDeCobro.sinReversar(r: MotorDeCobro.Registro) {
    enLibreta { libreta.cerrarSinVuelta(r.id) }
    emitirFila(r)
}

/** Una evidencia concluyente del SERVIDOR, por CAS sobre el estado de duda actual (ENVIADO, o NO_SE si ya se soltó). */
private suspend fun MotorDeCobro.cerrarDesdeLaDuda(r: MotorDeCobro.Registro, cambio: (IntentoEntity) -> IntentoEntity) {
    enLibreta { cerrarDesdeLaDuda(r.id, cambio) }
    emitirFila(r)
}

private fun MotorDeCobro.cerrarDesdeLaDuda(id: AttemptId, cambio: (IntentoEntity) -> IntentoEntity) {
    repeat(3) {
        val f = libreta.buscar(id) ?: return
        if (f.estado != EstadoDelIntento.ENVIADO && f.estado != EstadoDelIntento.NO_SE) return
        if (libreta.transicionar(id, f.estado, cambio) != null) return
    }
}
