package com.jaac.avoqado_tpv.features.verification.data

import androidx.room.withTransaction
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto
import com.jaac.avoqado_tpv.features.payment.data.repository.SaleVerificationRepository
import com.jaac.avoqado_tpv.features.verification.data.local.ActionableSaleCacheDao
import com.jaac.avoqado_tpv.features.verification.data.local.ActionableSaleCacheEntity
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueDao
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.verification.data.remote.ProofOfSaleRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

const val ACTIONABLE_OFFLINE_MESSAGE = "Lista guardada en la terminal; se actualiza al volver la señal"

data class ActionableState(val items: List<ActionableItem>, val isOffline: Boolean, val nextCursor: String?)

data class ActionableItem(
    /** null si de esa venta sólo hay capturas en la cola (no está en la lista guardada). */
    val detail: VerificationDetailDto?,
    val paymentId: String,
    /** Sus filas de la cola, la más nueva primero. */
    val queued: List<ProofOfSaleQueueEntity>,
    /** La agregó la terminal al cobrarla; el servidor todavía no la mandó en una página. */
    val localOnly: Boolean,
    /** Ranuras que esta terminal ya entregó: sólo pinta «foto entregada» sin red, nunca quita la tarjeta. */
    val deliveredSlots: Set<ProofSlot> = emptySet(),
) {
    /** null en una venta local o sin detalle: la foto se encola por `paymentId` y el servidor la encuentra. */
    val verificationId: String? get() = detail?.id?.takeIf { !localOnly && it.isNotBlank() }
}

/**
 * «Pendientes» también sin señal (spec §5.5): la lista que mandó el servidor guardada en la terminal, más las ventas que
 * la terminal acaba de cobrar, con las capturas de la cola encima.
 *
 * 🔴 La TPV no adivina si una venta sigue pendiente: le pregunta al servidor. Una página se guarda ENCIMA de lo que había
 * (conserva `delivered_slots`) y nunca se borra una tarjeta porque no vino en una página. Después de la primera página,
 * [refresh] pide `status` de las demás tarjetas del promotor y sólo quita las que el servidor contesta `COMPLETED` o
 * `REJECTED`; `PENDING`, `FAILED` y `NONE` se quedan. «Descartar» es de la cola, nunca de la tarjeta. Nada caduca por tiempo.
 */
