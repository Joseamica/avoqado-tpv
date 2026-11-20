package com.jaac.avoqado_tpv.features.checkout.data

import android.content.Context
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import retrofit2.http.POST
import retrofit2.http.Path
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D36 (hz:e82938e1:D36, encargo da7275 · 20-nov-2026): «Cobrar» crea la venta en el SERVIDOR al tocar «Cobrar». Si la cajera toca
 * «atrás» en la pantalla de cobro antes de pasar la tarjeta, esa venta se quedaba abierta con sus productos para siempre (106 en 90
 * días, 91 en Mindform) y ensuciaba «Cuentas por cobrar». Desde D34 ese «atrás» abre un Cobrar nuevo (PAX) o el mismo carrito (Nexgo),
 * así que tampoco se retomaba: la cajera la cobraba como venta NUEVA.
 *
 * Aquí se RETIRA (el servidor la cancela con su bitácora; nunca la borra), y sólo si esta terminal no tiene NINGÚN rastro de un intento
 * de cobro de esa venta ([PaymentAttemptDao.hayRastroDeCobroDeLaVenta]: libreta en cualquier estado, cola, cripto). El servidor
 * vuelve a comprobar lo suyo bajo el candado (ningún `Payment`, ninguna solicitud del POS, de mostrador, sin mesa, < 24 h).
 *
 * Sin red: el retiro se anota en el aparato ANTES de salir a la red y se reintenta al volver a abrir Cobrar y cuando vuelve la red;
 * pasadas 24 h se olvida (el servidor ya no lo aceptaría y la venta queda como hoy). Un rechazo del servidor (4xx) es definitivo.
 * La cajera no ve nada distinto: el «atrás» navega igual, sin esperar a la red.
 */
interface RetiroDeVentaApi {
    @POST("tpv/venues/{venueId}/orders/{orderId}/retirar-sin-cobro")
    suspend fun retirar(@Path("venueId") venueId: String, @Path("orderId") orderId: String): Response<Unit>
}

/** Lo pendiente de retirar, en el aparato: un libro chico (una venta por «atrás»), escrito con `commit()`. */
interface LibroDeRetiros {
    /** `true` si quedó escrito en disco. */
    fun anotar(pendiente: RetiroPendiente): Boolean
    fun quitar(orderId: String)
    fun pendientes(): List<RetiroPendiente>
}

data class RetiroPendiente(val orderId: String, val venueId: String, val anotadoEn: Long)

enum class DesenlaceDelRetiro {
    /** El servidor la retiró, o ya estaba retirada. */
    RETIRADA,

    /** Esta terminal tiene rastro de un intento de cobro: no se pide nada. */
    CON_RASTRO_DE_COBRO,

    /** El servidor dijo que no (ya cobrada, con solicitud del POS, vieja…): la venta queda como estaba. */
    RECHAZADA,

    /** Sin red o error del servidor: queda anotada para reintentarse. */
    PENDIENTE,

    /** No se pudo leer la libreta o anotar el retiro: no se pide nada (falla cerrado, la venta queda como hoy). */
    SIN_CERTEZA,
}

