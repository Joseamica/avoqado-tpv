package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton holder for in-flight payment state. `AngelPayPaymentViewModel`
 * writes (`setCharging(true)` around its Charging state, `setCharging(false)`
 * on completion/error). [AngelPayMerchantRepository] reads via the
 * [PaymentStateProvider] interface.
 *
 * AtomicBoolean is sufficient — no need for a coroutine-aware StateFlow
 * because the reader (Mutex-guarded switch path) only needs a current snapshot.
 */
@Singleton
class PaymentStateHolder @Inject constructor() : PaymentStateProvider {
    private val charging = AtomicBoolean(false)
    /**
     * 🔴 29-sep-2026: también una DEVOLUCIÓN hablando con AngelPay cuenta como «cobrando» para quien lo lee (no cambiar de
     * sesión ni de comercio a media llamada, el heartbeat, un segundo pedido de devolución). La devolución NO escribe
     * [charging]: si lo soltara al terminar, podía dejar sin protección a una venta que arrancó después (auditoría del 29-sep).
     */
    override fun isCharging(): Boolean = charging.get() || isRefundInFlight()
    fun setCharging(value: Boolean) { charging.set(value) }

    // Non-resolved (working) AngelPay screen state — see [PaymentStateProvider.isChargeAttemptActive].
    // Published by AngelPayPaymentViewModel from its own state stream (single funnel).
    private val chargeAttemptActive = AtomicBoolean(false)
    override fun isChargeAttemptActive(): Boolean = chargeAttemptActive.get()
    fun setChargeAttemptActive(value: Boolean) { chargeAttemptActive.set(value) }

    // 🔴 D666 (21-nov): al MORIR, una pantalla de cobro (PAX) sólo apaga estas banderas si no hay otra VIVA que naciera después.
    // Una pantalla muere (`onCleared`) ~700 ms después de salir, cuando termina la animación de la navegación; en ese rato otra
    // pantalla de cobro ya pudo encender las suyas, y apagarlas le quitaba la protección a esa venta. «Viva», no «la última que
    // nació»: si la nueva ya murió, la vieja que muere después sí apaga (si no, una bandera encendida quedaría pegada).
    private var ultimaPantallaDeCobro = 0L
    private val pantallasDeCobroVivas = HashSet<Long>()

    /** La pantalla de cobro que nace se anota; devuelve su número. */
    @Synchronized
    fun entraPantallaDeCobro(): Long = (++ultimaPantallaDeCobro).also { pantallasDeCobroVivas.add(it) }

    /** Al morir la pantalla [pantalla]: apaga las banderas sólo si no queda viva otra que naciera después. `true` si las apagó. */
    @Synchronized
    fun salePantallaDeCobro(pantalla: Long, soltarVentanaDelDinero: Boolean): Boolean {
        pantallasDeCobroVivas.remove(pantalla)
        return soltarBanderasDe(pantalla, soltarVentanaDelDinero)
    }

    /**
     * La pantalla [pantalla] suelta las banderas sin morir, o después de muerta (el `finally` del registro de una tarjeta, R86, que
     * sigue tras salir del éxito): sólo si no hay viva otra que naciera después (Codex D666 r2 #2). `true` si las apagó.
     */
    @Synchronized
    fun soltarBanderasDe(pantalla: Long, soltarVentanaDelDinero: Boolean): Boolean {
        if (pantallasDeCobroVivas.any { it > pantalla }) return false
        if (soltarVentanaDelDinero) charging.set(false)
        chargeAttemptActive.set(false)
        return true
    }

    // 🔴 Founder, 29-sep-2026: «nada puede detener las ventas». Una devolución de AngelPay marca aquí el rato en que habla con
    // el procesador (segundos) para que un cobro del POS no le arranque la pantalla. Reloj MONOTÓNICO y tope de 2 min: aunque
    // algo no la soltara, la marca se apaga sola y nunca traba las ventas. 0 = sin devolución en curso.
    private val devolucionDesde = AtomicLong(0L)
    override fun isRefundInFlight(): Boolean = devolucionEnCurso(System.nanoTime())
    fun marcarDevolucionEnCurso(ahoraNanos: Long = System.nanoTime()) { devolucionDesde.set(if (ahoraNanos == 0L) 1L else ahoraNanos) }
    fun terminarDevolucionEnCurso() { devolucionDesde.set(0L) }
    internal fun devolucionEnCurso(ahoraNanos: Long): Boolean {
        val desde = devolucionDesde.get()
        return desde != 0L && ahoraNanos - desde < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(TOPE_DEVOLUCION_EN_CURSO_MS)
    }

    companion object {
        /** Lo más que puede durar la marca de «devolución en curso» aunque nadie la suelte. */
        const val TOPE_DEVOLUCION_EN_CURSO_MS = 120_000L
    }
}
