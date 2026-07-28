package com.jaac.avoqado_tpv.features.payment.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal

/**
 * F-7: `enqueue` no debe reportar éxito cuando la fila no entró.
 *
 * `OnConflictStrategy.IGNORE` sobre el índice único de `reference_number` hace que un
 * insert chocado devuelva rowId 0. Antes del fix, ese 0 se traducía SIEMPRE en
 * `Result.success(Unit)` — pero las filas FAILED nunca se borran
 * (`deleteOldSyncedPayments` solo toca SUCCESS), así que un reference number repetido
 * choca con un cadáver, el pago nuevo jamás entra a la cola, y el cajero lee
 * "EN COLA" sobre un pago que no quedó encolado. Ver spec §4.2 F-7.
 */
class PendingPaymentEnqueueTest {

    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val repo = PaymentQueueRepositoryImpl(dao)

    // ------------------------------------------------------------------
    // Test helpers — minimal valid QueuedPayment / PendingPaymentEntity.
    // No hay apéndice de plan disponible; construidos directamente desde las fuentes
    // (features/payment/domain/model/QueuedPayment.kt, core/data/local/entity/PendingPaymentEntity.kt),
    // rellenando cada parámetro sin default con un valor mínimo válido.
    //
    // venueId/idempotencyKey son parámetros opcionales (default = mismo venue / sin key)
    // agregados en Fix round 1 (finding 1) para los tests de colisión cross-venue y
    // cross-idempotencyKey — no alteran las 4 llamadas originales.
    // ------------------------------------------------------------------

    private fun queuedPayment(
        reference: String,
        venueId: String = "venue-1",
        idempotencyKey: String? = null,
    ): QueuedPayment = QueuedPayment(
        referenceNumber = reference,
        venueId = venueId,
        staffId = "staff-1",
        amount = BigDecimal("100.00"),
        tip = BigDecimal("10.00"),
        rating = null,
        merchantAccountId = "merchant-1",
        blumonSerialNumber = "2841548417",
        maskedPan = "411111******1111",
        cardBrand = "VISA",
        entryMode = "CHIP",
        isInternational = false,
        authorizationNumber = "502511",
        idempotencyKey = idempotencyKey,
        createdAt = System.currentTimeMillis(),
    )

    private fun entity(
        reference: String,
        status: String,
        venueId: String = "venue-1",
        idempotencyKey: String? = null,
    ): PendingPaymentEntity = PendingPaymentEntity(
        referenceNumber = reference,
        venueId = venueId,
        staffId = "staff-1",
        amount = "100.00",
        tip = "10.00",
        rating = null,
        merchantAccountId = "merchant-1",
        blumonSerialNumber = "2841548417",
        maskedPan = "411111******1111",
        cardBrand = "VISA",
        entryMode = "CHIP",
        isInternational = false,
        authorizationNumber = "502511",
        idempotencyKey = idempotencyKey,
        createdAt = System.currentTimeMillis(),
        syncStatus = status,
    )

