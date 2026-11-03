package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.remotepayment.AvisoDeCobrosPendientes
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestDao
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Botón «No se cobró» del banner verde (founder, 3-nov: «A»): QUÉ cobros ofrece. La lista sale de Room de verdad: lo que decide es
 * SQL, y un mock no lo evalúa. Sólo un cobro en duda, sin dinero conocido y que ya no corre puede recibir la afirmación «no se cobró».
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CobrosDeclarablesRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var bandeja: RemotePaymentRequestDao
    private lateinit var ledger: PaymentAttemptLedger
    private val venue = "venue-1"
    private val ahora = 1_700_000_000_000L

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        bandeja = db.remotePaymentRequestDao()
        ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
    }

    @After fun cerrar() = db.close()

    /** Una duda limpia: un Pago rápido en duda, sin dinero conocido. Cada prueba le cambia sólo lo que quiere probar. */
    private fun duda(id: String, requestId: String? = null, haceMs: Long = 60_000) = PaymentAttemptEntity(
        attemptId = id, venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 10_000, tipCents = 2_000,
        recordingRoute = "FAST",
        paymentContextJson = if (requestId != null) """{"terminalPaymentRequestId":"$requestId"}""" else """{"amount":100.00}""",
        terminalPaymentRequestId = requestId, createdAt = ahora - haceMs, updatedAt = ahora - haceMs,
    )

    private fun enBandeja(requestId: String, haceMs: Long = 60_000, venueId: String = venue, efectivo: Boolean = false) = RemotePaymentRequestEntity(
        requestId = requestId, venueId = venueId, amountCents = 5_000, tipCents = 500, rating = null, skipReview = true, orderId = null,
        processedByStaffId = null, senderDeviceName = "POS", sourceTimestamp = "t", status = RemotePaymentRequestEntity.STATUS_PROCESSING,
        createdAt = ahora - haceMs, updatedAt = ahora - haceMs, executionStartedAt = if (efectivo) ahora else null,
    )

    private suspend fun declarables() = ledger.observarCobrosDeclarables(venue).first()

    // ── Lo que entra ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `entra un INDETERMINADO local con su importe, su antiguedad y si Avoqado ya contesto`() = runTest {
        dao.insert(duda("a-local").copy(serverAnsweredAt = 77L))

        assertThat(declarables()).containsExactly(
            CobroDeclarable(attemptId = "a-local", requestId = null, totalCentavos = 12_000, desdeMillis = ahora - 60_000, serverAnsweredAt = 77L),
        )
    }

    @Test fun `entra un INDETERMINADO ligado a su solicitud, tambien de la PAX`() = runTest {
        dao.insert(duda("a-nexgo", "r-nexgo"))
        dao.insert(duda("a-pax", "r-pax", haceMs = 30_000).copy(processor = "BLUMON"))

        assertThat(declarables().map { it.attemptId to it.requestId }).containsExactly("a-pax" to "r-pax", "a-nexgo" to "r-nexgo").inOrder()
    }

    @Test fun `entra una solicitud de bandeja PROCESSING sin intento, sin identidad de intento ni respuesta`() = runTest {
        bandeja.insert(enBandeja("r-sola"))

        assertThat(declarables()).containsExactly(
            CobroDeclarable(attemptId = null, requestId = "r-sola", totalCentavos = 5_500, desdeMillis = ahora - 60_000, serverAnsweredAt = null),
        )
    }

    @Test fun `un intento ligado sale una sola vez aunque su bandeja siga PROCESSING`() = runTest {
        bandeja.insert(enBandeja("r-1"))
        dao.insert(duda("a-1", "r-1"))

        assertThat(declarables().map { it.attemptId to it.requestId }).containsExactly("a-1" to "r-1")
    }

    @Test fun `la mas reciente primero, y las dos familias van revueltas por antiguedad`() = runTest {
        dao.insert(duda("vieja", haceMs = 300_000))
        bandeja.insert(enBandeja("en-medio", haceMs = 200_000))
        dao.insert(duda("nueva", haceMs = 100_000))

        assertThat(declarables().map { it.identidad }).containsExactly("nueva", "en-medio", "vieja").inOrder()
    }

    // ── Lo que no entra: cada una, por separado ───────────────────────────────────────────────────────────────────────

    @Test fun `no entra lo que puede estar corriendo o ya tiene respuesta del banco`() = runTest {
        val estados = listOf("PREPARANDO", "KERNEL_ACTIVO", "AUTORIZANDO", "HOST_RESPONDIO", "AUTORIZADO", "REGISTRO_FALLIDO", "ENTREGADA_A_COLA", "REGISTRADO", "CERRADA", "DESCARTADA")
        estados.forEach { dao.insert(duda("a-$it").copy(state = it)) }

        assertThat(declarables()).isEmpty()
    }

    @Test fun `no entra lo que trae evidencia de dinero o una contradiccion`() = runTest {
        val conDinero = mapOf<String, (PaymentAttemptEntity) -> PaymentAttemptEntity>(
            "host aprobado" to { it.copy(hostApproved = true) },
            "banco aprobado" to { it.copy(serverProcessorEvidence = "APPROVED") },
            "veto" to { it.copy(serverVeto = "PAYMENT_CONTRADICTION") },
            "registrado" to { it.copy(serverOutcome = "RECORDED", serverPaymentId = "p", serverAmountCents = 10_000, serverTipCents = 2_000) },
            "segunda captura" to { it.copy(serverOutcome = "SECOND_CAPTURE_EVIDENCE", serverPaymentId = "p") },
            "colision" to { it.copy(serverOutcome = "REFERENCE_COLLISION_EVIDENCE", serverPaymentId = "p") },
            "pendiente" to { it.copy(serverOutcome = "PENDING_EVIDENCE", serverPaymentId = "p") },
        )
        for ((nombre, marca) in conDinero) {
            db.clearAllTables()
            dao.insert(marca(duda("a")))
            assertWithMessage(nombre).that(declarables()).isEmpty()
        }
    }

    @Test fun `no entra una devolucion, una fila heredada ni otro negocio`() = runTest {
        dao.insert(duda("devolucion").copy(kind = PaymentAttemptEntity.KIND_REFUND))
        dao.insert(duda("heredada").copy(legacyShadow = true))
        dao.insert(duda("ajena").copy(venueId = "otro"))
        bandeja.insert(enBandeja("r-ajena", venueId = "otro"))

        assertThat(declarables()).isEmpty()
    }

    @Test fun `una bandeja con CUALQUIER intento no entra por su rama - eso lo resuelve la libreta`() = runTest {
        // PREPARANDO y KERNEL_ACTIVO cuentan en el aviso como «sin intento», pero el lector puede estar corriendo.
        for (estado in listOf("PREPARANDO", "KERNEL_ACTIVO", "AUTORIZANDO", "DESCARTADA", "REGISTRADO")) {
            db.clearAllTables()
            bandeja.insert(enBandeja("r-1"))
            dao.insert(duda("a-1", "r-1").copy(state = estado))
            assertWithMessage(estado).that(declarables()).isEmpty()
        }
    }

    @Test fun `una bandeja con efectivo arrancado no entra - ese cobro es de la cola`() = runTest {
        bandeja.insert(enBandeja("r-efectivo", efectivo = true))

        assertThat(declarables()).isEmpty()
    }

    @Test fun `una bandeja ya resuelta tampoco`() = runTest {
        bandeja.insert(enBandeja("r-1").copy(status = RemotePaymentRequestEntity.STATUS_RESOLVED))
        bandeja.insert(enBandeja("r-2").copy(status = RemotePaymentRequestEntity.STATUS_RECEIVED))

        assertThat(declarables()).isEmpty()
    }

    @Test fun `una solicitud con otro intento con dinero o corriendo no entra - la declaracion es de toda la solicitud`() = runTest {
        val hermanos = mapOf<String, (PaymentAttemptEntity) -> PaymentAttemptEntity>(
            "host respondio" to { it.copy(state = "HOST_RESPONDIO", hostApproved = true) },
            "lector corriendo" to { it.copy(state = "KERNEL_ACTIVO") },
            "registrado" to { it.copy(state = "REGISTRADO") },
            "en duda con aprobado" to { it.copy(hostApproved = true) },
            "en duda con evidencia" to { it.copy(serverProcessorEvidence = "APPROVED") },
            "descartado con veto" to { it.copy(state = "DESCARTADA", serverVeto = "PAYMENT_CONTRADICTION") },
        )
        for ((nombre, marca) in hermanos) {
            db.clearAllTables()
            dao.insert(duda("limpio", "r-1"))
            dao.insert(marca(duda("hermano", "r-1")))
            assertWithMessage(nombre).that(declarables()).isEmpty()
        }
    }

    @Test fun `un hermano descartado sin dinero no le quita la salida`() = runTest {
        dao.insert(duda("limpio", "r-1"))
        dao.insert(duda("rechazado", "r-1").copy(state = "DESCARTADA", hostApproved = false))

        assertThat(declarables().map { it.attemptId }).containsExactly("limpio")
    }

    @Test fun `la cuarentena por reloj de ESTE proceso no entra - nadie comprobo que el lector terminara`() = runTest {
        assertThat(ledger.openAttempt("viva", venue, "ANGELPAY", 100, 0, "FAST", "{}")).isTrue()
        assertThat(ledger.markAuthorizing("viva")).isTrue()
        assertThat(dao.quarantineStaleAuthorizing(venue, Long.MAX_VALUE, ahora)).isEqualTo(1)
        assertThat(dao.getById("viva")!!.lastError).isEqualTo(PaymentAttemptEntity.CUARENTENA_POR_ANTIGUEDAD)

        assertThat(declarables()).isEmpty()

        // El proceso murió: su llamada nativa murió con él y ya se puede declarar.
        val procesoNuevo = PaymentAttemptLedger(dao, mockk(relaxed = true))
        assertThat(procesoNuevo.observarCobrosDeclarables(venue).first().map { it.attemptId }).containsExactly("viva")
    }

    // ── La ventana y «una por solicitud» ──────────────────────────────────────────────────────────────────────────────

    @Test fun `la ventana es la del aviso - a los 10 min ya no se ofrece`() {
        val cobro = CobroDeclarable("a", null, 100, desdeMillis = ahora, serverAnsweredAt = null)
        val ventana = AvisoDeCobrosPendientes.VENTANA_AVISO_MS

        assertThat(DeclaracionDesdeElAviso.vigentes(listOf(cobro), ahora + ventana - 1)).containsExactly(cobro)
        assertThat(DeclaracionDesdeElAviso.vigentes(listOf(cobro), ahora + ventana)).isEmpty()
    }

    @Test fun `una por solicitud - dos intentos limpios de la misma salen como uno, el mas reciente`() = runTest {
        dao.insert(duda("primero", "r-1", haceMs = 200_000))
        dao.insert(duda("segundo", "r-1", haceMs = 100_000))
        dao.insert(duda("otro", "r-2", haceMs = 300_000))
        dao.insert(duda("local-1", haceMs = 400_000))
        dao.insert(duda("local-2", haceMs = 500_000))

        val vigentes = DeclaracionDesdeElAviso.vigentes(declarables(), ahora)

        assertThat(vigentes.map { it.attemptId }).containsExactly("segundo", "otro", "local-1", "local-2").inOrder()
    }

    @Test fun `todo lo declarable sale en el aviso - nunca se ofrece declarar algo que el aviso no nombra`() = runTest {
        dao.insert(duda("a-local"))
        dao.insert(duda("a-ligado", "r-ligado"))
        bandeja.insert(enBandeja("r-sola"))

        assertThat(declarables().map { it.totalCentavos to it.desdeMillis })
            .containsExactlyElementsIn(bandeja.observePendingObligations(venue).first().map { it.totalCentavos to it.desdeMillis })
    }

    @Test fun `el renglon de la lista dice el importe y la antiguedad`() {
        assertThat(AvisoDeCobrosPendientes.renglonDeCobro(12_000, ahora - 3 * 60_000, ahora)).isEqualTo("$120.00 · hace 3 min")
        assertThat(AvisoDeCobrosPendientes.renglonDeCobro(120_000, ahora - 3 * 60_000, ahora)).isEqualTo("$1,200.00 · hace 3 min")
        assertThat(AvisoDeCobrosPendientes.renglonDeCobro(12_000, ahora + 5_000, ahora)).isEqualTo("$120.00 · hace unos segundos")
    }
}