@Singleton
class ActionableSalesRepository @Inject constructor(
    private val db: AvoqadoDatabase,
    private val cacheDao: ActionableSaleCacheDao,
    private val queueDao: ProofOfSaleQueueDao,
    private val remote: ProofOfSaleRemote,
    private val verifications: SaleVerificationRepository,
    private val secureStorage: SecureStorage,
) {
    private val gson = Gson()
    private val offline = MutableStateFlow(false)
    private val cursor = MutableStateFlow<String?>(null)

    /** Guarda la primera página y luego revisa el estado de las demás tarjetas guardadas del promotor. */
    suspend fun refresh(): Result<Unit> = load(after = null)

    /** Guarda la siguiente página encima de lo que hay. Sin cursor no hay nada más que pedir. */
    suspend fun loadMore(): Result<Unit> = cursor.value?.let { load(after = it) } ?: Result.success(Unit)

    fun observe(staffId: String): Flow<ActionableState> =
        combine(cacheDao.observeStaff(staffId), queueDao.observeStaff(staffId), offline, cursor) { cached, queued, isOffline, next ->
            val byPayment = queued.groupBy { it.paymentId }
            val saved = cached.map { row ->
                ActionableItem(parse(row.json), row.paymentId, byPayment[row.paymentId].orEmpty(), row.localOnly, slotsOf(row.deliveredSlots))
            }
            val savedIds = cached.mapTo(HashSet()) { it.paymentId }
            val onlyQueued = byPayment.filterKeys { it !in savedIds }.map { (paymentId, rows) -> ActionableItem(null, paymentId, rows, localOnly = true) }
            ActionableState(ordered(saved + onlyQueued), isOffline, next)
        }

    /**
     * La venta recién cobrada con SIM, para que «Pendientes» la tenga aunque la terminal se reinicie sin señal antes de la
     * primera foto. Si ya está guardada (la mandó el servidor), no se toca.
     */
    suspend fun addLocalSale(
        paymentId: String, amount: BigDecimal, orderNumber: String?, isPortabilidad: Boolean, serialNumbers: List<String>,
        venueId: String? = null, staffId: String? = null,
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val staff = staffId ?: secureStorage.getStaffId()
        val venue = venueId ?: secureStorage.getVenueId()
        if (paymentId.isBlank() || staff == null || venue == null) return@withContext
        val detail = VerificationDetailDto(
            id = "", paymentId = paymentId, amount = amount.toDouble(), orderNumber = orderNumber, date = Instant.now().toString(),
            serialNumbers = serialNumbers, isPortabilidad = isPortabilidad, photos = emptyList(),
            requiredPhotos = if (isPortabilidad) 2 else 1, status = "PENDING",
        )
        db.withTransaction {
            if (cacheDao.get(paymentId) == null) {
                cacheDao.upsert(listOf(ActionableSaleCacheEntity(paymentId, venue, staff, null, gson.toJson(detail), "", true, System.currentTimeMillis())))
            }
        }
    }

    /**
     * El detalle para la corrección: del servidor si contesta, si no el de la lista guardada. Si no hay ninguno, el fallo es
     * el del servidor tal cual (una `IOException` = no hubo respuesta), para que la pantalla no diga «error» sin señal.
     */
    suspend fun detail(verificationId: String): Result<VerificationDetailDto> = withContext(Dispatchers.IO) {
        val fromServer = verifications.getVerificationDetail(verificationId)
        fromServer.getOrNull()?.let { fresh ->
            if (fresh.status in ACTIONABLE) secureStorage.getStaffId()?.let { save(listOf(fresh), it) }
            return@withContext Result.success(fresh)
        }
        cacheDao.getByVerification(verificationId)?.let { parse(it.json) }?.let { return@withContext Result.success(it) }
        Result.failure(fromServer.exceptionOrNull() ?: IllegalStateException("Sin detalle de la venta"))
    }

    private suspend fun load(after: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val staffId = secureStorage.getStaffId() ?: return@withContext Result.failure(IllegalStateException("Sin sesión"))
        val page = try {
            remote.actionable(after)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // «Sin señal» lo decide ESTA petición: nadie contestó. Un rechazo del servidor no es falta de señal.
            offline.value = e is IOException
            if ((e as? BackendHttpException)?.errorCode == INVALID_CURSOR) cursor.value = null
            Timber.w(e, "📋 [Pendientes] No se pudo traer la lista | cursor=$after")
            return@withContext Result.failure(e)
        }
        offline.value = false
        val rows = page.data.orEmpty()
        save(rows, staffId)
        cursor.value = page.nextCursor
        if (after == null) removeResolved(staffId, rows.mapTo(HashSet()) { it.paymentId })
        Result.success(Unit)
    }

    /** Encima de lo que había: el mismo pago es una sola tarjeta (llave `payment_id`) y conserva sus ranuras entregadas. */
    private suspend fun save(rows: List<VerificationDetailDto>, staffId: String) {
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        val venue = secureStorage.getVenueId().orEmpty()
        db.withTransaction {
            val merged = rows.associateBy { it.paymentId }.values.map { d ->
                val old = cacheDao.get(d.paymentId)
                ActionableSaleCacheEntity(d.paymentId, old?.venueId ?: venue, staffId, d.id, gson.toJson(d), old?.deliveredSlots.orEmpty(), false, now)
            }
            cacheDao.upsert(merged)
        }
    }

    /** Sólo `COMPLETED` o `REJECTED` del servidor quitan una tarjeta. Sin respuesta, no se quita nada. */
    private suspend fun removeResolved(staffId: String, inFirstPage: Set<String>) {
        val others = cacheDao.observeStaff(staffId).first().map { it.paymentId }.filter { it !in inFirstPage }
        if (others.isEmpty()) return
        val statuses = try {
            remote.statuses(others)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "📋 [Pendientes] Sin estado de ${others.size} tarjetas: se quedan")
            return
        }
        others.filter { statuses[it] in RESOLVED }.forEach { cacheDao.delete(it) }
    }

    /** Gson ignora la nulidad de Kotlin: un JSON incompleto no llega a la pantalla como lista nula. */
    @Suppress("SENSELESS_COMPARISON")
    private fun parse(json: String): VerificationDetailDto? =
        runCatching { gson.fromJson(json, VerificationDetailDto::class.java) }.getOrNull()
            ?.takeIf { it.paymentId != null && it.photos != null && it.serialNumbers != null && it.date != null }

    private fun slotsOf(csv: String): Set<ProofSlot> =
        csv.split(',').mapNotNullTo(LinkedHashSet()) { name -> ProofSlot.entries.firstOrNull { it.name == name } }

    /**
     * Arriba lo que sólo sabe la terminal (ventas recién cobradas y capturas sin tarjeta), la más nueva primero; después las
     * del servidor en SU orden (`terminalPage`): falta foto primero, luego la más vieja, luego el id.
     */
    private fun ordered(items: List<ActionableItem>): List<ActionableItem> {
        val (server, local) = items.partition { it.detail != null && !it.localOnly }
        return local.sortedByDescending { sortTime(it) } + server.sortedWith(
            compareBy<ActionableItem>({ hasAllPhotos(it.detail!!) }, { sortTime(it) }, { it.detail!!.id }),
        )
    }

    private fun hasAllPhotos(d: VerificationDetailDto) = d.photos.count { it.isNotEmpty() } >= d.requiredPhotos

    private fun sortTime(item: ActionableItem): Long =
        item.detail?.date?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: item.queued.maxOfOrNull { it.createdAt } ?: 0L

    private companion object {
        val ACTIONABLE = setOf("PENDING", "FAILED")
        val RESOLVED = setOf("COMPLETED", "REJECTED")
        const val INVALID_CURSOR = "INVALID_CURSOR"
    }
}