    @Test
    fun `insert normal devuelve exito`() = runTest {
        coEvery { dao.insert(any()) } returns 42L

        val result = repo.enqueue(queuedPayment(reference = "000000111111"))

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `choque con una fila PENDING del mismo pago devuelve exito`() = runTest {
        // Mismo pago encolado dos veces: ya está a salvo, no es un error.
        coEvery { dao.insert(any()) } returns 0L
        coEvery { dao.findByReference("000000111111", "venue-1") } returns
            entity(reference = "000000111111", status = "PENDING")

        val result = repo.enqueue(queuedPayment(reference = "000000111111"))

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `choque con un cadaver FAILED devuelve FALLO`() = runTest {
        // 🔴 El bug: la fila vieja bloquea el índice único para siempre y el pago
        // nuevo nunca entra a la cola, pero al cajero se le decía "EN COLA".
        coEvery { dao.insert(any()) } returns 0L
        coEvery { dao.findByReference("000000111111", "venue-1") } returns
            entity(reference = "000000111111", status = "FAILED")

        val result = repo.enqueue(queuedPayment(reference = "000000111111"))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `choque con una fila SUCCESS vieja devuelve FALLO`() = runTest {
        coEvery { dao.insert(any()) } returns 0L
        coEvery { dao.findByReference("000000111111", "venue-1") } returns
            entity(reference = "000000111111", status = "SUCCESS")

        val result = repo.enqueue(queuedPayment(reference = "000000111111"))

        assertThat(result.isFailure).isTrue()
    }

    // ------------------------------------------------------------------
    // Fix round 1, finding 1 — findByReference sin scope de venue leía la fila de
    // OTRO venue (o de OTRO pago con distinta idempotencyKey) como "el mismo pago,
    // ya a salvo". pending_payments NO se vacía al cambiar de venue/logout (guarda
    // datos de dinero), y un terminal reasignado de venue puede reusar un
    // reference_number corto/secuencial que el venue anterior aún tiene vivo.
    // ------------------------------------------------------------------

    @Test
    fun `choque con una fila PENDING de OTRO venue devuelve FALLO`() = runTest {
        // El SQL de findByReference ya filtra por venue_id: una fila PENDING que
        // pertenece a otro venue es invisible para este lookup (Room la excluye),
        // así que el mock representa eso devolviendo null para ESTE venue.
        coEvery { dao.insert(any()) } returns 0L
        coEvery { dao.findByReference("000000111111", "venue-B") } returns null

        val result = repo.enqueue(queuedPayment(reference = "000000111111", venueId = "venue-B"))

        assertThat(result.isFailure).isTrue()
        // Prueba que el venue del pago (no un venue fijo/incorrecto) es el que se
        // manda al DAO — el corazón del fix de finding 1.
        coVerify(exactly = 1) { dao.findByReference("000000111111", "venue-B") }
    }

    @Test
    fun `choque con una fila PENDING del mismo venue pero OTRA idempotencyKey devuelve FALLO`() = runTest {
        // Mismo venue + mismo reference, pero idempotencyKey distinta: es un pago
        // DISTINTO, no un doble-submit seguro del mismo intento.
        coEvery { dao.insert(any()) } returns 0L
        coEvery { dao.findByReference("000000111111", "venue-1") } returns
            entity(reference = "000000111111", status = "PENDING", idempotencyKey = "idem-OLD")

        val result = repo.enqueue(
            queuedPayment(reference = "000000111111", idempotencyKey = "idem-NEW"),
        )

        assertThat(result.isFailure).isTrue()
    }

    // ------------------------------------------------------------------
    // Fix round 3 (coordinator review) — enqueue() persiste una tarjeta YA cobrada
    // (solo se llama cuando el registro al backend falló). Cancelar el scope que
    // llama (viewModelScope.launch{} en AngelPayPaymentViewModel.handleRecordFailure,
    // p.ej. al hacer clear() del ViewModel si la pantalla se cierra) NO debe abortar
    // el insert — es la red de seguridad completa de ese cobro, no trabajo opcional.
    // ------------------------------------------------------------------

    @Test
    fun `el insert sobrevive aunque el job llamador se cancele mientras sigue en vuelo`() = runTest {
        // dao.insert() simula estar "en vuelo" (delay real, no virtual — enqueue() corre
        // sobre Dispatchers.IO real) el tiempo suficiente para que el test pueda cancelar
        // el job que lo llama MIENTRAS sigue corriendo bajo el escudo NonCancellable.
        var insertCompleted = false
        val insertStarted = CompletableDeferred<Unit>()
        coEvery { dao.insert(any()) } coAnswers {
            insertStarted.complete(Unit)
            delay(50)
            insertCompleted = true
            42L
        }

        val job = launch {
            repo.enqueue(queuedPayment(reference = "000000999999"))
        }
        insertStarted.await() // espera a que el insert() haya arrancado de verdad
        job.cancel()          // cancela el job MIENTRAS el insert sigue "en vuelo"
        job.join()

        // El insert se completó DE TODOS MODOS — NonCancellable protegió la escritura
        // del cancel() que ya había llegado. Sin el escudo (F-9, Fix round 3), cancelar
        // aquí habría lanzado CancellationException dentro de dao.insert() (en el delay
        // de arriba) e insertCompleted se habría quedado en false — la tarjeta ya cobrada
        // se habría perdido sin dejar fila en la cola.
        assertThat(insertCompleted).isTrue()
        coVerify(exactly = 1) { dao.insert(any()) }
    }
}
