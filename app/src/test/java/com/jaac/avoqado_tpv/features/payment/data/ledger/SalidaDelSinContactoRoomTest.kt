package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cobro simple · paso 2 (R5): lo que la salida del `catch` del sin contacto le pide a la libreta REAL. Antes del banco (`KERNEL_ACTIVO`)
 * suelta el lector y la siguiente cuenta entra en la misma terminal; con la autorización ya pedida (`AUTORIZANDO`) no toca nada.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SalidaDelSinContactoRoomTest {
    private val venue = "venue-1"
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
    }

    @After fun cerrar() = db.close()

    private fun libreta() = PaymentAttemptLedger(dao, mockk(relaxed = true))

    private suspend fun reservar(l: PaymentAttemptLedger, id: String, orden: String): Boolean =
        l.openAttempt(id, venue, "BLUMON", 10_000, 0, "ORDER", """{"venueId":"$venue","orderId":"$orden"}""")

    @Test fun `la excepcion del sin contacto antes del banco deja DESCARTADA, suelta el lector y la siguiente cuenta entra`() = runBlocking<Unit> {
        val l = libreta()
        assertThat(reservar(l, "a", "orden-A")).isTrue()
        assertThat(l.markKernelEntered("a")).isTrue()
        assertWithMessage("con el lector de A dentro, B espera").that(reservar(l, "b0", "orden-B")).isFalse()

        assertWithMessage("la salida del catch suelta").that(l.markKernelRefused("a", "excepcion_antes_del_banco")).isTrue()

        assertThat(dao.getById("a")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(l.hayLectorEnUso()).isFalse()
        assertWithMessage("B entra en la misma terminal").that(reservar(l, "b", "orden-B")).isTrue()
        assertWithMessage("A no queda cercada: nada salió al banco").that(l.retencionDeLaVenta("orden-A")).isNull()
        assertWithMessage("soltar el lector no acredita el regreso del SDK").that(dao.getById("a")!!.sdkReturnedAt).isNull()
    }

    @Test fun `con la autorizacion ya pedida la salida del catch no toca nada y la cuenta sigue cercada`() = runBlocking<Unit> {
        val l = libreta()
        assertThat(reservar(l, "a", "orden-A")).isTrue()
        assertThat(l.markKernelEntered("a")).isTrue()
        assertThat(l.markAuthorizing("a")).isTrue()

        assertWithMessage("desde AUTORIZANDO no suelta").that(l.markKernelRefused("a", "excepcion_antes_del_banco")).isFalse()

        assertThat(dao.getById("a")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
        assertWithMessage("otra tarjeta de A espera").that(reservar(l, "a2", "orden-A")).isFalse()
    }
}
