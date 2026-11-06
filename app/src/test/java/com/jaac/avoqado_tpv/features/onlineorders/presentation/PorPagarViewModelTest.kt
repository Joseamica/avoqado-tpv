package com.jaac.avoqado_tpv.features.onlineorders.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.features.onlineorders.data.FoliosRepository
import com.jaac.avoqado_tpv.features.onlineorders.data.VigiaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.ConteoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.EstadoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioModificador
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioProducto
import com.jaac.avoqado_tpv.features.onlineorders.domain.PaginaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    private fun vm() = PorPagarViewModel(vigia, folios, permisos, storage)

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
}
