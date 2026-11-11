package com.jaac.avoqado_tpv.features.onlineorders.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.features.giftcards.data.CobroConSaldoRepository
import com.jaac.avoqado_tpv.features.giftcards.data.DesenlaceDeSaldo
import com.jaac.avoqado_tpv.features.onlineorders.data.DespuesDeCobrarFolio
import com.jaac.avoqado_tpv.features.onlineorders.data.FoliosRepository
import com.jaac.avoqado_tpv.features.onlineorders.domain.BeneficioDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.data.VigiaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.ConteoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.EstadoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioModificador
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioProducto
import com.jaac.avoqado_tpv.features.onlineorders.domain.PaginaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * «Pedidos por pagar» en la TPV (P3, tienda híbrida etapa 1): la lista, la pantalla del folio y la entrada al cobro.
 * Textos de los mockups v2 §2 y de las reglas de UX (`ux-critica-v2.md`), que ganan sobre el plan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PorPagarViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val estado = MutableStateFlow(EstadoDeFolios())
    private val vigia = mockk<VigiaDeFolios>(relaxed = true)
    private val folios = mockk<FoliosRepository>()
    private val permisos = mockk<PermissionsRepository>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    // R78: la cola de cobros del aparato. Por defecto no hay ningún cobro subiéndose.
    private val cola = mockk<PendingPaymentDao> {
        coEvery { hayCobroSubiendose(any(), any()) } returns false
        coEvery { hayCobroSinRegistrar(any(), any()) } returns false
    }
    // R83 (I3): el cobro con tarjeta que esta terminal todavía está cobrando o registrando. Real: dos banderas atómicas.
    private val cobroEnCurso = PaymentStateHolder()
    // R89 (c): la libreta de cobros con tarjeta. Por defecto no tiene dinero en juego de ninguna venta.
    private val libreta = mockk<PaymentAttemptLedger> { coEvery { hayDineroEnJuegoDeLaVenta(any()) } returns false }

    // 10:30 en la Ciudad de México (UTC-6).
    private val ahora = Instant.parse("2026-11-06T16:30:00Z")
    private val mexico = ZoneOffset.ofHours(-6)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { vigia.estado } returns estado
        every { storage.getVenueId() } returns "v1"
        every { storage.getVenueTimezone() } returns "America/Mexico_City"
        VenueTimeZone.invalidateCache()
        coEvery { permisos.hasPermission(any()) } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        VenueTimeZone.invalidateCache()
    }

    // P2 (parte 2): el cobro con saldo guardado en el aparato. Por defecto no hay ninguno pendiente.
    private val saldo = mockk<CobroConSaldoRepository> { every { pendienteDe(any()) } returns null }
    private val despues = mockk<DespuesDeCobrarFolio>(relaxed = true)

    private fun vm() = PorPagarViewModel(vigia, folios, permisos, storage, cola, cobroEnCurso, libreta, saldo, despues)

    private fun folio(
        id: String,
        folio: String = "W${id.uppercase()}",
        nombre: String? = "Ana",
        total: String = "134.00",
        falta: String = total,
        venceEn: Instant? = ahora.plusSeconds(22 * 60),
        pagadoEn: Instant? = null,
        estado: String = "PENDING_ACCEPTANCE",
    ) = Folio(
        orderId = id, onlineOrderId = "oo-$id", folio = folio, estado = estado, nombre = nombre, telefono = null,
        nota = "sin azúcar, por favor", creadoEn = ahora.minusSeconds(8 * 60), venceEn = venceEn, pagadoEn = pagadoEn,
        total = BigDecimal(total), falta = BigDecimal(falta), estadoDePago = if (falta == total) "PENDING" else "PARTIAL",
        necesitaImprimir = false,
        productos = listOf(FolioProducto("Latte", 1, null, listOf(FolioModificador("Leche de almendra", 1)))),
    )

    private fun cargado(porPagar: List<Folio> = emptyList(), porEntregar: List<Folio> = emptyList(), sinConexion: Boolean = false) {
        estado.value = EstadoDeFolios(
            showTile = true, counts = ConteoDeFolios(porPagar.size, porEntregar.size), porPagar = porPagar, porEntregar = porEntregar,
            sinConexion = sinConexion, actualizadoEnMs = 1_000L,
        )
    }

    private fun pagina(porPagar: List<Folio> = emptyList(), porEntregar: List<Folio> = emptyList()) = PaginaDeFolios(
        horaDelServidor = ahora, showTile = true, pollSeconds = 30, conteo = ConteoDeFolios(porPagar.size, porEntregar.size),
        porPagar = porPagar, porEntregar = porEntregar, siguientes = emptyMap(),
    )

    private fun http(codigo: String) = BackendHttpException(statusCode = 409, message = "x", errorCode = codigo)

    // region — «Por entregar»: Listo y Entregado

    @Test
    fun `Por entregar lista los folios pagados y Entregado o Listo los sacan`() = runTest {
        val leo = folio("m2c", folio = "W8M2C", nombre = "Leo", total = "69.00", falta = "0.00", estado = "ACCEPTED")
        val sofi = folio("k9p", folio = "WK9P2", nombre = "Sofía", falta = "0.00", estado = "ACCEPTED")
        cargado(porEntregar = listOf(leo, sofi))
        coEvery { folios.entregado("v1", "m2c") } returns Result.success(Unit)
        coEvery { folios.listo("v1", "k9p") } returns Result.success(Unit)
        val vm = vm()
        assertThat(vm.ui.value.porEntregar.map { it.folio }).containsExactly("W8M2C", "WK9P2").inOrder()

        vm.entregado(leo)
        assertThat(vm.ui.value.porEntregar.map { it.folio }).containsExactly("WK9P2")
        assertThat(vm.ui.value.toast).isEqualTo("W8M2C entregado a Leo")

        vm.listo(sofi)
        assertThat(vm.ui.value.porEntregar).isEmpty()
        coVerify { folios.entregado("v1", "m2c") }
        coVerify { folios.listo("v1", "k9p") }
        verify(atLeast = 2) { vigia.sondearYa() }
    }

    @Test
    fun `sin orders update no aparecen Listo ni Entregado`() = runTest {
        coEvery { permisos.hasPermission("orders:update") } returns false
        val vm = vm()
        assertThat(vm.ui.value.puedeEntregar).isFalse()
        assertThat(muestraListoYEntregado(vm.ui.value.puedeEntregar, sinConexion = false)).isFalse()
    }

    @Test
    fun `sin red Listo y Entregado vuelven con la conexion`() {
        assertThat(muestraListoYEntregado(puedeEntregar = true, sinConexion = true)).isFalse()
        assertThat(muestraListoYEntregado(puedeEntregar = true, sinConexion = false)).isTrue()
        assertThat(TextosPorPagar.ENTREGA_SIN_RED).isEqualTo("«Listo» y «Entregado» vuelven con la conexión")
    }

    @Test
    fun `Por entregar dice a que hora se pago`() {
        val leo = folio("m2c", falta = "0.00", pagadoEn = Instant.parse("2026-11-06T16:05:00Z"))
        assertThat(textoDePago(leo, mexico)).isEqualTo("Pagado a las 10:05")
        assertThat(textoDePago(leo.copy(pagadoEn = null), mexico)).isNull()
    }

    // endregion

    // region — buscar

    @Test
    fun `buscar filtra por folio sin la W y por nombre sin acentos`() = runTest {
        val ana = folio("a", folio = "W7K3P", nombre = "Ana")
        val sofia = folio("b", folio = "W4R9T", nombre = "Sofía")
        val leo = folio("c", folio = "W8M2C", nombre = "Leo", falta = "0.00")
        cargado(porPagar = listOf(ana, sofia), porEntregar = listOf(leo))
        val vm = vm()

        vm.buscar("7k3")
        assertThat(vm.ui.value.porPagar).containsExactly(ana)
        assertThat(vm.ui.value.porEntregar).isEmpty()

        vm.buscar("sofia")
        assertThat(vm.ui.value.porPagar).containsExactly(sofia)

        vm.buscar("w8m-2")
        assertThat(vm.ui.value.porEntregar).containsExactly(leo)

        // Con menos de 3 caracteres no filtra.
        vm.buscar("W7")
        assertThat(vm.ui.value.porPagar).containsExactly(ana, sofia).inOrder()
    }

    @Test
    fun `sin resultado y lista vacia dicen que hacer`() {
        assertThat(TextosPorPagar.sinResultados("W7K3P")).isEqualTo("No encontramos «W7K3P»")
        assertThat(TextosPorPagar.SIN_RESULTADOS_AYUDA)
            .isEqualTo("Si ya venció o ya se pagó, revisa «Por entregar». Si venció, cóbralo en «Cobrar» como venta normal.")
        assertThat(TextosPorPagar.VACIO).isEqualTo("No hay pedidos por pagar")
        assertThat(TextosPorPagar.VACIO_AYUDA).isEqualTo("Cuando alguien pida por el QR, aparece aquí con su folio y su nombre.")
        assertThat(TextosPorPagar.SIN_CONEXION_TITULO).isEqualTo("Pedidos por pagar · Sin conexión:")
        assertThat(TextosPorPagar.SIN_CONEXION_CUERPO).isEqualTo("los pedidos nuevos no se ven. Cobra en \"Cobrar\" como venta normal")
    }

    // endregion

    // region — cobrar: relee el folio

    @Test
    fun `cobrar un folio vencido no abre el cobro y dice que ya vencio`() = runTest {
        val ana = folio("a", venceEn = ahora.plusSeconds(60))
        cargado(porPagar = listOf(ana))
        // Al releer, el servidor ya lo venció: no está en ninguna lista y su hora pasó.
        coEvery { folios.listar("v1", false) } returns Result.success(pagina().copy(horaDelServidor = ahora.plusSeconds(120)))
        val vm = vm()

        assertThat(vm.cobrar(ana)).isNull()
        val aviso = vm.ui.value.aviso!!
        assertThat(aviso.titulo).isEqualTo("Este pedido ya venció")
        assertThat(aviso.cuerpo).isEqualTo("Cóbralo como venta normal en «Cobrar».")
    }

    @Test
    fun `cobrar uno que sigue en la lista pero ya paso su hora tampoco abre el cobro`() = runTest {
        val ana = folio("a", venceEn = ahora.minusSeconds(10))
        cargado(porPagar = listOf(ana))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(ana)))
        val vm = vm()

        assertThat(vm.cobrar(ana)).isNull()
        assertThat(vm.ui.value.aviso!!.titulo).isEqualTo("Este pedido ya venció")
    }

    @Test
    fun `cobrar un folio ya pagado no abre el cobro y dice cuando lo cobro otra caja`() = runTest {
        val ana = folio("a")
        cargado(porPagar = listOf(ana))
        val pagado = ana.copy(falta = BigDecimal("0.00"), estado = "ACCEPTED", pagadoEn = Instant.parse("2026-11-06T16:21:00Z"))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porEntregar = listOf(pagado)))
        val vm = vm()

        assertThat(vm.cobrar(ana)).isNull()
        val aviso = vm.ui.value.aviso!!
        assertThat(aviso.titulo).isEqualTo("Este folio ya se pagó")
        assertThat(aviso.cuerpo).isEqualTo("Lo cobró otra caja a las 10:21. Ya está en «Por entregar».")
        assertThat(aviso.boton).isEqualTo("Ver en «Por entregar»")
        assertThat(aviso.irAPorEntregar).isTrue()
    }

    @Test
    fun `cobrar abre con el saldo y no con el total`() = runTest {
        val sofia = folio("b", folio = "W4R9T", nombre = "Sofía", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(sofia)))
        val vm = vm()

        val apertura = vm.cobrar(sofia)!!
        assertThat(apertura.initialAmount).isEqualTo("85.00")
        assertThat(apertura.orderId).isEqualTo("b")
        assertThat(apertura.orderNumber).isEqualTo("W4R9T")
        // Nexgo no cobra en partes: el folio con abono se cobra por TODO lo que falta, como pago completo.
        assertThat(apertura.splitType).isEqualTo(SplitType.FULLPAYMENT.value)
        assertThat(apertura.folio.orderId).isEqualTo("b")
        assertThat(apertura.folio.orderNumber).isEqualTo("W4R9T")
        assertThat(apertura.folio.nombre).isEqualTo("Sofía")
        assertThat(apertura.folio.renglones.single().extras.single().nombre).isEqualTo("Leche de almendra")
        assertThat(vm.ui.value.aviso).isNull()
    }

    @Test
    fun `R78 con un cobro de ese folio en la cola del aparato no deja cobrar otra vez`() = runTest {
        // La TPV cobró el folio y su registro no ha subido: el SERVIDOR lo sigue viendo «por pagar» y la relectura dejaría
        // cobrarlo OTRA VEZ. Mientras la cola traiga ese cobro, no se abre ninguno.
        val sofia = folio("b", folio = "W4R9T", nombre = "Sofía", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { cola.hayCobroSubiendose("v1", "b") } returns true
        val vm = vm()
        var abrio = false

        val apertura = vm.cobrar(sofia) { abrio = true }

        assertThat(apertura).isNull()
        assertThat(abrio).isFalse()
        assertThat(vm.ui.value.aviso).isEqualTo(TextosPorPagar.COBRO_SUBIENDOSE)
        assertThat(vm.ui.value.aviso!!.titulo).isEqualTo("Hay un cobro de este folio subiéndose")
        assertThat(vm.ui.value.aviso!!.cuerpo).isEqualTo("Espera a que suba.")
        assertThat(vm.ui.value.ocupados).isEmpty()
        coVerify(exactly = 0) { folios.listar(any(), any()) }
    }

    @Test
    fun `R83 con un cobro de ese folio sin registrar y sin revisar en la terminal no deja cobrarlo otra vez`() = runTest {
        // Un 4xx permanente o los reintentos agotados dejan la fila FAILED: el dinero está en la caja y el SERVIDOR sigue viendo
        // el folio «por pagar». Hasta que una persona la revise, cobrarlo otra vez sería cobrarle dos veces a la clienta.
        val sofia = folio("b", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { cola.hayCobroSinRegistrar("v1", "b") } returns true
        val vm = vm()
        var abrio = false

        assertThat(vm.cobrar(sofia) { abrio = true }).isNull()

        assertThat(abrio).isFalse()
        assertThat(vm.ui.value.aviso).isEqualTo(TextosPorPagar.COBRO_SIN_REGISTRAR)
        assertThat(vm.ui.value.aviso!!.titulo).isEqualTo("Este folio tiene un cobro sin registrar en esta terminal")
        assertThat(vm.ui.value.aviso!!.cuerpo)
            .isEqualTo("Pídele a un gerente que lo revise en «Turnos de caja» › «Cerrar caja» antes de volver a cobrarlo.")
        coVerify(exactly = 0) { folios.listar(any(), any()) }
    }

    @Test
    fun `R83 un cobro sin registrar que ya se reviso deja abrir el folio`() = runTest {
        // La consulta sólo cuenta lo NO revisado (`acknowledged = 0`), igual que el cierre de caja: revisado ⇒ false.
        val sofia = folio("b", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(sofia)))
        val vm = vm()

        assertThat(vm.cobrar(sofia)).isNotNull()
        coVerify(exactly = 1) { cola.hayCobroSinRegistrar("v1", "b") }
    }

    @Test
    fun `R83 con un cobro en curso en esta terminal no abre otro`() = runTest {
        // La tarjeta no tiene fila en la cola mientras registra (sólo el efectivo se guarda antes del POST): la bandera del cobro
        // en curso es lo que la terminal sabe en esos segundos.
        val sofia = folio("b", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        cobroEnCurso.setCharging(true)
        val vm = vm()
        var abrio = false

        assertThat(vm.cobrar(sofia) { abrio = true }).isNull()

        assertThat(abrio).isFalse()
        assertThat(vm.ui.value.aviso).isEqualTo(TextosPorPagar.COBRO_EN_CURSO)
        assertThat(vm.ui.value.aviso!!.titulo).isEqualTo("Hay un cobro en curso en esta terminal")
        assertThat(vm.ui.value.aviso!!.cuerpo).isEqualTo("Espera a que termine.")
        coVerify(exactly = 0) { folios.listar(any(), any()) }
    }

    @Test
    fun `R89 con un cobro con tarjeta de ese folio sin registrar en la libreta no deja cobrarlo otra vez`() = runTest {
        // La cajera salió del éxito y el registro falló: no hay fila en la cola y la bandera ya bajó, pero la libreta (PAX y Nexgo)
        // sigue teniendo el cobro aprobado sin registrar hasta que la recuperación lo suba.
        val sofia = folio("b", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { libreta.hayDineroEnJuegoDeLaVenta("b") } returns true
        val vm = vm()
        var abrio = false

        assertThat(vm.cobrar(sofia) { abrio = true }).isNull()

        assertThat(abrio).isFalse()
        assertThat(vm.ui.value.aviso).isEqualTo(TextosPorPagar.COBRO_CON_TARJETA_SIN_REGISTRAR)
        // R91: honesto; en el aparato no hay camino para que un gerente lo «revise».
        assertThat(vm.ui.value.aviso!!.titulo).isEqualTo("La tarjeta de este folio ya se aprobó")
        assertThat(vm.ui.value.aviso!!.cuerpo).isEqualTo("La terminal lo está registrando. No lo cobres otra vez.")
        coVerify(exactly = 0) { folios.listar(any(), any()) }
    }

    @Test
    fun `R89 si no se puede leer la libreta tampoco abre el cobro`() = runTest {
        val sofia = folio("b", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { libreta.hayDineroEnJuegoDeLaVenta("b") } throws IllegalStateException("database is locked")
        val vm = vm()

        assertThat(vm.cobrar(sofia)).isNull()

        assertThat(vm.ui.value.aviso!!.titulo).isEqualTo("No se pudo revisar el folio")
        coVerify(exactly = 0) { folios.listar(any(), any()) }
    }

    @Test
    fun `R89 sin dinero sin confirmar en ninguna de las tres fuentes el folio abre`() = runTest {
        val sofia = folio("b", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(sofia)))
        val vm = vm()

        assertThat(vm.cobrar(sofia)).isNotNull()

        coVerify(exactly = 1) { cola.hayCobroSinRegistrar("v1", "b") }
        coVerify(exactly = 1) { cola.hayCobroSubiendose("v1", "b") }
        coVerify(exactly = 1) { libreta.hayDineroEnJuegoDeLaVenta("b") }
    }

    @Test
    fun `si no se puede leer la cola de la terminal no abre el cobro`() = runTest {
        // Falla cerrado: sin saber si ya hay un cobro de este folio en la terminal, no se arriesga uno segundo.
        val sofia = folio("b", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { cola.hayCobroSinRegistrar("v1", "b") } throws IllegalStateException("database is locked")
        val vm = vm()

        assertThat(vm.cobrar(sofia)).isNull()

        assertThat(vm.ui.value.aviso!!.titulo).isEqualTo("No se pudo revisar el folio")
        assertThat(vm.ui.value.ocupados).isEmpty()
        coVerify(exactly = 0) { folios.listar(any(), any()) }
    }

    @Test
    fun `R78 en cuanto el cobro sube, el folio ya se puede releer`() = runTest {
        val sofia = folio("b", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(sofia)))
        val vm = vm()

        assertThat(vm.cobrar(sofia)).isNotNull()
        coVerify(exactly = 1) { cola.hayCobroSubiendose("v1", "b") }
    }

    @Test
    fun `Cobrar sigue ocupado hasta que se abre el cobro (lector listo)`() = runTest {
        val sofia = folio("b", total = "185.00", falta = "85.00")
        cargado(porPagar = listOf(sofia))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(sofia)))
        val vm = vm()
        var ocupadoAlAbrir = false

        val apertura = vm.cobrar(sofia) { ocupadoAlAbrir = "b" in vm.ui.value.ocupados }

        assertThat(apertura!!.initialAmount).isEqualTo("85.00")
        assertThat(ocupadoAlAbrir).isTrue()
        assertThat(vm.ui.value.ocupados).isEmpty()
    }

    @Test
    fun `cada vez que la pantalla vuelve a verse pide la lista otra vez`() = runTest {
        val vm = vm()
        vm.pantallaVisible()
        vm.pantallaVisible()
        verify(exactly = 2) { vigia.sondearYa() }
    }

    @Test
    fun `el cobro de un folio dice de que folio es bajo el monto`() {
        assertThat(TextosPorPagar.folioDelCobro("W7K3P", "Ana")).isEqualTo("Folio W7K3P · Ana")
        assertThat(TextosPorPagar.folioDelCobro("W7K3P", null)).isEqualTo("Folio W7K3P")
    }

    @Test
    fun `si otra caja abono mientras estaba abierto, cobra lo que falta ahora`() = runTest {
        val ana = folio("a", total = "134.00")
        cargado(porPagar = listOf(ana))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(ana.copy(falta = BigDecimal("34.00")))))
        val vm = vm()

        assertThat(vm.cobrar(ana)!!.initialAmount).isEqualTo("34.00")
    }

    @Test
    fun `un folio que ya no esta y no ha vencido no se cobra`() = runTest {
        val ana = folio("a")
        cargado(porPagar = listOf(ana))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina())
        val vm = vm()

        assertThat(vm.cobrar(ana)).isNull()
        assertThat(vm.ui.value.aviso!!.titulo).isEqualTo("Este folio ya no está por pagar")
    }

    @Test
    fun `si no se puede releer, no abre el cobro`() = runTest {
        val ana = folio("a")
        cargado(porPagar = listOf(ana))
        coEvery { folios.listar("v1", false) } returns Result.failure(IOException("sin red"))
        val vm = vm()

        assertThat(vm.cobrar(ana)).isNull()
        assertThat(vm.ui.value.aviso!!.titulo).isEqualTo("No se pudo revisar el folio")
    }

    @Test
    fun `sin red no deja cobrar folios`() = runTest {
        val ana = folio("a")
        cargado(porPagar = listOf(ana), sinConexion = true)
        val vm = vm()

        assertThat(vm.cobrar(ana)).isNull()
        coVerify(exactly = 0) { folios.listar(any(), any()) }
    }

    // endregion

    // region — Cancelar folio

    @Test
    fun `Cancelar folio solo aparece con orders cancel-unpaid, sin dinero y con red`() = runTest {
        val ana = folio("a")
        val conAbono = folio("b", total = "185.00", falta = "85.00")
        assertThat(muestraCancelarFolio(ana, puedeCancelar = true, sinConexion = false)).isTrue()
        assertThat(muestraCancelarFolio(ana, puedeCancelar = false, sinConexion = false)).isFalse()
        assertThat(muestraCancelarFolio(conAbono, puedeCancelar = true, sinConexion = false)).isFalse()
        assertThat(muestraCancelarFolio(ana, puedeCancelar = true, sinConexion = true)).isFalse()

        coEvery { permisos.hasPermission("orders:cancel-unpaid") } returns false
        assertThat(vm().ui.value.puedeCancelar).isFalse()
    }

    @Test
    fun `Cancelar folio pregunta antes con el folio y el nombre`() {
        assertThat(TextosPorPagar.cancelarTitulo("W7K3P")).isEqualTo("¿Cancelar el folio W7K3P?")
        assertThat(TextosPorPagar.cancelarCuerpo("Ana"))
            .isEqualTo("Ana lo verá cancelado en su celular. Úsalo para un pedido falso o si ya no lo quiere.")
        // Sin «ella» (el mockup lo trae): el folio puede ser de Leo. Igual que ANDROID.
        assertThat(TextosPorPagar.NO).isEqualTo("No")
        assertThat(TextosPorPagar.SI_CANCELAR).isEqualTo("Sí, cancelar")
    }

    @Test
    fun `cancelar quita el folio de la lista y cierra su pantalla`() = runTest {
        val ana = folio("a")
        cargado(porPagar = listOf(ana))
        coEvery { folios.cancelar("v1", "a") } returns Result.success(Unit)
        val vm = vm()
        vm.abrir(ana)

        vm.cancelar(ana)
        assertThat(vm.ui.value.porPagar).isEmpty()
        assertThat(vm.ui.value.seleccionado).isNull()
        verify { vigia.sondearYa() }
    }

    @Test
    fun `cancelar con dinero muestra el texto de ORDER_HAS_PAYMENTS`() = runTest {
        val ana = folio("a")
        cargado(porPagar = listOf(ana))
        coEvery { folios.cancelar("v1", "a") } returns Result.failure(http("ORDER_HAS_PAYMENTS"))
        val vm = vm()
        vm.abrir(ana)

        vm.cancelar(ana)
        val aviso = vm.ui.value.aviso!!
        assertThat(aviso.titulo).isEqualTo("No se puede cancelar")
        assertThat(aviso.cuerpo).isEqualTo("Este folio ya tiene un cobro, o uno en curso en la terminal. Si ya se cobró, primero se reembolsa.")
        // El folio sigue: no se quita ni se cierra su pantalla.
        assertThat(vm.ui.value.porPagar).containsExactly(ana)
        assertThat(vm.ui.value.seleccionado).isEqualTo(ana)
    }

    // endregion

    // region — la pantalla y su tiempo

    @Test
    fun `la pantalla del folio no ofrece imprimir la comanda antes de cobrar`() {
        listOf("PorPagarScreen.kt", "PorPagarViewModel.kt").forEach { archivo ->
            val fuente = File("src/main/java/com/jaac/avoqado_tpv/features/onlineorders/presentation/$archivo").readText()
            // Ningún texto de la pantalla habla de imprimir (el campo `necesitaImprimir` del folio no es un texto).
            assertThat(Regex("\"[^\"\n]*[Ii]mprim[^\"\n]*\"").containsMatchIn(fuente)).isFalse()
            assertThat(fuente.contains("PrinterManager")).isFalse()
            assertThat(fuente.contains("printKitchenTicket")).isFalse()
        }
    }

    @Test
    fun `vence en N min, ambar con 5 o menos, hora fija sin red y vencido sin color`() {
        val en22 = folio("a", venceEn = ahora.plusSeconds(22 * 60))
        assertThat(vencimiento(en22, ahora, sinConexion = false, zona = mexico)).isEqualTo(Vencimiento("Vence en 22 min", pronto = false))
        val en3 = folio("b", venceEn = ahora.plusSeconds(2 * 60 + 5))
        assertThat(vencimiento(en3, ahora, sinConexion = false, zona = mexico)).isEqualTo(Vencimiento("Vence en 3 min", pronto = true))
        assertThat(vencimiento(en22, ahora, sinConexion = true, zona = mexico)).isEqualTo(Vencimiento("Vence a las 10:52", pronto = false))
        val vencido = folio("c", venceEn = ahora.minusSeconds(60))
        assertThat(vencimiento(vencido, ahora, sinConexion = false, zona = mexico)).isEqualTo(Vencimiento("Venció a las 10:29", pronto = false))
        // Con abono no vence solo: no se le pone reloj.
        assertThat(vencimiento(folio("d", total = "185.00", falta = "85.00"), ahora, false, mexico)).isNull()
    }

    @Test
    fun `sin red la lista dice de hace cuanto es`() {
        assertThat(deHace(actualizadoEnMs = 0L, ahoraMs = 2 * 60_000L + 5_000L)).isEqualTo("(de hace 2 min)")
        assertThat(deHace(actualizadoEnMs = 0L, ahoraMs = 20_000L)).isEqualTo("(de hace un momento)")
    }

    @Test
    fun `con abono dice cuanto falta de cuanto y cuanto ya pago`() {
        val sofia = folio("b", total = "185.00", falta = "85.00")
        assertThat(sofia.abono).isEqualTo(BigDecimal("100.00"))
        assertThat(TextosPorPagar.faltaDe("$85.00", "$185.00")).isEqualTo("Falta $85.00 de $185.00")
        assertThat(TextosPorPagar.yaPago("$100.00")).isEqualTo("Ya pagó $100.00")
    }

    // endregion

    // region — «Cargar más»

    @Test
    fun `con 101 por entregar, Cargar mas trae el 101`() = runTest {
        val cien = (1..100).map { folio("e$it", falta = "0.00", estado = "ACCEPTED") }
        estado.value = EstadoDeFolios(
            showTile = true, counts = ConteoDeFolios(0, 101), porEntregar = cien,
            nextCursors = mapOf(SeccionDeFolios.POR_ENTREGAR to "cursor-100"), actualizadoEnMs = 1_000L,
        )
        coEvery { vigia.cargarMas(SeccionDeFolios.POR_ENTREGAR) } coAnswers {
            estado.value = estado.value.copy(porEntregar = cien + folio("e101", falta = "0.00", estado = "ACCEPTED"), nextCursors = emptyMap())
            true
        }
        val vm = vm()
        assertThat(vm.ui.value.porEntregar).hasSize(100)

        vm.cargarMas(SeccionDeFolios.POR_ENTREGAR)
        assertThat(vm.ui.value.porEntregar).hasSize(101)
        assertThat(vm.ui.value.porEntregar.last().orderId).isEqualTo("e101")
    }

    // endregion

    // region — R92 (revisión de P5): I1, M2 y M3

    @Test
    fun `R92 I1 el encabezado es el numero del servidor aunque la lista cargada traiga mas`() = runTest {
        // 52 por pagar, la cajera cargó la página 2 (52 en pantalla) y otra caja cobró uno: el SERVIDOR ya dice 51.
        val cargados = (1..52).map { folio("p$it") }
        estado.value = EstadoDeFolios(
            showTile = true, counts = ConteoDeFolios(51, 0), porPagar = cargados, actualizadoEnMs = 1_000L,
        )
        val vm = vm()
        assertThat(vm.ui.value.numeroPorPagar).isEqualTo(51)

        // Con más páginas por traer tampoco se toma el mayor de los dos.
        estado.value = estado.value.copy(nextCursors = mapOf(SeccionDeFolios.POR_PAGAR to "c2"), actualizadoEnMs = 2_000L)
        assertThat(vm.ui.value.numeroPorPagar).isEqualTo(51)
    }

    @Test
    fun `R92 I1 lo que esta caja saco se resta del numero solo hasta que el servidor lo cuenta`() = runTest {
        val ana = folio("a")
        val beto = folio("b", nombre = "Beto")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(2, 0), porPagar = listOf(ana, beto), actualizadoEnMs = 1_000L)
        coEvery { folios.cancelar("v1", "b") } returns Result.success(Unit)
        val vm = vm()

        vm.cancelar(beto)
        assertThat(vm.ui.value.numeroPorPagar).isEqualTo(1) // misma lista: el servidor todavía lo cuenta

        // La vuelta siguiente ya lo trae descontado (1), aunque su página todavía no se relea: no se resta dos veces.
        estado.value = estado.value.copy(counts = ConteoDeFolios(1, 0), actualizadoEnMs = 2_000L)
        assertThat(vm.ui.value.numeroPorPagar).isEqualTo(1)
    }

    @Test
    fun `R92 M2 un folio entregado desde la pagina 2 no regresa con sus botones hasta que el servidor lo confirma`() = runTest {
        val leo = folio("m2c", folio = "W8M2C", nombre = "Leo", falta = "0.00", estado = "ACCEPTED")
        val sofi = folio("k9p", folio = "WK9P2", nombre = "Sofía", falta = "0.00", estado = "ACCEPTED")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(0, 2), porEntregar = listOf(leo, sofi), actualizadoEnMs = 1_000L)
        coEvery { folios.entregado("v1", "k9p") } returns Result.success(Unit)
        coEvery { folios.listo("v1", "m2c") } returns Result.success(Unit)
        val vm = vm()

        vm.entregado(sofi)
        vm.listo(leo)
        assertThat(vm.ui.value.porEntregar).isEmpty()

        // Otra vuelta (hora nueva) con su página todavía sin releer: los dos siguen ahí, viejos. No regresan.
        estado.value = estado.value.copy(counts = ConteoDeFolios(0, 0), actualizadoEnMs = 2_000L)
        assertThat(vm.ui.value.porEntregar).isEmpty()

        // La relectura confirma: Sofía ya no viene y Leo viene «Listo» ⇒ Leo se ve otra vez, con su estado nuevo.
        estado.value = estado.value.copy(porEntregar = listOf(leo.copy(estado = "READY")), actualizadoEnMs = 3_000L)
        assertThat(vm.ui.value.porEntregar.map { it.orderId to it.estado }).containsExactly("m2c" to "READY")
    }

    @Test
    fun `R92 M3 al cerrarse la pantalla el vigia suelta las paginas extra de ESTA lista`() = runTest {
        every { vigia.abrirLista() } returns 7L
        val vm = vm()
        PorPagarViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm)
        verify(exactly = 1) { vigia.soltarExtras(7L) }
    }

    // endregion

    // region — R94: lo escondido aquí sale en cuanto el SERVIDOR lo trae distinto (sección o estado)

    @Test
    fun `R94 cancelado aqui que revive por dinero tardio aparece en Por entregar con sus botones y en el numero`() = runTest {
        val ana = folio("a")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(1, 0), porPagar = listOf(ana), actualizadoEnMs = 1_000L)
        coEvery { folios.cancelar("v1", "a") } returns Result.success(Unit)
        val vm = vm()

        vm.cancelar(ana)
        assertThat(vm.ui.value.porPagar).isEmpty()

        // Llegó dinero de la cola de otro aparato: el SERVIDOR lo revive y lo manda en «Por entregar» como ACCEPTED.
        val revivido = ana.copy(estado = "ACCEPTED", falta = BigDecimal("0.00"), estadoDePago = "PAID")
        estado.value = estado.value.copy(counts = ConteoDeFolios(0, 1), porPagar = emptyList(), porEntregar = listOf(revivido), actualizadoEnMs = 2_000L)

        assertThat(vm.ui.value.porEntregar.map { it.orderId }).containsExactly("a")
        assertThat(vm.ui.value.numeroPorEntregar).isEqualTo(1)
        assertThat(muestraListoYEntregado(vm.ui.value.puedeEntregar, vm.ui.value.folios.sinConexion)).isTrue()
    }

    @Test
    fun `R94 una relectura vieja que lo trae igual que cuando se escondio lo deja escondido`() = runTest {
        val ana = folio("a")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(2, 0), porPagar = listOf(folio("b"), ana), actualizadoEnMs = 1_000L)
        coEvery { folios.cancelar("v1", "a") } returns Result.success(Unit)
        val vm = vm()
        vm.cancelar(ana)

        // Vuelta nueva cuya página extra todavía trae a «a» tal cual (misma sección, mismo estado).
        estado.value = estado.value.copy(counts = ConteoDeFolios(1, 0), actualizadoEnMs = 2_000L)
        assertThat(vm.ui.value.porPagar.map { it.orderId }).containsExactly("b")
        assertThat(vm.ui.value.numeroPorPagar).isEqualTo(1)
    }

    @Test
    fun `R94 cuando el folio sale de todas las listas se olvida lo escondido`() = runTest {
        val ana = folio("a")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(1, 0), porPagar = listOf(ana), actualizadoEnMs = 1_000L)
        coEvery { folios.cancelar("v1", "a") } returns Result.success(Unit)
        val vm = vm()
        vm.cancelar(ana)

        estado.value = estado.value.copy(counts = ConteoDeFolios(0, 0), porPagar = emptyList(), actualizadoEnMs = 2_000L) // el SERVIDOR lo confirmó
        // Ya no queda nada escondido: si el SERVIDOR lo vuelve a mandar (igual), se ve.
        estado.value = estado.value.copy(counts = ConteoDeFolios(1, 0), porPagar = listOf(ana), actualizadoEnMs = 3_000L)
        assertThat(vm.ui.value.porPagar.map { it.orderId }).containsExactly("a")
    }

    // endregion

    // region — R96: un abono tardío también lo regresa (misma sección y estado, otro saldo)

    @Test
    fun `R96 cancelado aqui que revive con un abono tardio regresa a Por pagar con Cobrar por lo que falta`() = runTest {
        val ana = folio("a", total = "134.00")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(1, 0), porPagar = listOf(ana), actualizadoEnMs = 1_000L)
        coEvery { folios.cancelar("v1", "a") } returns Result.success(Unit)
        val vm = vm()

        vm.cancelar(ana)
        assertThat(vm.ui.value.porPagar).isEmpty()

        // El efectivo encolado de otro aparato pagó una parte: el SERVIDOR lo revive en «Por pagar», PENDING_ACCEPTANCE, falta menos.
        val conAbono = ana.copy(falta = BigDecimal("50.00"), estadoDePago = "PARTIAL")
        estado.value = estado.value.copy(counts = ConteoDeFolios(1, 0), porPagar = listOf(conAbono), actualizadoEnMs = 2_000L)

        assertThat(vm.ui.value.porPagar.map { it.orderId to it.falta }).containsExactly("a" to BigDecimal("50.00"))
        assertThat(vm.ui.value.numeroPorPagar).isEqualTo(1)
        assertThat(vm.ui.value.folios.sinConexion).isFalse() // con red, la pantalla del folio ofrece «Cobrar»

        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(conAbono)))
        assertThat(vm.cobrar(conAbono)?.initialAmount).isEqualTo("50.00")
    }

    // endregion

    // region — R97: la huella es la de ANTES de la acción

    @Test
    fun `R97 si una vuelta trae el folio READY mientras viaja Listo, al responder se ve con Entregado`() = runTest {
        val leo = folio("m2c", folio = "W8M2C", nombre = "Leo", falta = "0.00", estado = "ACCEPTED")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(0, 1), porEntregar = listOf(leo), actualizadoEnMs = 1_000L)
        coEvery { folios.listo("v1", "m2c") } coAnswers {
            // El SERVIDOR ya lo procesó y una vuelta de la página 1 llega antes que la respuesta de «Listo».
            estado.value = estado.value.copy(porEntregar = listOf(leo.copy(estado = "READY")), actualizadoEnMs = 2_000L)
            Result.success(Unit)
        }
        val vm = vm()

        vm.listo(leo)

        assertThat(vm.ui.value.porEntregar.map { it.orderId to it.estado }).containsExactly("m2c" to "READY")
        assertThat(muestraListoYEntregado(vm.ui.value.puedeEntregar, vm.ui.value.folios.sinConexion)).isTrue()
    }

    // endregion

    // region — P6 (R98): «Por entregar» = pagado y sin entregar = ACCEPTED + READY

    @Test
    fun `P6 un folio READY del servidor lleva la marca Listo y solo ofrece Entregado`() = runTest {
        val leo = folio("m2c", folio = "W8M2C", nombre = "Leo", falta = "0.00", estado = "READY")
        val sofi = folio("k9p", folio = "WK9P2", nombre = "Sofía", falta = "0.00", estado = "ACCEPTED")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(0, 2), porEntregar = listOf(sofi, leo), actualizadoEnMs = 1_000L)
        val vm = vm()

        assertThat(vm.ui.value.porEntregar.map { it.orderId }).containsExactly("k9p", "m2c").inOrder()
        assertThat(vm.ui.value.numeroPorEntregar).isEqualTo(2) // el número del SERVIDOR ya cuenta los READY
        val ui = vm.ui.value
        // READY: marca «Listo», sólo «Entregado» (sin «Listo» ni «Cancelar»).
        assertThat(estaListo(leo)).isTrue()
        assertThat(muestraBotonListo(leo, ui.puedeEntregar, ui.folios.sinConexion)).isFalse()
        assertThat(muestraListoYEntregado(ui.puedeEntregar, ui.folios.sinConexion)).isTrue()
        assertThat(muestraCancelarFolio(leo, ui.puedeCancelar, ui.folios.sinConexion)).isFalse()
        // ACCEPTED: los dos botones, como hoy.
        assertThat(estaListo(sofi)).isFalse()
        assertThat(muestraBotonListo(sofi, ui.puedeEntregar, ui.folios.sinConexion)).isTrue()
        // La pantalla usa esas reglas en la tarjeta y en el folio.
        val fuente = File("src/main/java/com/jaac/avoqado_tpv/features/onlineorders/presentation/PorPagarScreen.kt").readText()
        assertThat(fuente).contains("if (muestraBotonListo(folio, ui.puedeEntregar, sinConexion))")
        assertThat(Regex("if \\(estaListo\\(folio\\)\\) MarcaListo\\(").findAll(fuente).count()).isEqualTo(2)
    }

    @Test
    fun `P6 Listo deja el folio visible como READY en Por entregar`() = runTest {
        val leo = folio("m2c", folio = "W8M2C", nombre = "Leo", falta = "0.00", estado = "ACCEPTED")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(0, 1), porEntregar = listOf(leo), actualizadoEnMs = 1_000L)
        coEvery { folios.listo("v1", "m2c") } returns Result.success(Unit)
        val vm = vm()

        vm.listo(leo)
        // La vuelta que sigue (el SERVIDOR nuevo) lo trae en «Por entregar» como READY: se ve, sólo con «Entregado».
        estado.value = estado.value.copy(porEntregar = listOf(leo.copy(estado = "READY")), actualizadoEnMs = 2_000L)

        val visto = vm.ui.value.porEntregar.single()
        assertThat(visto.estado).isEqualTo("READY")
        assertThat(muestraBotonListo(visto, vm.ui.value.puedeEntregar, false)).isFalse()
        assertThat(vm.ui.value.numeroPorEntregar).isEqualTo(1)
    }

    @Test
    fun `P6 Entregado en un folio READY lo saca de la lista`() = runTest {
        val leo = folio("m2c", folio = "W8M2C", nombre = "Leo", falta = "0.00", estado = "READY")
        estado.value = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(0, 1), porEntregar = listOf(leo), actualizadoEnMs = 1_000L)
        coEvery { folios.entregado("v1", "m2c") } returns Result.success(Unit)
        val vm = vm()

        vm.entregado(leo)
        assertThat(vm.ui.value.porEntregar).isEmpty()
        assertThat(vm.ui.value.numeroPorEntregar).isEqualTo(0)
        assertThat(vm.ui.value.toast).isEqualTo("W8M2C entregado a Leo")

        estado.value = estado.value.copy(counts = ConteoDeFolios(0, 0), porEntregar = emptyList(), actualizadoEnMs = 2_000L)
        assertThat(vm.ui.value.porEntregar).isEmpty()
    }

    // endregion

    // region — P2 (parte 2): saldo o tarjeta de regalo, y la cuenta de la clienta

    @Test
    fun `P2 Saldo o tarjeta de regalo solo con giftCards redeem`() = runTest {
        coEvery { permisos.hasPermission("giftCards:redeem") } returns false
        assertThat(vm().ui.value.puedeUsarSaldo).isFalse()

        coEvery { permisos.hasPermission("giftCards:redeem") } returns true
        assertThat(vm().ui.value.puedeUsarSaldo).isTrue()
    }

    @Test
    fun `P2 sin red el boton de saldo sale en gris con Necesita conexion`() {
        assertThat(botonDeSaldo(puedeUsarSaldo = true, sinConexion = true, pagado = false))
            .isEqualTo(BotonDeSaldo(activo = false, nota = "Necesita conexión"))
        assertThat(botonDeSaldo(puedeUsarSaldo = true, sinConexion = false, pagado = false)).isEqualTo(BotonDeSaldo(activo = true, nota = null))
        assertThat(botonDeSaldo(puedeUsarSaldo = false, sinConexion = false, pagado = false)).isNull()
        assertThat(botonDeSaldo(puedeUsarSaldo = true, sinConexion = false, pagado = true)).isNull()
        assertThat(TextosPorPagar.SALDO_O_TARJETA).isEqualTo("Saldo o tarjeta de regalo")
    }

    @Test
    fun `P2 reabrir con una solicitud pendiente comprueba el cobro con saldo antes de dejar cobrar`() = runTest {
        val ana = folio("a1")
        cargado(porPagar = listOf(ana))
        every { saldo.pendienteDe("a1") } returns mockk()
        val respuesta = CompletableDeferred<DesenlaceDeSaldo?>()
        coEvery { saldo.resolver("a1") } coAnswers { respuesta.await() }
        val vm = vm()

        vm.abrir(ana)
        assertThat(vm.ui.value.comprobandoSaldo).containsExactly("a1")
        assertThat(TextosPorPagar.COMPROBANDO_SALDO).isEqualTo("Comprobando el cobro con saldo…")
        var abrio = false
        assertThat(vm.cobrar(ana) { abrio = true }).isNull()
        assertThat(abrio).isFalse()

        respuesta.complete(DesenlaceDeSaldo.Incierto)

        assertThat(vm.ui.value.comprobandoSaldo).isEmpty()
        assertThat(vm.ui.value.saldoSinConfirmar).containsExactly("a1")
        assertThat(vm.ui.value.aviso).isEqualTo(TextosPorPagar.SALDO_SIN_CONFIRMAR)
        // «Cancelar folio» no se ofrece con un cobro con saldo sin confirmar.
        assertThat(muestraCancelarFolio(ana, puedeCancelar = true, sinConexion = false, saldoEnDuda = true)).isFalse()
        coVerify(exactly = 0) { folios.listar(any(), any()) }
    }

    @Test
    fun `P2 con el cobro con saldo en duda, Cobrar vuelve a comprobar y no abre ningun cobro`() = runTest {
        val ana = folio("a1")
        cargado(porPagar = listOf(ana))
        every { saldo.pendienteDe("a1") } returns mockk()
        coEvery { saldo.resolver("a1") } returns DesenlaceDeSaldo.Incierto
        val vm = vm()
        var abrio = false

        assertThat(vm.cobrar(ana) { abrio = true }).isNull()

        assertThat(abrio).isFalse()
        coVerify(exactly = 1) { saldo.resolver("a1") }
        assertThat(vm.ui.value.aviso).isEqualTo(TextosPorPagar.SALDO_SIN_CONFIRMAR)
        coVerify(exactly = 0) { folios.listar(any(), any()) }
    }

    @Test
    fun `P2 si al comprobar el saldo cubrio todo, confirma el folio una vez y lo manda a Por entregar`() = runTest {
        val ana = folio("a1", folio = "W7K3P")
        cargado(porPagar = listOf(ana))
        every { saldo.pendienteDe("a1") } returns mockk()
        coEvery { saldo.resolver("a1") } returns DesenlaceDeSaldo.Cobrado(0, 13400, "HB7T", 21600)
        val vm = vm()

        vm.abrir(ana)

        verify(exactly = 1) { despues.alConfirmarse(match { it.orderId == "a1" && it.orderNumber == "W7K3P" }) }
        assertThat(vm.ui.value.aviso).isEqualTo(TextosPorPagar.pagadoConSaldo("W7K3P"))
        assertThat(vm.ui.value.aviso!!.irAPorEntregar).isTrue()
        verify { vigia.sondearYa() }
    }

    @Test
    fun `P2 si al comprobar quedo saldo por cobrar, dice cuanto falta y no abre el cobro solo`() = runTest {
        val ana = folio("a1")
        cargado(porPagar = listOf(ana))
        every { saldo.pendienteDe("a1") } returns mockk()
        coEvery { saldo.resolver("a1") } returns DesenlaceDeSaldo.Cobrado(8400, 5000, "HB7T", 0)
        val vm = vm()
        var abrio = false

        assertThat(vm.cobrar(ana) { abrio = true }).isNull()

        assertThat(abrio).isFalse()
        verify(exactly = 0) { despues.alConfirmarse(any()) }
        assertThat(vm.ui.value.aviso).isEqualTo(TextosPorPagar.saldoConfirmado("$84.00"))
    }

    @Test
    fun `Codex r1 4 un abono con saldo recuperado al comprobar tambien sale bajo el monto al cobrar lo que falta`() = runTest {
        val ana = folio("a1", folio = "W7K3P", total = "134.00")
        cargado(porPagar = listOf(ana))
        every { saldo.pendienteDe("a1") } returns mockk()
        coEvery { saldo.resolver("a1") } returns DesenlaceDeSaldo.Cobrado(8400, 5000, "HB7T", 0)
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(ana.copy(falta = BigDecimal("84.00")))))
        val vm = vm()
        assertThat(vm.cobrar(ana)).isNull()
        every { saldo.pendienteDe("a1") } returns null

        val apertura = vm.cobrar(ana)!!

        assertThat(apertura.initialAmount).isEqualTo("84.00")
        assertThat(apertura.folio.pagadoConSaldo).isEqualTo(BigDecimal("50.00"))
    }

    @Test
    fun `Codex r5 F2 tras morir la app, el cobro de lo que falta dice cuanto ya pago segun el SERVIDOR`() = runTest {
        val ana = folio("a1", folio = "W7K3P", total = "134.00", falta = "84.00")
        cargado(porPagar = listOf(ana))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(ana)))
        // Un ViewModel nuevo: no sabe nada del saldo que se cobró antes de morir.
        val apertura = vm().cobrar(ana)!!

        assertThat(apertura.folio.pagadoConSaldo).isNull()
        assertThat(apertura.folio.yaPago).isEqualTo(BigDecimal("50.00"))
        assertThat(TextosPorPagar.folioDelCobro("W7K3P", "Ana", null, BigDecimal("50.00"))).isEqualTo("Folio W7K3P · Ana · ya pagó $50.00")
        assertThat(TextosPorPagar.folioDelCobro("W7K3P", "Ana", BigDecimal("50.00"), BigDecimal("50.00")))
            .isEqualTo("Folio W7K3P · Ana · ya pagó $50.00 con saldo")
        assertThat(TextosPorPagar.folioDelCobro("W7K3P", "Ana", BigDecimal("50.00"), BigDecimal("80.00")))
            .isEqualTo("Folio W7K3P · Ana · ya pagó $80.00 ($50.00 con saldo)")
    }

    @Test
    fun `P2 2e-3 lo que falta despues del saldo abre el cobro de siempre con lo que falta ahora`() = runTest {
        val ana = folio("a1", folio = "W7K3P", total = "134.00")
        cargado(porPagar = listOf(ana))
        coEvery { folios.listar("v1", false) } returns Result.success(pagina(porPagar = listOf(ana.copy(falta = BigDecimal("84.00")))))
        val vm = vm()
        vm.abrir(ana)
        var abierta: AperturaDeCobro? = null

        val apertura = vm.cobrarResto(5000) { abierta = it }!!

        assertThat(abierta).isEqualTo(apertura)
        assertThat(apertura.initialAmount).isEqualTo("84.00")
        assertThat(apertura.splitType).isEqualTo(SplitType.FULLPAYMENT.value)
        assertThat(apertura.folio.pagadoConSaldo).isEqualTo(BigDecimal("50.00"))
        assertThat(TextosPorPagar.folioDelCobro("W7K3P", "Ana", BigDecimal("50.00"))).isEqualTo("Folio W7K3P · Ana · ya pagó $50.00 con saldo")
    }

    @Test
    fun `P2 la tarjeta del folio dice Con cuenta y sus beneficios cuando vienen`() {
        val conCuenta = folio("a1").copy(cliente = "Ana", beneficios = listOf(BeneficioDeFolio("Premio", BigDecimal("45.00"))), beneficiosTotal = 1)
        assertThat(lineaDeLaCuenta(conCuenta)).isEqualTo("Con cuenta: Ana · Premio −$45.00")
        // Sin los campos (un SERVIDOR viejo o un folio sin cuenta): como hoy, nada.
        assertThat(lineaDeLaCuenta(folio("a2"))).isNull()
        // Nunca recorta en silencio.
        val conMas = conCuenta.copy(beneficiosTotal = 3)
        assertThat(lineaDeLaCuenta(conMas)).isEqualTo("Con cuenta: Ana · Premio −$45.00 · y 2 más")
    }

    // endregion
}