class RetiroDeVentaSinCobroCore(
    private val hayRastroDeCobro: suspend (orderId: String) -> Boolean,
    private val libro: LibroDeRetiros,
    private val enviar: suspend (venueId: String, orderId: String) -> Response<Unit>,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    /** Sólo el ENVÍO va en fila: anotar un retiro nuevo nunca espera a que otro termine de salir a la red (Codex D36 r2 #6). */
    private val candado = Mutex()

    suspend fun retirarSiNuncaSeCobro(venueId: String, orderId: String): DesenlaceDelRetiro {
        when (rastro(orderId)) {
            null -> return DesenlaceDelRetiro.SIN_CERTEZA
            true -> {
                Timber.i("🛒 D36: la venta %s tiene rastro de cobro en esta terminal: no se retira", orderId)
                return DesenlaceDelRetiro.CON_RASTRO_DE_COBRO
            }
            false -> Unit
        }
        // ANTES de la red y SIN esperar el candado del envío: si el proceso muere o no hay red, el retiro sigue pendiente.
        val pendiente = RetiroPendiente(orderId, venueId, reloj())
        if (!libro.anotar(pendiente)) {
            Timber.w("🛒 D36: no se pudo anotar el retiro de %s: no se pide", orderId)
            return DesenlaceDelRetiro.SIN_CERTEZA
        }
        return candado.withLock {
            // Un reintento pudo haberlo mandado mientras se esperaba el candado: si ya no está anotado, ya salió.
            if (libro.pendientes().none { it.orderId == orderId }) DesenlaceDelRetiro.RETIRADA else mandar(pendiente)
        }
    }

    /** Reintenta lo anotado (al abrir Cobrar, al volver la red). Lo de más de 24 h se olvida: el servidor ya no lo aceptaría. */
    suspend fun reintentarPendientes(): Unit = candado.withLock {
        for (pendiente in libro.pendientes()) {
            if (reloj() - pendiente.anotadoEn > PLAZO_MS) {
                Timber.i("🛒 D36: el retiro de %s ya venció: se olvida (la venta queda como estaba)", pendiente.orderId)
                libro.quitar(pendiente.orderId)
                continue
            }
            when (rastro(pendiente.orderId)) {
                null -> continue
                true -> { libro.quitar(pendiente.orderId); continue }
                false -> Unit
            }
            if (mandar(pendiente) == DesenlaceDelRetiro.PENDIENTE) return@withLock // sin red: lo demás tampoco saldría
        }
    }

    private suspend fun rastro(orderId: String): Boolean? = try {
        hayRastroDeCobro(orderId)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Timber.e(e, "🛒 D36: no se pudo leer la libreta de %s", orderId)
        null
    }

    private suspend fun mandar(pendiente: RetiroPendiente): DesenlaceDelRetiro {
        val respuesta = try {
            enviar(pendiente.venueId, pendiente.orderId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "🛒 D36: sin respuesta al retirar %s: queda pendiente", pendiente.orderId)
            return DesenlaceDelRetiro.PENDIENTE
        }
        return when {
            respuesta.isSuccessful -> {
                Timber.i("🛒 D36: venta %s retirada (nunca se cobró)", pendiente.orderId)
                libro.quitar(pendiente.orderId)
                DesenlaceDelRetiro.RETIRADA
            }
            // 401/408/429 y 5xx son de paso; lo demás (403, 404, 409…) es la respuesta del servidor y no cambia al reintentar.
            respuesta.code() in CODIGOS_DE_PASO || respuesta.code() >= 500 -> DesenlaceDelRetiro.PENDIENTE
            else -> {
                Timber.i("🛒 D36: el servidor no retiró %s (HTTP %d): queda como estaba", pendiente.orderId, respuesta.code())
                libro.quitar(pendiente.orderId)
                DesenlaceDelRetiro.RECHAZADA
            }
        }
    }

    companion object {
        const val PLAZO_MS = 24L * 60 * 60 * 1000
        private val CODIGOS_DE_PASO = setOf(401, 408, 429)

        /** El mismo fragmento con el que la libreta busca una venta (`retencionDeLaVenta`). */
        fun fragmentoDeLaVenta(orderId: String): String = "\"orderId\":" + Gson().toJson(orderId)
    }
}

@Singleton
class LibroDeRetirosEnPreferencias @Inject constructor(@ApplicationContext context: Context) : LibroDeRetiros {
    private val prefs = context.getSharedPreferences("retiros_de_cobrar", Context.MODE_PRIVATE)

    override fun anotar(pendiente: RetiroPendiente): Boolean =
        prefs.edit().putString(pendiente.orderId, "${pendiente.venueId}|${pendiente.anotadoEn}").commit()

    override fun quitar(orderId: String) {
        prefs.edit().remove(orderId).commit()
    }

    override fun pendientes(): List<RetiroPendiente> = prefs.all.mapNotNull { (orderId, valor) ->
        val partes = (valor as? String)?.split('|') ?: return@mapNotNull null
        val anotadoEn = partes.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        RetiroPendiente(orderId, partes[0], anotadoEn)
    }.sortedBy { it.anotadoEn }
}

/** La puerta que usa la app: corre fuera de la pantalla (el «atrás» navega sin esperar) y no se cancela al salir de ella. */
@Singleton
class RetiroDeVentaSinCobro @Inject constructor(
    dao: PaymentAttemptDao,
    libro: LibroDeRetirosEnPreferencias,
    api: RetiroDeVentaApi,
    conexion: ConnectionStateManager,
) {
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Al volver la red, lo que quedó anotado sin red sale solo (también si la cajera ya no vuelve a abrir Cobrar).
        alcance.launch {
            // Internet Y SERVIDOR: si el SERVIDOR se cayó con el WiFi vivo, también lo anotado sale cuando vuelve (Codex D36 r2 #6).
            conexion.connectionState.map { it.isFullyConnected }.distinctUntilChanged().filter { it }.collect { reintentarPendientes() }
        }
    }
    private val core = RetiroDeVentaSinCobroCore(
        hayRastroDeCobro = { orderId -> dao.hayRastroDeCobroDeLaVenta(orderId, RetiroDeVentaSinCobroCore.fragmentoDeLaVenta(orderId)) },
        libro = libro,
        enviar = { venueId, orderId -> api.retirar(venueId, orderId) },
    )

    fun alRegresarSinCobrar(venueId: String?, orderId: String?) {
        if (venueId.isNullOrBlank() || orderId.isNullOrBlank()) return
        alcance.launch { core.retirarSiNuncaSeCobro(venueId, orderId) }
    }

    fun reintentarPendientes() {
        alcance.launch {
            try {
                core.reintentarPendientes()
            } catch (e: IOException) {
                Timber.w(e, "🛒 D36: reintento de retiros sin red")
            }
        }
    }
}
