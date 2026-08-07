package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * `QuarantineSheet` — cuarentena visible (Plan C, Task 9). Literal del plan
 * (`labelFor`, el ciclo load/dismiss) más la cobertura que pide el enunciado
 * explícito de la tarea:
 *
 * - un intent `REJECTED` aparece en cuarentena
 * - un intent `RETRY` NUNCA aparece (se queda `PENDING`) — la prueba de
 *   contrato completa vive en `SyncOutboxTest.un_intent_RETRY_se_queda_PENDING_y_no_se_pierde`
 *   (instrumented, Room real: ahí es donde `RETRY` físicamente no se
 *   persiste como fila rechazada); aquí se cubre el límite de ESTE
 *   ViewModel — su ÚNICA fuente de datos es [SyncOutbox.rejectedIntents],
 *   así que si el outbox no expone un RETRY, este ViewModel no tiene forma
 *   de mostrarlo por accidente.
 * - un tipo de intent desconocido se muestra (reportable), nunca se pierde
 *   en silencio ni truena
 * - la acción de recuperación ("Marcar resuelta" → [QuarantineViewModel.dismiss])
 *   hace lo correcto: llama al outbox con el id correcto, recarga después, y
 *   es un no-op seguro si se invoca antes de cualquier [QuarantineViewModel.load]
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuarantineViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val outbox = mockk<SyncOutbox>()
    private val deviceInfoManager = mockk<DeviceInfoManager>()
    private val permissionsRepository = mockk<PermissionsRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearMocks(outbox, deviceInfoManager, permissionsRepository)
        // MANAGER+ por defecto en la mayoría de los tests existentes — solo
        // el bloque de gate de rol (más abajo) sobre-escribe esto a `false`.
        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): QuarantineViewModel = QuarantineViewModel(
        outbox = outbox,
        deviceInfoManager = deviceInfoManager,
        permissionsRepository = permissionsRepository,
    )

    private fun rejected(
        type: String,
        id: String = "intent-1",
        errorCode: String? = "TABLE_OWNED_BY_OTHER",
        message: String? = null,
    ) = SyncOutbox.RejectedIntent(id = id, type = type, errorCode = errorCode, message = message, createdAt = 1_000L)

    // region — labelFor: literal del plan

    @Test
    fun cada_tipo_de_intent_tiene_etiqueta_en_espanol() {
        // Un mesero no puede accionar sobre "ADD_ITEMS".
        assertThat(labelFor("OPEN_TABLE")).isEqualTo("Abrir mesa")
        assertThat(labelFor("ADD_ITEMS")).isEqualTo("Enviar ronda")
        assertThat(labelFor("PAY_CASH")).isEqualTo("Cobro en efectivo")
        assertThat(labelFor("CLEAR_TABLE")).isEqualTo("Liberar mesa")
    }

    @Test
    fun todo_tipo_conocido_tiene_etiqueta() {
        // Un tipo nuevo del server sin etiqueta sale como texto crudo en pantalla.
        SyncIntentTypes.ALL.forEach { tipo ->
            assertThat(labelFor(tipo)).isNotEqualTo(tipo)
        }
    }

    @Test
    fun un_tipo_de_intent_no_reconocido_se_muestra_en_vez_de_perderse() {
        // El server puede agregar un 15o tipo mañana — labelFor NUNCA debe
        // tronar ni regresar texto vacío; el tipo crudo queda visible para
        // que sea reportable, en vez de esconderse detrás de un genérico.
        val label = labelFor("UN_TIPO_QUE_TODAVIA_NO_EXISTE")
        assertThat(label).isNotEmpty()
        assertThat(label).contains("UN_TIPO_QUE_TODAVIA_NO_EXISTE")
    }

    // endregion

    // region — resolutionHintFor: nunca el errorCode crudo, siempre algo accionable

    @Test
    fun cada_errorCode_REJECTED_conocido_tiene_guia_en_espanol_no_el_codigo_crudo() {
        val knownRejectedCodes = listOf(
            "TABLE_OWNED_BY_OTHER",
            "STALE_DEVICE_SEQUENCE",
            "ACTOR_MISMATCH",
            "PERMISSION_DENIED",
            "ORDER_NOT_FOUND",
            "ITEMS_NOT_RESOLVED",
            "INVALID_PAYLOAD",
            "FEATURE_LOCKED",
            "OUTCOME_UNKNOWN",
            "UNKNOWN_INTENT_TYPE",
        )
        knownRejectedCodes.forEach { code ->
            val hint = resolutionHintFor(code, SyncIntentTypes.OPEN_TABLE)
            assertThat(hint).isNotEqualTo(code)
            assertThat(hint).isNotEmpty()
        }
    }

    @Test
    fun sin_errorCode_un_PAY_CASH_rechazado_avisa_que_el_efectivo_sigue_en_caja() {
        // El dinero YA está en la mano del mesero — la guía tiene que decirlo
        // explícito, nunca un genérico que suene a que el efectivo se perdió.
        val hint = resolutionHintFor(errorCode = null, type = SyncIntentTypes.PAY_CASH)
        assertThat(hint).contains("efectivo")
        assertThat(hint).contains("caja")
    }

    @Test
    fun sin_errorCode_una_accion_que_no_es_PAY_CASH_da_guia_generica() {
        val hint = resolutionHintFor(errorCode = null, type = SyncIntentTypes.ADD_ITEMS)
        assertThat(hint).isNotEmpty()
        assertThat(hint).doesNotContain("efectivo")
    }

    // endregion

    // region — load/dismiss: literal del plan + no-op seguro

    @Test
    fun un_rechazo_no_desaparece_hasta_que_el_mesero_lo_descarta() = runTest {
        coEvery { outbox.rejectedIntents(any()) } returns listOf(rejected("ADD_ITEMS"))
        coEvery { outbox.dismissRejected(any(), any()) } returns Unit
        val viewModel = createViewModel()

        viewModel.load("venue-a")
        assertThat(viewModel.state.value.items).hasSize(1)

        viewModel.dismiss("intent-1")
        coVerify { outbox.dismissRejected("venue-a", "intent-1") }
    }

    @Test
    fun un_rechazo_cargado_se_traduce_a_etiqueta_y_guia_nunca_al_codigo_crudo() = runTest {
        coEvery { outbox.rejectedIntents(any()) } returns listOf(
            rejected(type = "PAY_CASH", id = "intent-2", errorCode = "OUTCOME_UNKNOWN", message = "server timeout"),
        )
        val viewModel = createViewModel()

        viewModel.load("venue-a")

        val item = viewModel.state.value.items.single()
        assertThat(item.label).isEqualTo("Cobro en efectivo")
        assertThat(item.resolutionHint).isNotEqualTo("OUTCOME_UNKNOWN")
        assertThat(item.resolutionHint).isNotEmpty()
        // El mensaje crudo del server SÍ se conserva (contexto adicional),
        // pero nunca reemplaza la guía accionable — ambos coexisten.
        assertThat(item.message).isEqualTo("server timeout")
    }

    @Test
    fun un_intent_RETRY_nunca_aparece_porque_el_outbox_no_lo_expone_como_rechazo() = runTest {
        // RETRY (VERSION_CONFLICT, hoy el único) NUNCA se persiste como
        // REJECTED (ver SyncOutbox.applyAck) — su única fuente de datos
        // (rejectedIntents) por diseño jamás lo devuelve. Simula el caso
        // real: solo hay un intent en RETRY pendiente, cero rechazos.
        coEvery { outbox.rejectedIntents(any()) } returns emptyList()
        val viewModel = createViewModel()

        viewModel.load("venue-a")

        assertThat(viewModel.state.value.items).isEmpty()
    }

    @Test
    fun dismiss_antes_de_cualquier_load_es_no_op_seguro() = runTest {
        // Defiende contra un tap fantasma de la UI (p.ej. recomposición rara)
        // antes de que la pantalla termine de cargar — nunca debe llamar al
        // outbox con un venueId adivinado o vacío.
        val viewModel = createViewModel()

        viewModel.dismiss("intent-1")

        coVerify(exactly = 0) { outbox.dismissRejected(any(), any()) }
    }

    @Test
    fun dismiss_recarga_la_lista_para_que_el_item_descartado_desaparezca() = runTest {
        // Primera carga: 1 rechazo. Tras dismiss, el mock ya no lo devuelve
        // (como pasaría de verdad tras el DELETE) — dismiss() debe recargar,
        // no confiar en un borrado optimista local.
        coEvery { outbox.rejectedIntents("venue-a") } returns listOf(rejected("ADD_ITEMS"))
        coEvery { outbox.dismissRejected(any(), any()) } returns Unit
        val viewModel = createViewModel()
        viewModel.load("venue-a")
        assertThat(viewModel.state.value.items).hasSize(1)

        coEvery { outbox.rejectedIntents("venue-a") } returns emptyList()
        viewModel.dismiss("intent-1")

        assertThat(viewModel.state.value.items).isEmpty()
        coVerify(exactly = 2) { outbox.rejectedIntents("venue-a") } // carga inicial + recarga tras dismiss
    }

    @Test
    fun load_sin_argumento_resuelve_el_venue_de_esta_terminal() = runTest {
        every { deviceInfoManager.getVenueId() } returns "venue-terminal"
        coEvery { outbox.rejectedIntents("venue-terminal") } returns listOf(rejected("OPEN_TABLE"))
        val viewModel = createViewModel()

        viewModel.load()

        assertThat(viewModel.state.value.items).hasSize(1)
        coVerify { outbox.rejectedIntents("venue-terminal") }
    }

    @Test
    fun load_sin_argumento_sin_venue_configurado_no_llama_al_outbox() = runTest {
        every { deviceInfoManager.getVenueId() } returns null
        val viewModel = createViewModel()

        viewModel.load()

        coVerify(exactly = 0) { outbox.rejectedIntents(any()) }
        assertThat(viewModel.state.value.items).isEmpty()
    }

    // endregion

    // region — gate de rol de dismiss (paridad Android, Hallazgo #3 2026-08-06):
    // resolver un rechazo puede ser dinero (PAY_CASH), así que solo
    // MANAGER+ (`payments:refund`) puede descartarlo — mismo permiso que
    // gatea reembolsos.

    @Test
    fun canResolve_refleja_el_permiso_payments_refund_del_backend() = runTest {
        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.canResolve).isTrue()
    }

    @Test
    fun sin_permiso_payments_refund_canResolve_es_falso() = runTest {
        coEvery { permissionsRepository.hasPermission("payments:refund") } returns false
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.canResolve).isFalse()
    }

    @Test
    fun un_WAITER_sin_permiso_no_puede_descartar_un_rechazo() = runTest {
        // Dinero-adyacente: PAY_CASH rechazado significa "el efectivo sigue
        // en caja" — un mesero no debe poder hacerlo desaparecer sin que un
        // gerente lo revise primero.
        coEvery { permissionsRepository.hasPermission("payments:refund") } returns false
        coEvery { outbox.rejectedIntents(any()) } returns listOf(rejected("PAY_CASH"))
        val viewModel = createViewModel()
        viewModel.load("venue-a")
        assertThat(viewModel.state.value.items).hasSize(1)

        viewModel.dismiss("intent-1")

        coVerify(exactly = 0) { outbox.dismissRejected(any(), any()) }
        // El item SIGUE visible — nunca desaparece sin autorización.
        assertThat(viewModel.state.value.items).hasSize(1)
    }

    @Test
    fun un_MANAGER_con_permiso_SI_puede_descartar_un_rechazo() = runTest {
        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { outbox.rejectedIntents(any()) } returns listOf(rejected("PAY_CASH"))
        coEvery { outbox.dismissRejected(any(), any()) } returns Unit
        val viewModel = createViewModel()
        viewModel.load("venue-a")

        viewModel.dismiss("intent-1")

        coVerify(exactly = 1) { outbox.dismissRejected("venue-a", "intent-1") }
    }

    // endregion
}
