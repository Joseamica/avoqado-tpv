package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.features.ordering.data.api.OrderApiService
import com.jaac.avoqado_tpv.features.ordering.domain.TpvCreateOrderWithItemsItem
import com.jaac.avoqado_tpv.features.ordering.domain.TpvCreateOrderWithItemsRequest
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal

/**
 * «Cobrar» sin red, C2a (Codex plan-r1 #2): los fallos de `createOrderWithItems` salen TIPADOS, por el repositorio real y un servidor
 * de mentiras (no inyectando el error): 503 ⇒ `HttpError(503)` que cuenta como «el servidor no contestó»; socket cortado ⇒
 * `NetworkError`. El TEXTO que ve el cajero no cambia: viaja en `customUserMessage` (`userMessage`).
 */
class OrderRepositoryCreateOrderFallosTest {

    private lateinit var servidor: MockWebServer
    private lateinit var repo: OrderRepositoryImpl

    @Before fun preparar() {
        servidor = MockWebServer().also { it.start() }
        val api = Retrofit.Builder().baseUrl(servidor.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(OrderApiService::class.java)
        repo = OrderRepositoryImpl(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun apagar() { servidor.shutdown() }

    private val pedido = TpvCreateOrderWithItemsRequest(
        items = listOf(TpvCreateOrderWithItemsItem(productId = "p-1", quantity = 1)),
        staffId = "staff-1", subtotal = BigDecimal("50.00"), total = BigDecimal("50.00"), externalId = "llave-1",
    )

    private fun falla(): Throwable? = runBlocking { repo.createOrderWithItems("venue-1", pedido) }.exceptionOrNull()

    @Test fun `un 503 es HttpError 503 y servidorNoContesto, con el texto de hoy`() {
        servidor.enqueue(MockResponse().setResponseCode(503))

        val e = falla() as ApiException.HttpError

        assertThat(e.code).isEqualTo(503)
        assertThat(e.servidorNoContesto).isTrue()
        assertThat(e.userMessage).isEqualTo("Error al crear orden: 503")
    }

    @Test fun `un socket cortado tras la peticion es NetworkError y servidorNoContesto`() {
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val e = falla()

        assertThat(e).isInstanceOf(ApiException.NetworkError::class.java)
        assertThat((e as ApiException).servidorNoContesto).isTrue()
    }

    @Test fun `el servidor apagado es NetworkError`() {
        servidor.shutdown()

        assertThat(falla()).isInstanceOf(ApiException.NetworkError::class.java)
    }

    @Test fun `los 4xx conservan el texto de hoy y no cuentan como servidor que no contesto`() {
        val esperados = mapOf(
            400 to "No se pudo crear la orden. Verifica descuentos, cortesías o totales.",
            401 to "No autorizado. Por favor inicia sesión nuevamente.",
            404 to "Producto, descuento o staff no encontrado.",
            422 to "Error al crear orden: 422",
            500 to "Error al crear orden: 500",
        )
        for ((codigo, texto) in esperados) {
            servidor.enqueue(MockResponse().setResponseCode(codigo))
            val e = falla() as ApiException.HttpError
            assertThat(e.code).isEqualTo(codigo)
            assertThat(e.userMessage).isEqualTo(texto)
            assertThat(e.servidorNoContesto).isFalse()
        }
    }

    @Test fun `un 403 sale como PermisoRequeridoException con el texto de hoy y el permiso`() {
        servidor.enqueue(MockResponse().setResponseCode(403).setBody("""{"required":"orders:comp","overridable":true}"""))
        val e = falla() as com.jaac.avoqado_tpv.core.data.network.PermisoRequeridoException
        assertThat(e.required).isEqualTo("orders:comp")
        assertThat(e.overridable).isTrue()
        servidor.enqueue(MockResponse().setResponseCode(403))
        assertThat(falla()!!.message).isEqualTo("No tienes permiso para crear órdenes.")
    }

    @Test fun `D1 la autorizacion del descuento viaja en el cuerpo y la cortesia en el encabezado`() {
        servidor.enqueue(MockResponse().setResponseCode(503))
        val conDescuento = pedido.copy(
            discount = BigDecimal("5.00"), orderDiscountId = "d-10", total = BigDecimal("45.00"), autorizacionDelDescuento = "tok-d",
        )
        runBlocking { repo.createOrderWithItems("venue-1", conDescuento, overrideToken = "tok-c") }

        val llegada = servidor.takeRequest()
        val cuerpo = com.google.gson.JsonParser.parseString(llegada.body.readUtf8()).asJsonObject
        assertThat(cuerpo["autorizacionDelDescuento"].asString).isEqualTo("tok-d")
        assertThat(cuerpo["orderDiscountId"].asString).isEqualTo("d-10")
        assertThat(cuerpo["discount"].asDouble).isEqualTo(5.0)
        assertThat(llegada.getHeader("X-Permission-Override")).isEqualTo("tok-c")

        // Sin autorización, el cuerpo es el de siempre: la clave ni aparece.
        servidor.enqueue(MockResponse().setResponseCode(503))
        falla()
        assertThat(servidor.takeRequest().body.readUtf8()).doesNotContain("autorizacionDelDescuento")
    }

    private val conDescuentoDeCatalogo = pedido.copy(discount = BigDecimal("5.00"), orderDiscountId = "d-10", total = BigDecimal("45.00"))
    private val GENERICO_400 = "No se pudo crear la orden. Verifica descuentos, cortesías o totales."

    private fun fallaCon(peticion: TpvCreateOrderWithItemsRequest, mensaje: String): ApiException.HttpError {
        servidor.enqueue(MockResponse().setResponseCode(400).setBody(com.google.gson.JsonObject().apply { addProperty("message", mensaje) }.toString()))
        return runBlocking { repo.createOrderWithItems("venue-1", peticion) }.exceptionOrNull() as ApiException.HttpError
    }

    @Test fun `D1 un 400 porque el descuento del cliente cambio deja el aviso del servidor tal cual`() {
        val aviso = "El descuento «Cliente 10 %» cambió desde que se puso en la cuenta. Vuelve a elegir al cliente para actualizarlo."
        assertThat(fallaCon(conDescuentoDeCatalogo, aviso).userMessage).isEqualTo(aviso)
    }

    @Test fun `D1 cualquier otro 400 con descuento de catalogo sigue con el texto de siempre`() {
        assertThat(fallaCon(conDescuentoDeCatalogo, "Discount \"X\" requires a minimum purchase amount").userMessage).isEqualTo(GENERICO_400)
        assertThat(fallaCon(conDescuentoDeCatalogo, "Order total cannot be negative").userMessage).isEqualTo(GENERICO_400)
        // Sin descuento de catálogo, ni el texto del descuento pasa: el 400 es de otra cosa.
        assertThat(fallaCon(pedido, "El descuento «X» cambió desde que se puso en la cuenta.").userMessage).isEqualTo(GENERICO_400)
    }

    @Test fun `un 400 con cupon conserva la razon del servidor`() {
        servidor.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Este cupón ya venció."}"""))
        val e = runBlocking { repo.createOrderWithItems("venue-1", pedido.copy(couponCode = "VERANO")) }.exceptionOrNull() as ApiException.HttpError
        assertThat(e.userMessage).isEqualTo("Este cupón ya venció.")
    }

    @Test fun `una respuesta 200 sin cuerpo valido sigue siendo el error de hoy`() {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false,"data":null}"""))

        val e = falla()

        assertThat(e).isNotNull()
        assertThat(e).isNotInstanceOf(ApiException::class.java)
        assertThat(e!!.message).isEqualTo("Invalid response from server")
    }
}
