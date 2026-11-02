package com.jaac.avoqado_tpv.features.checkout.data.repository

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaGuardadaVista
import java.math.BigDecimal
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.features.checkout.data.CarritoEnCurso
import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalRepository
import com.jaac.avoqado_tpv.features.checkout.domain.CarritoAMedias
import com.jaac.avoqado_tpv.features.checkout.domain.QueHacerConElCarritoAMedias
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.checkout.domain.queHacerConElCarritoAMedias
import com.jaac.avoqado_tpv.features.checkout.domain.repository.VentaSinRedRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Procesador y seriales con que se guarda una fila de efectivo en ESTE aparato. */
data class ProcesadorDelEfectivo(val paymentProcessor: String, val blumonSerialNumber: String, val deviceSerialNumber: String?)

/**
 * De donde hoy los toma el contexto de pago en efectivo de cada sabor: PAX/Blumon (`buildFastPaymentContext` con `blumonSerial = ""`
 * y `secureStorage.getSerialNumber()`) y Nexgo (`PaymentContext.AngelPayPayment` con `TerminalConfig.serialNumber`, fila AngelPay con
 * `blumonSerialNumber = ""`). [serieGuardada] es la de `SecureStorage`; [serieDeLaTerminal] la de `TerminalConfig`.
 */
fun procesadorDelEfectivo(hayLectorBlumon: Boolean, serieGuardada: String?, serieDeLaTerminal: String): ProcesadorDelEfectivo =
    if (hayLectorBlumon) {
        ProcesadorDelEfectivo(PendingPaymentEntity.PROCESSOR_BLUMON, blumonSerialNumber = "", deviceSerialNumber = serieGuardada)
    } else {
        ProcesadorDelEfectivo(PendingPaymentEntity.PROCESSOR_ANGELPAY, blumonSerialNumber = "", deviceSerialNumber = serieDeLaTerminal)
    }

/**
 * 💵 «Cobrar» sin red: lo que Cobrar guarda EN EL APARATO. El carrito a medias vive en [CarritoEnCurso] (SharedPreferences con
 * `commit()`); la venta en $0 es una fila de `pending_payments` ([VentaLocalRepository]) que nace PENDING y sube sola (C4); la regla de
 * qué carrito restaurar es [queHacerConElCarritoAMedias].
 */
@Singleton
class VentaSinRedRepositoryImpl @Inject constructor(
    private val carritoEnCurso: CarritoEnCurso,
    private val dao: PendingPaymentDao,
    private val ventaLocalRepository: VentaLocalRepository,
    private val shiftRepository: ShiftRepository,
    private val secureStorage: SecureStorage,
    @ApplicationContext private val context: Context,
) : VentaSinRedRepository {

    override suspend fun guardarCarrito(venueId: String, llave: String, cart: CartState, cliente: Customer?): Boolean =
        carritoEnCurso.guardar(venueId, llave, cart, cliente = cliente)

    override suspend fun borrarCarrito(venueId: String) {
        carritoEnCurso.borrar(venueId)
    }

    override suspend fun carritoParaRestaurar(venueId: String): CarritoAMedias? {
        val guardado = carritoEnCurso.leerGuardado(venueId) ?: return null
        val hayFila = try {
            dao.existeFilaConLlave(guardado.llave)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Sin poder confirmarlo, restaurar podría duplicar una venta ya guardada: no se restaura, y se conserva para la próxima.
            Timber.e(e, "🛒 [VentaSinRed] No se pudo revisar si la venta a medias ya tiene fila; no se restaura")
            throw IllegalStateException("No se pudo comprobar si la venta ya quedó cobrada. Se conserva; vuelve a intentar.", e)
        }
        val decision = queHacerConElCarritoAMedias(guardado.guardadoEn, System.currentTimeMillis(), hayFila)
        if (decision == QueHacerConElCarritoAMedias.RESTAURAR && !guardado.cart.isEmpty) {
            return CarritoAMedias(guardado.llave, guardado.cart, guardado.cliente)
        }
        Timber.i("🛒 [VentaSinRed] Carrito a medias descartado (fila=$hayFila, guardadoEn=${guardado.guardadoEn}, vacío=${guardado.cart.isEmpty})")
        carritoEnCurso.borrar(venueId)
        return null
    }

    override suspend fun turnoDeLaVenta(venueId: String): String? =
        if (!shiftRepository.isShiftSystemEnabled()) null else shiftRepository.getCachedOpenShift(venueId)?.id

    override suspend fun guardarVentaEnCero(venta: VentaLocal): Result<Unit> {
        val procesador = procesadorDelEfectivo(BuildConfig.ENABLE_PAX_SDK, secureStorage.getSerialNumber(), TerminalConfig.serialNumber)
        return ventaLocalRepository.guardar(
            venta = venta,
            paymentProcessor = procesador.paymentProcessor,
            blumonSerialNumber = procesador.blumonSerialNumber,
            deviceSerialNumber = procesador.deviceSerialNumber,
            reclamada = false, // PENDING: la sube el worker (C4), nadie compite por ella
            relojMonotonoAlVender = SystemClock.elapsedRealtime(),
            arranqueAlVender = arranqueDelAparato(),
        ).map {
            // El insert ya se comprometió: agendar es un disparo adicional, nunca una razón para perder el éxito local.
            runCatching { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(context) }
                .onFailure { Timber.w(it, "No se pudo agendar la venta en cero guardada") }
            // La fila ya quedó: el carrito a medias sobra (si muere aquí, al abrir Cobrar se ve la fila y se borra igual).
            carritoEnCurso.borrar(venta.venueId)
        }
    }

    override suspend fun ultimaVentaSinMostrar(venueId: String): VentaGuardadaVista? {
        val fila = dao.ultimaVentaSinMostrar(venueId) ?: return null
        val llave = requireNotNull(fila.idempotencyKey).also { require(it.isNotBlank()) }
        val ticket = requireNotNull(fila.ticketJson)
        val renglones = requireNotNull(Gson().fromJson<List<RenglonDeTicket>>(ticket, object : TypeToken<List<RenglonDeTicket>>() {}.type))
        require(renglones.isNotEmpty() && renglones.all {
            !it.nombre.isNullOrBlank() && !it.externalId.isNullOrBlank() && it.cantidad > 0 && it.totalCents >= 0 &&
                it.brutoCents >= 0 && requireNotNull(it.extras).all { e -> !e.id.isNullOrBlank() && !e.nombre.isNullOrBlank() && !e.tipo.isNullOrBlank() }
        }) { "No se pudo leer el ticket de la venta guardada" }
        val total = BigDecimal(fila.amount).movePointRight(2).intValueExact()
        val propina = BigDecimal(fila.tip).movePointRight(2).intValueExact()
        require(total >= 0 && propina >= 0)
        return VentaGuardadaVista(
            VentaLocal(llave, fila.venueId, fila.staffId, fila.shiftId, fila.createdAt, total, requireNotNull(fila.orderRequestJson), renglones),
            propina,
        )
    }

    override suspend fun marcarVentaVista(llave: String) {
        try {
            check(dao.marcarExitoMostrado(llave) == 1) { "No se pudo confirmar la venta guardada" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "🛒 [VentaSinRed] No se pudo marcar la venta como vista")
            throw e
        }
    }

    /** `Settings.Global.BOOT_COUNT`: si cambia antes de subir, el reloj monótono ya no mide la edad de la venta. */
    private fun arranqueDelAparato(): Int? =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
}
