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
            CobroDeclarable(attemptId = "a-local", requestId = null, totalCentavos = 12_000, desdeMillis = ahora - 60_000, serverAnsweredAt = 77L,
                estado = "INDETERMINADO"),
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

        assertThat(declarables().map { it.clave }).containsExactly("nueva", "en-medio", "vieja").inOrder()
    }

    // ── Lo que no entra: cada una, por separado ───────────────────────────────────────────────────────────────────────

    // F1 bloque 5 (I5): lo que cuenta Caja se VE en «Por revisar» (sólo para consultar o para su dueño); lo cerrado no.
    @Test fun `lo que puede estar corriendo o ya tiene respuesta del banco se ve, pero no se declara`() = runTest {
        val estados = listOf("PREPARANDO", "KERNEL_ACTIVO", "AUTORIZANDO", "HOST_RESPONDIO", "AUTORIZADO", "REGISTRO_FALLIDO", "ENTREGADA_A_COLA", "REGISTRADO", "CERRADA", "DESCARTADA")
        estados.forEach { dao.insert(duda("a-$it").copy(state = it)) }

        val renglones = declarables()
        assertThat(renglones.map { it.attemptId }).containsExactly("a-PREPARANDO", "a-KERNEL_ACTIVO", "a-AUTORIZANDO", "a-HOST_RESPONDIO",
            "a-AUTORIZADO", "a-REGISTRO_FALLIDO", "a-ENTREGADA_A_COLA")
        assertThat(renglones.none { it.declarable }).isTrue()
    }

    // F1 (tarea 4): reemplaza «no entra»: una fila con dinero o contradicción queda en la lista para CONSULTARLA, sin poder declararse.
    @Test fun `F1 lo que trae evidencia de dinero o una contradiccion entra sólo para consulta`() = runTest {
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
            assertWithMessage(nombre).that(declarables().map { it.attemptId to it.declarable }).containsExactly("a" to false)
        }
        db.clearAllTables()
        dao.insert(duda("limpia"))
        assertWithMessage("una duda limpia sí se declara").that(declarables().single().declarable).isTrue()
    }

    // F1 bloque 5: la devolución y la heredada las cuenta Caja: se ven (su dueño las resuelve), sin declaración. Otro negocio, no.
    @Test fun `una devolucion y una heredada se ven sin declaracion, otro negocio no entra`() = runTest {
        dao.insert(duda("devolucion").copy(kind = PaymentAttemptEntity.KIND_REFUND))
        dao.insert(duda("heredada").copy(legacyShadow = true))
        dao.insert(duda("ajena").copy(venueId = "otro"))
        bandeja.insert(enBandeja("r-ajena", venueId = "otro"))

        val renglones = declarables()
        assertThat(renglones.map { it.attemptId }).containsExactly("devolucion", "heredada")
        assertThat(renglones.none { it.declarable }).isTrue()
        assertThat(renglones.single { it.attemptId == "devolucion" }.tipo).isEqualTo(PaymentAttemptEntity.KIND_REFUND)
    }

    @Test fun `una bandeja con un intento que responde por ella no entra por su rama - con uno descartado sin dinero sale sólo para consultar`() = runTest {
        // PREPARANDO y KERNEL_ACTIVO cuentan en el aviso como «sin intento», pero el lector puede estar corriendo.
        for (estado in listOf("PREPARANDO", "KERNEL_ACTIVO", "AUTORIZANDO", "REGISTRADO")) {
            db.clearAllTables()
            bandeja.insert(enBandeja("r-1"))
            dao.insert(duda("a-1", "r-1").copy(state = estado))
            assertWithMessage(estado).that(declarables().filter { it.clase == CobroDeclarable.CLASE_SOLICITUD }).isEmpty()
        }
        // Codex final r1 #8: el intento DESCARTADO sin dinero ya no responde por ella y Caja la cuenta: se ve, pero no se firma (D8).
        db.clearAllTables()
        bandeja.insert(enBandeja("r-1"))
        dao.insert(duda("a-1", "r-1").copy(state = "DESCARTADA"))
        val solicitudes = declarables().filter { it.clase == CobroDeclarable.CLASE_SOLICITUD }
        assertThat(solicitudes.map { it.requestId }).containsExactly("r-1")
        assertThat(solicitudes.single().declarable).isFalse()
    }

    @Test fun `una bandeja con efectivo arrancado se ve sin declaracion - la cuenta Caja hasta que su pago llega a la cola`() = runTest {
        bandeja.insert(enBandeja("r-efectivo", efectivo = true))

        // Codex final r1 #8: la misma regla de Caja (antes no entraba): se ve y se consulta, nunca se firma «no se cobró».
        assertThat(declarables().map { it.requestId to it.declarable }).containsExactly("r-efectivo" to false)
    }

    @Test fun `una bandeja ya resuelta tampoco`() = runTest {
        bandeja.insert(enBandeja("r-1").copy(status = RemotePaymentRequestEntity.STATUS_RESOLVED))
        bandeja.insert(enBandeja("r-2").copy(status = RemotePaymentRequestEntity.STATUS_RECEIVED))

        assertThat(declarables()).isEmpty()
    }

    // F1 (Codex código r2 #4): «no entra» = no se puede DECLARAR; la fila se ve igual, sólo para consultar (el cierre la cuenta).
    @Test fun `una solicitud con otro intento con dinero o corriendo no se declara - la declaracion es de toda la solicitud`() = runTest {
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
            // F1 (tarea 4): la solicitud no se ofrece para declarar; un hermano EN DUDA con dinero sale sólo para consulta.
            assertWithMessage(nombre).that(declarables().filter { it.declarable }).isEmpty()
            assertWithMessage(nombre).that(declarables().single { it.attemptId == "limpio" }.declarable).isFalse()
        }
    }

    @Test fun `un hermano descartado sin dinero no le quita la salida`() = runTest {
        dao.insert(duda("limpio", "r-1"))
        dao.insert(duda("rechazado", "r-1").copy(state = "DESCARTADA", hostApproved = false))

        assertThat(declarables().map { it.attemptId }).containsExactly("limpio")
    }

    @Test fun `la cuarentena por reloj de ESTE proceso no se declara - nadie comprobo que el lector terminara`() = runTest {
        assertThat(ledger.openAttempt("viva", venue, "ANGELPAY", 100, 0, "FAST", "{}")).isTrue()
        assertThat(ledger.markAuthorizing("viva")).isTrue()
        assertThat(dao.quarantineStaleAuthorizing(venue, Long.MAX_VALUE, ahora)).isEqualTo(1)
        assertThat(dao.getById("viva")!!.lastError).isEqualTo(PaymentAttemptEntity.CUARENTENA_POR_ANTIGUEDAD)

        // Codex código r2 #4: se ve (el cierre de caja la cuenta), pero sólo para consultar.
        assertThat(declarables().single().declarable).isFalse()

        // El proceso murió: su llamada nativa murió con él y ya se puede declarar.
        val procesoNuevo = PaymentAttemptLedger(dao, mockk(relaxed = true))
        assertThat(procesoNuevo.observarCobrosDeclarables(venue).first().single { it.attemptId == "viva" }.declarable).isTrue()
    }

    // ── La ventana y «una por solicitud» ──────────────────────────────────────────────────────────────────────────────

    @Test fun `la ventana es la del aviso - a los 10 min ya no se ofrece`() {
        val cobro = CobroDeclarable("a", null, 100, desdeMillis = ahora, serverAnsweredAt = null)
        val ventana = AvisoDeCobrosPendientes.VENTANA_AVISO_MS

        assertThat(DeclaracionDesdeElAviso.vigentes(listOf(cobro), ahora + ventana - 1)).containsExactly(cobro)
        assertThat(DeclaracionDesdeElAviso.vigentes(listOf(cobro), ahora + ventana)).isEmpty()
    }

    /** D20: pasados los 10 min el aviso se va, pero la duda sigue: «Por revisar» (Pagos) la conserva hasta que se declare. */
    @Test fun `D20 por revisar no tiene ventana - lo de hace horas sigue a la vista, uno por intento`() = runTest {
        dao.insert(duda("viejo", haceMs = 3 * 60 * 60_000L))
        dao.insert(duda("primero", "r-1", haceMs = 2 * 60 * 60_000L))
        dao.insert(duda("segundo", "r-1", haceMs = 60 * 60_000L))

        val todos = declarables()

        assertThat(DeclaracionDesdeElAviso.vigentes(todos, ahora)).isEmpty()
        // F1 bloque 5: un renglón por INTENTO, aunque dos sean de la misma solicitud.
        assertThat(DeclaracionDesdeElAviso.porRevisar(todos).map { it.attemptId }).containsExactly("segundo", "primero", "viejo").inOrder()
    }

    /** Codex r1 #1: la consulta trae los 50 más recientes; con 51, el más viejo no sale y el recuadro tiene que decirlo. */
    @Test fun `D20 con la consulta llena se sabe que hay mas, y con 49 no`() = runTest {
        repeat(51) { i -> dao.insert(duda("local-$i", haceMs = (i + 1) * 60 * 60_000L)) }

        val todos = declarables()

        assertThat(todos).hasSize(DeclaracionDesdeElAviso.TOPE_DE_LA_CONSULTA)
        assertThat(todos.map { it.attemptId }).doesNotContain("local-50")
        assertThat(DeclaracionDesdeElAviso.recortada(todos)).isTrue()
        assertThat(DeclaracionDesdeElAviso.recortada(todos.drop(1))).isFalse()

        // Codex r2: «Ver anteriores» (otros 50) sí lo alcanza, y la lista ya no está llena.
        val ampliada = ledger.observarCobrosDeclarables(venue, 2 * DeclaracionDesdeElAviso.TOPE_DE_LA_CONSULTA).first()
        assertThat(ampliada.map { it.attemptId }).contains("local-50")
        assertThat(DeclaracionDesdeElAviso.recortada(ampliada, 2 * DeclaracionDesdeElAviso.TOPE_DE_LA_CONSULTA)).isFalse()
    }

    @Test fun `F1 bloque 5 - dos intentos de la misma solicitud son dos renglones`() = runTest {
        dao.insert(duda("primero", "r-1", haceMs = 200_000))
        dao.insert(duda("segundo", "r-1", haceMs = 100_000))
        dao.insert(duda("otro", "r-2", haceMs = 300_000))
        dao.insert(duda("local-1", haceMs = 400_000))
        dao.insert(duda("local-2", haceMs = 500_000))

        val vigentes = DeclaracionDesdeElAviso.vigentes(declarables(), ahora)

        assertThat(vigentes.map { it.attemptId }).containsExactly("segundo", "primero", "otro", "local-1", "local-2").inOrder()
    }

    @Test fun `todo lo declarable sale en el aviso - nunca se ofrece declarar algo que el aviso no nombra`() = runTest {
        dao.insert(duda("a-local"))
        dao.insert(duda("a-ligado", "r-ligado"))
        bandeja.insert(enBandeja("r-sola"))

        assertThat(declarables().map { it.totalCentavos to it.desdeMillis })
            .containsExactlyElementsIn(bandeja.observePendingObligations(venue).first().map { it.totalCentavos to it.desdeMillis })
    }

    @Test fun `F1 el renglon del gerente dice importe, hora del negocio y referencia corta`() {
        val zona = java.time.ZoneOffset.ofHours(-6)
        val alas1432 = java.time.ZonedDateTime.of(2026, 11, 17, 14, 32, 0, 0, zona).toInstant().toEpochMilli()
        val cobro = CobroDeclarable("6f1c2a9e-77b3-4d1e-9a2f-03c4d5e6a1b2", null, 12_000, alas1432, null)
        assertThat(cobro.referenciaCorta).isEqualTo("E6A1B2")
        assertThat(AvisoDeCobrosPendientes.renglonParaElGerente(cobro.totalCentavos, cobro.desdeMillis, cobro.referenciaCorta, zona))
            .isEqualTo("$120.00 · 14:32 · ref E6A1B2")
    }

    @Test fun `el renglon de la lista dice el importe y la antiguedad`() {
        assertThat(AvisoDeCobrosPendientes.renglonDeCobro(12_000, ahora - 3 * 60_000, ahora)).isEqualTo("$120.00 · hace 3 min")
        assertThat(AvisoDeCobrosPendientes.renglonDeCobro(120_000, ahora - 3 * 60_000, ahora)).isEqualTo("$1,200.00 · hace 3 min")
        assertThat(AvisoDeCobrosPendientes.renglonDeCobro(12_000, ahora + 5_000, ahora)).isEqualTo("$120.00 · hace unos segundos")
    }
}
