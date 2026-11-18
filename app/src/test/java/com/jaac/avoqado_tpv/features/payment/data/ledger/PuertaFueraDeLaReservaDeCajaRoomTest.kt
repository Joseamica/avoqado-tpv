package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * hz:nube0000:naa0a0de (Codex r4 H3 y r19 del PR #75): LA PUERTA escribe la evidencia positiva del servidor que quedó sin escribir y
 * la borra de la memoria en el acto. Pero en una reserva de cobro corre DENTRO de la transacción de Caja
 * (`ShiftRepository.guardarNuevaObligacionDeCaja`, que vuelve a comprobar la sesión DESPUÉS de guardar). Si esa transacción se
 * revierte, Room deshace la marca de dinero y la memoria ya no la tiene: el aparato sigue sin esa protección.
 *
 * Room real; la base que falla es un trigger (como un disco lleno) y la reversión la provoca un cambio de sesión a media reserva.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PuertaFueraDeLaReservaDeCajaRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var shifts: ShiftRepository
    private lateinit var ledger: PaymentAttemptLedger
    private val storage = mockk<SecureStorage>(relaxed = true)
    private var token = "token-a"
    private var duringQuery: (String) -> Unit = {}

    @Before fun setup() {
        every { storage.getVenueId() } returns "v1"
        every { storage.getStaffId() } returns "staff-a"
        every { storage.getToken() } answers { token }
        every { storage.getKioskVenueId() } returns null
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } returns false // sin sistema de turnos: no hace falta abrir caja
        every { storage.getString("permissions_cache", null) } returns "v1|staff-a\npayments:create,payments:refund"
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().setQueryCallback({ sql, _ -> duringQuery(sql) }, java.util.concurrent.Executor { it.run() }).build()
        shifts = ShiftRepository(mockk<ApiService>(), db, storage, db.cachedShiftDao(), mockk<RefundQueueRepository>(relaxed = true),
            appContext = ApplicationProvider.getApplicationContext())
        ledger = PaymentAttemptLedger(db.paymentAttemptDao(), mockk<TpvSettingsRepository>(relaxed = true))
    }

    @After fun cleanup() = db.close()

    private fun sql(s: String) = db.openHelper.writableDatabase.execSQL(s)

    @Test fun `P1 la reserva de Caja se revierte y la evidencia del servidor que la puerta escribio no se pierde`() = runTest {
        // Otro cobro, ya cerrado, del que Avoqado dice que SÍ hubo dinero; la base no pudo escribirlo (queda en memoria).
        assertThat(ledger.openAttempt("otro", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 1000, 0, "FAST", "{}")).isTrue()
        assertThat(ledger.markDiscardedBeforeCharge("otro", "prueba")).isTrue()
        sql("CREATE TRIGGER falla_evidencia BEFORE UPDATE ON payment_attempts WHEN NEW.server_processor_evidence IS 'APPROVED' " +
            "BEGIN SELECT RAISE(ABORT, 'disco lleno'); END")
        assertThat(ledger.marcarEvidenciaPositivaDelServidor("v1", "otro").isFailure).isTrue()
        assertThat(ledger.tieneEvidenciaSinGuardar("otro")).isTrue()
        sql("DROP TRIGGER falla_evidencia") // la base vuelve

        // El siguiente cobro: la reserva escribe y, antes de confirmar, la sesión cambia → la transacción de Caja se revierte.
        duringQuery = { s -> if (s.startsWith("INSERT") && s.contains("payment_attempts")) token = "token-b" }
        assertThat(ledger.openAttemptForShift(shifts, "nuevo", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 1000, 0, "FAST", "{}")).isFalse()
        duringQuery = {}

        assertThat(db.paymentAttemptDao().getById("nuevo")).isNull() // la reserva sí se revirtió
        val escrita = db.paymentAttemptDao().getById("otro")?.serverProcessorEvidence == PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED
        assertWithMessage("el dinero que Avoqado vio de «otro» tiene que seguir escrito o recordado")
            .that(escrita || ledger.tieneEvidenciaSinGuardar("otro")).isTrue()
    }

    @Test fun `con la base sana la puerta escribe la evidencia pendiente y el cobro siguiente se reserva`() = runTest {
        assertThat(ledger.openAttempt("otro", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 1000, 0, "FAST", "{}")).isTrue()
        assertThat(ledger.markDiscardedBeforeCharge("otro", "prueba")).isTrue()
        sql("CREATE TRIGGER falla_evidencia BEFORE UPDATE ON payment_attempts WHEN NEW.server_processor_evidence IS 'APPROVED' " +
            "BEGIN SELECT RAISE(ABORT, 'disco lleno'); END")
        assertThat(ledger.marcarEvidenciaPositivaDelServidor("v1", "otro").isFailure).isTrue()
        sql("DROP TRIGGER falla_evidencia")

        assertThat(ledger.openAttemptForShift(shifts, "nuevo", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 1000, 0, "FAST", "{}")).isTrue()

        assertThat(db.paymentAttemptDao().getById("otro")?.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        assertThat(ledger.tieneEvidenciaSinGuardar("otro")).isFalse()
        assertThat(db.paymentAttemptDao().getById("nuevo")?.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
    }

    @Test fun `con la base todavia rota la reserva se niega y la evidencia sigue en memoria`() = runTest {
        assertThat(ledger.openAttempt("otro", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 1000, 0, "FAST", "{}")).isTrue()
        assertThat(ledger.markDiscardedBeforeCharge("otro", "prueba")).isTrue()
        sql("CREATE TRIGGER falla_evidencia BEFORE UPDATE ON payment_attempts WHEN NEW.server_processor_evidence IS 'APPROVED' " +
            "BEGIN SELECT RAISE(ABORT, 'disco lleno'); END")
        assertThat(ledger.marcarEvidenciaPositivaDelServidor("v1", "otro").isFailure).isTrue()

        assertThat(ledger.openAttemptForShift(shifts, "nuevo", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 1000, 0, "FAST", "{}")).isFalse()

        assertThat(db.paymentAttemptDao().getById("nuevo")).isNull()
        assertThat(ledger.tieneEvidenciaSinGuardar("otro")).isTrue()
    }
}
