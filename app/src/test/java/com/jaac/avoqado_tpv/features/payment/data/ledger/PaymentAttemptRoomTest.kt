package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PaymentAttemptRoomTest {
    @Test fun `two ledger instances cannot reserve preparing kernel work concurrently across venues`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val first = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            val second = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            val start = kotlinx.coroutines.CompletableDeferred<Unit>()
            val one = async(kotlinx.coroutines.Dispatchers.IO) {
                start.await()
                first.openAttempt("one", "venue-a", "BLUMON", 100, 0, "FAST", "{}")
            }
            val two = async(kotlinx.coroutines.Dispatchers.IO) {
                start.await()
                second.openAttempt("two", "venue-b", "ANGELPAY", 100, 0, "FAST", "{}")
            }
            start.complete(Unit)
            val admitted = listOf(one.await(), two.await())
            assertEquals("A terminal has one durable pre-kernel reservation, independent of order/venue", 1, admitted.count { it })
            val winner = if (admitted[0]) "one" else "two"
            assertTrue(first.markDiscardedBeforeCharge(winner, "confirmed_pre_sdk_cancel"))
            assertTrue(second.openAttempt("after-safe-discard", "venue-b", "BLUMON", 100, 0, "FAST", "{}"))
        } finally { db.close() }
    }

    @Test fun `P1 una negativa explicita del kernel libera la terminal, una incierta la retiene`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)

            // La tarjeta se acerca: el kernel ya corre y NADIE puede descartar esa fila.
            assertTrue(ledger.openAttempt("rechazado", "venue", "BLUMON", 100, 0, "FAST", "{}"))
            assertTrue(ledger.markKernelEntered("rechazado"))
            assertEquals("KERNEL_ACTIVO", db.paymentAttemptDao().getById("rechazado")?.state)
            assertFalse("KERNEL_ACTIVO no es descartable: el kernel pudo aprobar solo",
                ledger.markDiscardedBeforeCharge("rechazado", "user_cancel"))
            assertFalse("y mientras corre, la terminal esta tomada",
                ledger.openAttempt("mientras-corre", "venue", "BLUMON", 100, 0, "FAST", "{}"))

            // El kernel dijo que NO antes de salir a autorizar: no hubo cobro, la caja sigue viva.
            assertTrue(ledger.markKernelRefused("rechazado", "DENIED_BY_KERNEL"))
            assertEquals("DESCARTADA", db.paymentAttemptDao().getById("rechazado")?.state)
            assertTrue("un rechazo del kernel no puede dejar la caja sin cobrar",
                ledger.openAttempt("siguiente-venta", "venue", "BLUMON", 100, 0, "FAST", "{}"))

            // En cambio un desenlace INCIERTO no se da por resuelto: pudo haber cobro.
            assertTrue(ledger.markKernelEntered("siguiente-venta"))
            ledger.markIndeterminate("siguiente-venta", "TIMEOUT")
            assertEquals("INDETERMINADO", db.paymentAttemptDao().getById("siguiente-venta")?.state)
            assertNotNull("un TIMEOUT del kernel NO acredita que no se cobró", db.paymentAttemptDao().findUnresolvedCharge())
            // En la PAX eso se AVISA y la caja sigue (founder, 24-sep); en el kiosco, donde nadie lee el aviso, aparta.
            assertFalse("en el kiosco un TIMEOUT del kernel NO libera la terminal",
                ledger.openAttempt("tercera", "venue", "BLUMON", 100, 0, "FAST", "{}", esKiosco = true))
        } finally { db.close() }
    }

    /**
     * 🔴 F0 — CERCAR LA VENTA, NO EL APARATO (los dos límites del founder, 12-sep-2026).
     *
     * «No podemos interrumpir el proceso de cobro en un negocio, eso sería catastrófico, pero
     * tampoco dejando de lado que cobren doble». Una obligación pendiente (el SDK YA salió y
     * falta acreditar o registrar) cerca SU venta; no puede apagar el aparato entero, porque
     * una venta distinta no tiene ninguna relación con la incierta.
     *
     * Lo que sigue apartando el aparato es la EJECUCIÓN en curso (PREPARANDO/KERNEL_ACTIVO/
     * AUTORIZANDO: el lector está ocupado) y una obligación pendiente SIN identidad de venta,
     * que es el único caso en el que no hay nada más que la cerque — ése lo fijan las dos
     * pruebas de arriba, que dicen «orderless» en su nombre a propósito.
     */
    @Test fun `P1 una venta incierta CON identidad cerca su venta y deja cobrar las demas`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)

            // Una venta con cuenta se queda sin desenlace: el NIP equivocado de la N86 (E699).
            assertTrue(ledger.openAttempt("incierta", "venue", "ANGELPAY", 1200, 0, "ORDER", """{"orderId":"cuenta-1"}"""))
            assertTrue(ledger.markAuthorizing("incierta"))
            ledger.markIndeterminate("incierta", "AngelPay sin veredicto: E699")
            assertEquals("INDETERMINADO", db.paymentAttemptDao().getById("incierta")?.state)

            // 🔴 El negocio SIGUE COBRANDO: otra cuenta no tiene relación con la incierta.
            assertTrue("una venta distinta debe poder cobrarse: el aparato no se apaga por una obligación pendiente",
                ledger.openAttempt("otra-cuenta", "venue", "ANGELPAY", 5000, 0, "ORDER", """{"orderId":"cuenta-2"}"""))
            assertTrue(ledger.markKernelEntered("otra-cuenta"))
            assertTrue(ledger.markKernelRefused("otra-cuenta", "DENIED_BY_KERNEL"))

            // 🔴 …y la venta incierta SIGUE CERCADA: sobre ELLA no se cobra otra vez.
            assertFalse("la cuenta con el cobro sin desenlace no admite un segundo cobro",
                ledger.openAttempt("recobro", "venue", "ANGELPAY", 1200, 0, "ORDER", """{"orderId":"cuenta-1"}"""))

            // Y la solicitud del POS cerca igual aunque la cuenta sea otra.
            assertTrue(ledger.openAttempt("remota", "venue", "ANGELPAY", 900, 0, "ORDER",
                """{"orderId":"cuenta-3","terminalPaymentRequestId":"req-9"}"""))
            assertTrue(ledger.markAuthorizing("remota"))
            ledger.markIndeterminate("remota", "se fue la red")
            assertTrue("otra venta más sigue pudiendo cobrarse con dos obligaciones pendientes",
                ledger.openAttempt("cuarta", "venue", "ANGELPAY", 700, 0, "ORDER", """{"orderId":"cuenta-4"}"""))
        } finally { db.close() }
    }

    /** 🔴 Mientras el LECTOR está ocupado no entra nadie, tenga identidad o no: es físico. */
    @Test fun `P1 la ejecucion en curso sigue apartando el aparato aunque la venta tenga identidad`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            assertTrue(ledger.openAttempt("leyendo", "venue", "ANGELPAY", 1000, 0, "ORDER", """{"orderId":"cuenta-1"}"""))
            assertTrue(ledger.markKernelEntered("leyendo"))
            assertFalse("con el kernel dentro no se puede abrir otro cobro, ni de otra cuenta",
                ledger.openAttempt("otra", "venue", "ANGELPAY", 2000, 0, "ORDER", """{"orderId":"cuenta-2"}"""))
            assertFalse("tampoco uno sin cuenta",
                ledger.openAttempt("suelta", "venue", "ANGELPAY", 2000, 0, "FAST", "{}"))
        } finally { db.close() }
    }

    @Test fun `review recreated ledger rejects orderless authorization while terminal has unknown`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val first = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            assertTrue(first.openAttempt("old", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
            assertTrue(first.markAuthorizing("old"))
            first.markIndeterminate("old", "process died")
            val recreated = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            val admitted = recreated.openAttempt("new", "venue", "BLUMON", 200, 0, "FAST", "{}") &&
                recreated.markAuthorizing("new")
            assertFalse("An orderless sale must retain the terminal execution hold across recreation", admitted)
            assertEquals("INDETERMINADO", db.paymentAttemptDao().getById("old")?.state)
        } finally { db.close() }
    }

    @Test fun `unknown survives new ledger reader and remains venue scoped`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.paymentAttemptDao()
            val row = PaymentAttemptEntity("attempt", "venue", "BLUMON", state = "INDETERMINADO",
                amountCents = 100, tipCents = 0, recordingRoute = "ORDER", paymentContextJson = """{"orderId":"order"}""",
                createdAt = 1, updatedAt = 1)
            dao.insert(row)
            dao.insert(row.copy(attemptId = "other", venueId = "other"))
            dao.insert(row.copy(attemptId = "done", state = "REGISTRADO"))
            assertEquals(1, dao.observeUnresolvedCount("venue").first())
            assertEquals("attempt", dao.findUnresolvedOrder("venue", "\"orderId\":\"order\"")?.attemptId)
            assertNull(dao.findUnresolvedOrder("other-venue", "\"orderId\":\"order\""))
            dao.casTransition("attempt", listOf("INDETERMINADO"), "REGISTRADO", 2)
            assertEquals(0, dao.observeUnresolvedCount("venue").first())
        } finally { db.close() }
    }

    /**
     * 🔴 Con dos obligaciones pendientes a la vez —lo que la cerca por venta hizo posible— adoptar
     * «la más reciente» le cuelga a una solicitud el desenlace de otra cuenta.
     */
    @Test fun `P1 un ViewModel recreado adopta el cobro de SU solicitud, no el mas reciente`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)

            assertTrue(ledger.openAttempt("mia", "venue", "ANGELPAY", 1200, 0, "ORDER",
                """{"orderId":"cuenta-1","terminalPaymentRequestId":"req-mia"}"""))
            assertTrue(ledger.markAuthorizing("mia"))
            ledger.markIndeterminate("mia", "murio el proceso")

            // Otra venta queda pendiente DESPUÉS: es la más reciente, y no es la mía.
            assertTrue(ledger.openAttempt("ajena", "venue", "ANGELPAY", 5000, 0, "ORDER",
                """{"orderId":"cuenta-2","terminalPaymentRequestId":"req-ajena"}"""))
            assertTrue(ledger.markAuthorizing("ajena"))
            ledger.markIndeterminate("ajena", "se fue la red")

            assertEquals("se adopta por identidad, no por reloj",
                "mia", ledger.adoptarCobroDeLaSolicitud("req-mia")?.attemptId)
            assertEquals("ajena", ledger.adoptarCobroDeLaSolicitud("req-ajena")?.attemptId)

            // 🔴 Una solicitud que no nombra ninguna fila NO adivina teniendo dos candidatas.
            assertNull("con dos pendientes y ninguna suya, no se adopta nada",
                ledger.adoptarCobroDeLaSolicitud("req-desconocida"))
        } finally { db.close() }
    }

    /**
     * 🔴 El P1 que encontró Codex el 12-sep: «la única pendiente sin dueño» describe también a una
     * venta LOCAL legítima. Adoptarla le colgaba a esa venta la autorización de un cobro del POS,
     * y la dejaba REGISTRADA sin haberse registrado nunca. Unicidad NO demuestra pertenencia.
     */
    @Test fun `P1 una venta LOCAL pendiente jamas se adopta como el cobro de una solicitud del POS`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)

            // A: venta LOCAL (sin solicitud del POS) que queda incierta. Es la ÚNICA pendiente.
            assertTrue(ledger.openAttempt("local", "venue", "ANGELPAY", 12000, 0, "ORDER",
                """{"orderId":"OA"}"""))
            assertTrue(ledger.markAuthorizing("local"))
            ledger.markIndeterminate("local", "murio el proceso")

            // El ViewModel recreado de un cobro del POS pregunta por SU solicitud.
            assertNull("la venta local no es de nadie del POS: adoptarla le cuelga el cobro de otro",
                ledger.adoptarCobroDeLaSolicitud("req-del-pos"))

            // Y la suya, cuando existe, la sigue encontrando.
            assertTrue(ledger.openAttempt("remota", "venue", "ANGELPAY", 900, 0, "ORDER",
                """{"orderId":"OB","terminalPaymentRequestId":"req-del-pos"}"""))
            // PREPARANDO no es una obligación: una fila que sólo reservó no pudo mover dinero.
            assertTrue(ledger.markAuthorizing("remota"))
            assertEquals("remota", ledger.adoptarCobroDeLaSolicitud("req-del-pos")?.attemptId)
        } finally { db.close() }
    }

    /**
     * 🔴 La secuencia exacta que encontró Codex (2026-09-12): B se registra ANTES de que su
     * callback llegue, así que buscar por SU solicitud no encuentra nada y queda UNA sola fila
     * pendiente — la de A, con su propia solicitud escrita en el contexto. Adoptarla le colgaría
     * a A la autorización de B.
     */
    @Test fun `P1 no se adopta una fila que nombra OTRA solicitud aunque sea la unica pendiente`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)

            assertTrue(ledger.openAttempt("A", "venue", "ANGELPAY", 1200, 0, "ORDER",
                """{"orderId":"OA","terminalPaymentRequestId":"RA"}"""))
            assertTrue(ledger.markAuthorizing("A"))
            ledger.markIndeterminate("A", "sin veredicto")

            assertTrue(ledger.openAttempt("B", "venue", "ANGELPAY", 900, 0, "ORDER",
                """{"orderId":"OB","terminalPaymentRequestId":"RB"}"""))
            assertTrue(ledger.markAuthorizing("B"))
            // La recuperación cierra B antes de que llegue su callback: deja de estar pendiente.
            db.paymentAttemptDao().casTransition("B", listOf("AUTORIZANDO"), "REGISTRADO", 99)
            assertEquals("REGISTRADO", db.paymentAttemptDao().getById("B")?.state)

            assertNull("la fila de A dice RA: nunca puede adoptarse como el cobro de RB",
                ledger.adoptarCobroDeLaSolicitud("RB"))
            assertEquals("y A sigue siendo de A", "A", ledger.adoptarCobroDeLaSolicitud("RA")?.attemptId)
        } finally { db.close() }
    }

    /** 🔴 Un `orderId` de puros espacios no puede soltar el aparato Y quedarse sin cerca. */
    @Test fun `P1 un orderId de espacios cerca su venta igual que cualquier otro`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            assertTrue(ledger.openAttempt("rara", "venue", "ANGELPAY", 1200, 0, "ORDER", """{"orderId":"   "}"""))
            assertTrue(ledger.markAuthorizing("rara"))
            ledger.markIndeterminate("rara", "sin veredicto")
            assertFalse("la misma venta, por rara que sea su llave, no admite otro cobro",
                ledger.openAttempt("recobro", "venue", "ANGELPAY", 1200, 0, "ORDER", """{"orderId":"   "}"""))
            assertTrue("y otra venta sí puede cobrarse",
                ledger.openAttempt("otra", "venue", "ANGELPAY", 500, 0, "ORDER", """{"orderId":"cuenta-9"}"""))
        } finally { db.close() }
    }

    /** 🔴 HOST_RESPONDIO aparta el aparato SIEMPRE: la PAX lo escribe antes de cerrar el kernel. */
    @Test fun `P1 host respondio aparta el aparato aunque la venta tenga identidad`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            assertTrue(ledger.openAttempt("emv", "venue", "BLUMON", 1200, 0, "ORDER", """{"orderId":"cuenta-1"}"""))
            assertTrue(ledger.markAuthorizing("emv"))
            db.paymentAttemptDao().casTransition("emv", listOf("AUTORIZANDO"), "HOST_RESPONDIO", 50)
            assertFalse("el kernel puede seguir dentro: nadie más entra",
                ledger.openAttempt("otra", "venue", "BLUMON", 500, 0, "ORDER", """{"orderId":"cuenta-2"}"""))
        } finally { db.close() }
    }

    /**
     * 🔴 F0 se apoya en que el CAJERO distingue «reintento de la venta incierta» de «venta nueva
     * idéntica». En autoservicio no hay cajero: la pantalla la ve el cliente. Ahí cualquier
     * obligación pendiente vuelve a apartar el aparato (Codex, 12-sep: el kiosco que se reinicia
     * pierde su orden en memoria, crea otra y cobra dos veces la misma compra).
     */
    @Test fun `P1 en el kiosco cualquier obligacion pendiente sigue apartando el aparato`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            assertTrue(ledger.openAttempt("incierta", "venue", "BLUMON", 12000, 0, "ORDER", """{"orderId":"compra-1"}"""))
            assertTrue(ledger.markAuthorizing("incierta"))
            ledger.markIndeterminate("incierta", "murio el proceso")

            assertFalse("en el kiosco nadie puede juzgar: no se abre otro cobro",
                ledger.openAttempt("kiosco", "venue", "BLUMON", 5000, 0, "ORDER",
                    """{"orderId":"compra-2"}""", esKiosco = true))

            // En el mostrador, la MISMA situación sí deja cobrar: ahí hay quien distinga, y el aviso se lo dice.
            assertTrue("con cajero enfrente el negocio sigue cobrando",
                ledger.openAttempt("mostrador", "venue", "BLUMON", 5000, 0, "ORDER", """{"orderId":"compra-2"}"""))
        } finally { db.close() }
    }

    /**
     * 🔴 P1 que encontró Codex el 12-sep sobre F0: la cuarentena decide por RELOJ, no por
     * evidencia. Un kernel colgado pasa a INDETERMINADO a los 10 min sin que nadie haya
     * comprobado que la llamada nativa terminó — y F0 lo leía como «el SDK ya salió» y soltaba el
     * lector para otra venta. El aparato es UNO: eso es un cobro doble.
     */
    @Test fun `P1 una fila en cuarentena POR RELOJ sigue apartando el aparato, la del procesador no`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            val dao = db.paymentAttemptDao()

            // Un cobro entra al kernel y se cuelga.
            assertTrue(ledger.openAttempt("colgado", "venue", "BLUMON", 12000, 0, "ORDER", """{"orderId":"cuenta-1"}"""))
            assertTrue(ledger.markKernelEntered("colgado"))
            // Pasa el plazo y el barrido lo pone en cuarentena SIN saber si el SDK salió.
            assertEquals(1, dao.quarantineStaleKernel("venue", olderThan = Long.MAX_VALUE, now = 99))
            assertEquals("INDETERMINADO", dao.getById("colgado")?.state)

            assertFalse("nadie acreditó que la llamada nativa terminara: el lector sigue tomado",
                ledger.openAttempt("otra", "venue", "BLUMON", 5000, 0, "ORDER", """{"orderId":"cuenta-2"}"""))

            // En cambio, una incierta que el PROCESADOR contestó sí libera el aparato y cerca su venta.
            assertEquals(1, dao.casTransition("colgado", listOf("INDETERMINADO"), "REGISTRADO", 100))
            assertTrue(ledger.openAttempt("contestada", "venue", "BLUMON", 7000, 0, "ORDER", """{"orderId":"cuenta-3"}"""))
            assertTrue(ledger.markAuthorizing("contestada"))
            ledger.markIndeterminate("contestada", "AngelPay E699")
            assertTrue("una incierta con veredicto del procesador cerca su venta, no el aparato",
                ledger.openAttempt("siguiente", "venue", "BLUMON", 3000, 0, "ORDER", """{"orderId":"cuenta-4"}"""))
        } finally { db.close() }
    }

    /**
     * 🔴 Codex, 12-sep: la recuperación admite `HOST_RESPONDIO` por ANTIGÜEDAD y, al fallar el
     * registro, escribía su mensaje encima de la marca de cuarentena — borrando la única señal de
     * que nadie acreditó el final del SDK, y soltando el lector. La marca tiene que sobrevivir.
     */
    @Test fun `P1 la marca de cuarentena sobrevive a un registro fallido de la recuperacion`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            val dao = db.paymentAttemptDao()

            assertTrue(ledger.openAttempt("colgado", "venue", "BLUMON", 12000, 0, "ORDER", """{"orderId":"cuenta-1"}"""))
            assertTrue(ledger.markKernelEntered("colgado"))
            assertEquals(1, dao.quarantineStaleKernel("venue", olderThan = Long.MAX_VALUE, now = 10))
            // La recuperación lo lleva a HOST_RESPONDIO y de ahí a REGISTRO_FALLIDO.
            assertEquals(1, dao.casTransition("colgado", listOf("INDETERMINADO"), "HOST_RESPONDIO", 20))
            assertEquals(1, dao.claimRecovery("colgado", "venue", now = 30, leaseUntil = 9_000))
            assertEquals(1, dao.completeRecovery("colgado", "venue", ownedLease = 9_000,
                newState = "REGISTRO_FALLIDO", now = 40, error = "Cobrado; registro pendiente"))

            assertEquals("la marca no se borra con el mensaje del registro",
                "cuarentena_por_antiguedad", dao.getById("colgado")?.lastError)
            assertFalse("sigue sin acreditarse que el SDK terminó: el lector no se suelta",
                ledger.openAttempt("otra", "venue", "BLUMON", 5000, 0, "ORDER", """{"orderId":"cuenta-2"}"""))
        } finally { db.close() }
    }

    /**
     * 🔴 Las filas que YA viven en los aparatos instalados: la cuarentena de la versión anterior
     * no dejaba marca. Ausencia de marca no acredita respuesta del procesador, así que apartan.
     */
    @Test fun `P1 un INDETERMINADO sin motivo, de una version anterior, sigue apartando el aparato`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            db.paymentAttemptDao().insert(PaymentAttemptEntity("vieja", "venue", "BLUMON",
                state = "INDETERMINADO", amountCents = 12000, tipCents = 0, recordingRoute = "ORDER",
                paymentContextJson = """{"orderId":"cuenta-1"}""", createdAt = 1, updatedAt = 1))
            assertFalse("sin motivo no se sabe de dónde salió esa incertidumbre: el lector no se suelta",
                ledger.openAttempt("otra", "venue", "BLUMON", 5000, 0, "ORDER", """{"orderId":"cuenta-2"}"""))
        } finally { db.close() }
    }

    /**
     * 🔴 El último P1 de Codex (12-sep): la PAX escribe `HOST_RESPONDIO` y todavía le falta
     * `CompleteEmvTrans`. La recuperación lo toma POR ANTIGÜEDAD y, si el registro falla, lo deja
     * en `REGISTRO_FALLIDO`. Esa fila NUNCA pasó por cuarentena, así que no lleva marca — y F0
     * soltaba el lector con la llamada nativa posiblemente viva. Ahora `REGISTRO_FALLIDO` aparta.
     */
    @Test fun `P1 un registro fallido de la recuperacion no suelta el lector aunque nunca hubo marca`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            val dao = db.paymentAttemptDao()

            assertTrue(ledger.openAttempt("pax", "venue", "BLUMON", 12000, 0, "ORDER", """{"orderId":"cuenta-1"}"""))
            assertTrue(ledger.markAuthorizing("pax"))
            // La PAX escribe el resultado del host ANTES de cerrar el EMV, y sin motivo.
            assertEquals(1, dao.casTransition("pax", listOf("AUTORIZANDO"), "HOST_RESPONDIO", 20))
            assertNull(dao.getById("pax")?.lastError)
            // La recuperación lo toma por antigüedad y el registro falla.
            assertEquals(1, dao.claimRecovery("pax", "venue", now = 30, leaseUntil = 9_000))
            assertEquals(1, dao.completeRecovery("pax", "venue", ownedLease = 9_000,
                newState = "REGISTRO_FALLIDO", now = 40, error = "Cobrado; registro pendiente"))

            assertFalse("CompleteEmvTrans puede seguir colgado: el lector no se suelta",
                ledger.openAttempt("otra", "venue", "BLUMON", 5000, 0, "ORDER", """{"orderId":"cuenta-2"}"""))

            // Y en cuanto el cobro llega a la cola —el camino normal— el aparato queda libre.
            assertEquals(1, dao.casTransition("pax", listOf("REGISTRO_FALLIDO"), "ENTREGADA_A_COLA", 50))
            assertTrue("con el cobro ya encolado el negocio sigue cobrando",
                ledger.openAttempt("siguiente", "venue", "BLUMON", 5000, 0, "ORDER", """{"orderId":"cuenta-2"}"""))
        } finally { db.close() }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Decisión del founder (24-sep): en la PAX un cobro que quedó sin veredicto AVISA, no traba.
    // La PAX no recibe el aviso del banco y su SDK no acepta nuestra referencia: apartarla hasta
    // tener evidencia la dejaba sin cobrar horas (la de pruebas, con un $10; Mindform, con diez).
    // Lo que sigue apartando es lo que puede seguir EJECUTÁNDOSE: el lector en uso, la cuarentena
    // por reloj, la fila sin motivo de una versión vieja; y en el kiosco, todo (no hay cajero que lea).
    // ═══════════════════════════════════════════════════════════════════════════

    private fun ledgerEnMemoria(): Pair<AvoqadoDatabase, PaymentAttemptLedger> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
        return db to PaymentAttemptLedger(db.paymentAttemptDao(), settings)
    }

    @Test fun `P1 en la PAX un Pago rapido sin veredicto avisa pero deja cobrar la siguiente venta`() = runTest {
        val (db, ledger) = ledgerEnMemoria()
        try {
            val dao = db.paymentAttemptDao()
            assertTrue(ledger.openAttempt("dudoso", "venue", "BLUMON", 1000, 0, "FAST", "{}"))
            assertTrue(ledger.markAuthorizing("dudoso"))
            ledger.markIndeterminate("dudoso", "Blumon sin veredicto: MomentumFailure · NO AUTORIZADO")

            assertNull("la PAX no queda apartada por un cobro que ya terminó sin veredicto", dao.findTerminalHold())
            assertTrue("la siguiente venta entra", ledger.openAttempt("siguiente", "venue", "BLUMON", 1000, 0, "FAST", "{}"))
            assertTrue("y llega al lector", ledger.markKernelEntered("siguiente"))

            // El cinturón: el cobro dudoso sigue guardado y sigue en el aviso del mostrador.
            assertEquals("INDETERMINADO", dao.getById("dudoso")?.state)
            val aviso = db.remotePaymentRequestDao().observePendingObligations("venue").first()
            assertEquals(listOf(1000L), aviso.map { it.totalCentavos })
        } finally { db.close() }
    }

    @Test fun `P1 la PAX con un cobro sin veredicto tambien deja AUTORIZAR la siguiente venta`() = runTest {
        val (db, ledger) = ledgerEnMemoria()
        try {
            assertTrue(ledger.openAttempt("dudoso", "venue", "BLUMON", 1000, 0, "FAST", "{}"))
            assertTrue(ledger.markAuthorizing("dudoso"))
            ledger.markIndeterminate("dudoso", "Blumon sin veredicto: GenericFailure")
            assertTrue(ledger.openAttempt("siguiente", "venue", "BLUMON", 2500, 0, "FAST", "{}"))
            assertTrue("la barrera de AUTORIZANDO dice lo mismo que la de reservar", ledger.markAuthorizing("siguiente"))
        } finally { db.close() }
    }

    @Test fun `P1 en el kiosco de la PAX el cobro sin veredicto sigue apartando el aparato`() = runTest {
        val (db, ledger) = ledgerEnMemoria()
        try {
            assertTrue(ledger.openAttempt("dudoso", "venue", "BLUMON", 1000, 0, "FAST", "{}"))
            assertTrue(ledger.markAuthorizing("dudoso"))
            ledger.markIndeterminate("dudoso", "Blumon sin veredicto: GenericFailure")
            assertFalse("en autoservicio nadie lee el aviso: no se abre otro cobro",
                ledger.openAttempt("kiosco", "venue", "BLUMON", 1000, 0, "FAST", "{}", esKiosco = true))
        } finally { db.close() }
    }

    @Test fun `P1 la Nexgo no cambia - su cobro sin veredicto y sin venta sigue apartando hasta el aviso del banco`() = runTest {
        val (db, ledger) = ledgerEnMemoria()
        try {
            assertTrue(ledger.openAttempt("dudoso", "venue", "ANGELPAY", 1000, 0, "FAST", "{}"))
            assertTrue(ledger.markAuthorizing("dudoso"))
            ledger.markIndeterminate("dudoso", "AngelPay sin veredicto: TIMEOUT")
            assertNotNull(db.paymentAttemptDao().findTerminalHold())
            assertFalse(ledger.openAttempt("siguiente", "venue", "ANGELPAY", 1000, 0, "FAST", "{}"))
        } finally { db.close() }
    }

    @Test fun `P1 en la PAX la cuarentena por reloj sigue apartando aunque sea un Pago rapido`() = runTest {
        val (db, ledger) = ledgerEnMemoria()
        try {
            val dao = db.paymentAttemptDao()
            assertTrue(ledger.openAttempt("colgado", "venue", "BLUMON", 1000, 0, "FAST", "{}"))
            assertTrue(ledger.markKernelEntered("colgado"))
            assertEquals(1, dao.quarantineStaleKernel("venue", olderThan = Long.MAX_VALUE, now = 99))
            assertNotNull("nadie acreditó que la llamada nativa terminara", dao.findTerminalHold())
            assertFalse(ledger.openAttempt("otra", "venue", "BLUMON", 1000, 0, "FAST", "{}"))
        } finally { db.close() }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Decisión del founder (25-sep): «Cobrar» también AVISA, no traba — igual que Pago rápido.
    // Una duda de la PAX SIN dinero conocido ya no cerca su venta: el cajero ve el aviso y decide.
    // Si SÍ hay dinero (Avoqado lo confirmó, o hay veto), no es duda: esa venta sigue cercada.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun `P1 en la PAX una venta de Cobrar sin veredicto avisa pero se puede volver a cobrar`() = runTest {
        val (db, ledger) = ledgerEnMemoria()
        try {
            val dao = db.paymentAttemptDao()
            assertTrue(ledger.openAttempt("dudoso", "venue", "BLUMON", 1000, 0, "ORDER", """{"orderId":"cuenta-1"}"""))
            assertTrue(ledger.markAuthorizing("dudoso"))
            ledger.markIndeterminate("dudoso", "Blumon sin veredicto: MomentumFailure · NO AUTORIZADO")

            assertNull("una duda sin dinero ya no cerca su venta", ledger.retencionDeLaVenta("cuenta-1"))
            assertTrue("la misma venta se puede volver a cobrar",
                ledger.openAttempt("recobro", "venue", "BLUMON", 1000, 0, "ORDER", """{"orderId":"cuenta-1"}"""))
            assertTrue("y llega al lector", ledger.markKernelEntered("recobro"))

            // El cinturón: la duda sigue guardada y en el aviso del mostrador.
            assertEquals("INDETERMINADO", dao.getById("dudoso")?.state)
            val aviso = db.remotePaymentRequestDao().observePendingObligations("venue").first()
            assertEquals(listOf(1000L), aviso.map { it.totalCentavos })
        } finally { db.close() }
    }

    @Test fun `P1 en la PAX la venta con dinero confirmado por Avoqado sigue cercada`() = runTest {
        val (db, ledger) = ledgerEnMemoria()
        try {
            assertTrue(ledger.openAttempt("cobrado", "venue", "BLUMON", 1000, 0, "ORDER", """{"orderId":"cuenta-2"}"""))
            assertTrue(ledger.markAuthorizing("cobrado"))
            ledger.markIndeterminate("cobrado", "Blumon sin veredicto: GenericFailure")
            assertTrue(ledger.marcarEvidenciaPositivaDelServidor("venue", "cobrado").getOrThrow())

            assertNotNull("con dinero confirmado no es duda: la venta sigue cercada", ledger.retencionDeLaVenta("cuenta-2"))
            assertFalse("no se cobra dos veces lo seguro",
                ledger.openAttempt("doble", "venue", "BLUMON", 1000, 0, "ORDER", """{"orderId":"cuenta-2"}"""))
        } finally { db.close() }
    }

    @Test fun `P1 en la PAX la venta con veto del servidor sigue cercada`() = runTest {
        val (db, ledger) = ledgerEnMemoria()
        try {
            assertTrue(ledger.openAttempt("vetado", "venue", "BLUMON", 1000, 0, "ORDER", """{"orderId":"cuenta-3"}"""))
            assertTrue(ledger.markAuthorizing("vetado"))
            ledger.markIndeterminate("vetado", "Blumon sin veredicto: GenericFailure")
            assertTrue(ledger.marcarVetoDelServidor("venue", "vetado", "UNATTRIBUTED_EVIDENCE").getOrThrow())

            assertNotNull(ledger.retencionDeLaVenta("cuenta-3"))
            assertFalse(ledger.openAttempt("otra-vez", "venue", "BLUMON", 1000, 0, "ORDER", """{"orderId":"cuenta-3"}"""))
        } finally { db.close() }
    }

    @Test fun `P1 en la PAX la cuarentena por reloj sigue cercando su venta`() = runTest {
        val (db, ledger) = ledgerEnMemoria()
        try {
            val dao = db.paymentAttemptDao()
            assertTrue(ledger.openAttempt("colgado", "venue", "BLUMON", 1000, 0, "ORDER", """{"orderId":"cuenta-4"}"""))
            assertTrue(ledger.markKernelEntered("colgado"))
            assertEquals(1, dao.quarantineStaleKernel("venue", olderThan = Long.MAX_VALUE, now = 99))
            assertNotNull("nadie acreditó que el lector terminara", ledger.retencionDeLaVenta("cuenta-4"))
        } finally { db.close() }
    }
}
