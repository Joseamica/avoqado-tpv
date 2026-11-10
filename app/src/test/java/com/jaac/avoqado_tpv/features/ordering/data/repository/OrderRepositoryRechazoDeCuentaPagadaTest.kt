package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.ordering.data.api.OrderApiService
import com.jaac.avoqado_tpv.features.ordering.domain.PermanentSyncException
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * D20 (`hz:c8157a64:D20`): con un 400, quitar, anular, dar cortesía o poner un descuento enseñan el motivo del SERVIDOR si ya
 * viene en español (D18: «Esta cuenta ya está pagada… usa Reembolso.»). Con el SERVIDOR de antes de D18 (inglés), un cuerpo
 * vacío o uno que no es suyo, la cajera sigue viendo el texto fijo de hoy. Repositorio real contra un servidor de mentiras.
 */
class OrderRepositoryRechazoDeCuentaPagadaTest {

    private lateinit var servidor: MockWebServer
    private lateinit var repo: OrderRepositoryImpl

    @Before fun preparar() {
        servidor = MockWebServer().also { it.start() }
        val api = Retrofit.Builder().baseUrl(servidor.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(OrderApiService::class.java)
        repo = OrderRepositoryImpl(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun apagar() { servidor.shutdown() }

    private fun rechazo(cuerpo: String?) {
        val r = MockResponse().setResponseCode(400)
        if (cuerpo != null) r.setBody(cuerpo)
        servidor.enqueue(r)
    }

    private fun json(mensaje: String) = """{"message":"$mensaje"}"""

    private fun quitar() = runBlocking { repo.removeOrderItem("v-1", "o-1", "i-1", 3) }.exceptionOrNull()
    private fun cortesia() = runBlocking { repo.compItems("v-1", "o-1", listOf("i-1"), "Se tardó", "s-1", null) }.exceptionOrNull()
    private fun anular() = runBlocking { repo.voidItems("v-1", "o-1", listOf("i-1"), "Error", "s-1", 3) }.exceptionOrNull()
    private fun descuento() = runBlocking { repo.applyDiscount("v-1", "o-1", "PERCENTAGE", 10.0, "s-1", null, null, 3) }.exceptionOrNull()

    private val FIJO_PAGADA = "La orden ya está pagada y no puede modificarse."
    private val FIJO_DESCUENTO = "Descuento inválido. Verifica el valor ingresado."

    // ── Con D18: el texto del SERVIDOR ──────────────────────────────────────────────────────────

    @Test fun `quitar con la cuenta pagada enseña el texto de D18 y sigue siendo falla permanente`() {
        val d18 = "Esta cuenta ya está pagada y ya no se le pueden quitar artículos. Si hay que devolver dinero, usa Reembolso."
        rechazo(json(d18))
        val e = quitar() as PermanentSyncException
        assertThat(e.httpCode).isEqualTo(400)
        assertThat(e.message).isEqualTo(d18)
    }

    @Test fun `cortesia con la cuenta pagada enseña el texto de D18`() {
        val d18 = "Esta cuenta ya está pagada y ya no admite cortesías. Si hay que devolver dinero, usa Reembolso."
        rechazo(json(d18))
        assertThat(cortesia()!!.message).isEqualTo(d18)
    }

    @Test fun `anular con la cuenta pagada enseña el texto de D18`() {
        val d18 = "Esta cuenta ya está pagada y ya no se pueden anular artículos. Si hay que devolver dinero, usa Reembolso."
        rechazo(json(d18))
        assertThat(anular()!!.message).isEqualTo(d18)
    }

    @Test fun `descuento con la cuenta pagada enseña el texto de D18`() {
        val d18 = "Esta cuenta ya está pagada y ya no admite descuentos. Si cobraste de más, reembolsa la diferencia."
        rechazo(json(d18))
        assertThat(descuento()!!.message).isEqualTo(d18)
    }

    @Test fun `los otros 400 en español del SERVIDOR también se enseñan`() {
        val porDebajo = "Anular estos artículos dejaría la cuenta en \$50.00 y ya se cobraron \$80.00. Reembolsa primero la diferencia o anula menos artículos."
        rechazo(json(porDebajo))
        assertThat(anular()!!.message).isEqualTo(porDebajo)

        val validacion = "Error de validación: reason: Requerido"
        rechazo(json(validacion))
        assertThat(cortesia()!!.message).isEqualTo(validacion)

        val importada = "Esta cuenta viene de SoftRestaurant y su total lo manda ese sistema: quita, anula, agrega, separa, junta, regala o descuenta artículos allá. Aquí sólo se cobra."
        rechazo(json(importada))
        assertThat(descuento()!!.message).isEqualTo(importada)
        rechazo(json(importada))
        assertThat(quitar()!!.message).isEqualTo(importada)
    }

    // ── SERVIDOR de antes de D18 (producción hoy) y respuestas sin mensaje: el texto fijo ────────

    @Test fun `con el SERVIDOR de hoy, en inglés, la cajera sigue viendo el texto fijo en español`() {
        rechazo(json("Cannot remove items from a paid order"))
        val e = quitar() as PermanentSyncException
        assertThat(e.httpCode).isEqualTo(400)
        assertThat(e.message).isEqualTo(FIJO_PAGADA)

        rechazo(json("Cannot comp items from a paid order"))
        assertThat(cortesia()!!.message).isEqualTo(FIJO_PAGADA)

        rechazo(json("Cannot void items from a paid order"))
        assertThat(anular()!!.message).isEqualTo(FIJO_PAGADA)

        rechazo(json("Cannot apply discount to a paid order"))
        assertThat(descuento()!!.message).isEqualTo(FIJO_DESCUENTO)
    }

    @Test fun `los demás 400 en inglés de estas rutas tampoco se enseñan`() {
        rechazo(json("No items found to comp"))
        assertThat(cortesia()!!.message).isEqualTo(FIJO_PAGADA)

        rechazo(json("No items found to void"))
        assertThat(anular()!!.message).isEqualTo(FIJO_PAGADA)
        rechazo(json("Some item IDs were not found in this order"))
        assertThat(anular()!!.message).isEqualTo(FIJO_PAGADA)

        rechazo(json("Order was modified by another request. Please refresh and try again. (Expected version: 3, Current: 4)"))
        assertThat(descuento()!!.message).isEqualTo(FIJO_DESCUENTO)
        rechazo(json("Percentage discount must be between 0 and 100"))
        assertThat(descuento()!!.message).isEqualTo(FIJO_DESCUENTO)
        rechazo(json("Fixed discount amount cannot be negative"))
        assertThat(descuento()!!.message).isEqualTo(FIJO_DESCUENTO)
        rechazo(json("No items found to apply discount"))
        assertThat(descuento()!!.message).isEqualTo(FIJO_DESCUENTO)
    }

    @Test fun `un cuerpo vacío, HTML de un intermediario o un mensaje en blanco dejan el texto fijo`() {
        for (cuerpo in listOf(null, "<html>502 Bad Gateway</html>", """{"message":"   "}""", """{"error":"x"}""")) {
            rechazo(cuerpo)
            assertThat(quitar()!!.message).isEqualTo(FIJO_PAGADA)
            rechazo(cuerpo)
            assertThat(cortesia()!!.message).isEqualTo(FIJO_PAGADA)
            rechazo(cuerpo)
            assertThat(anular()!!.message).isEqualTo(FIJO_PAGADA)
            rechazo(cuerpo)
            assertThat(descuento()!!.message).isEqualTo(FIJO_DESCUENTO)
        }
    }

    @Test fun `los demás códigos no cambian`() {
        servidor.enqueue(MockResponse().setResponseCode(404).setBody(json("Esta cuenta no existe")))
        assertThat(anular()!!.message).isEqualTo("Orden no encontrada.")
        servidor.enqueue(MockResponse().setResponseCode(500).setBody(json("Algo en español")))
        assertThat(cortesia()!!.message).isEqualTo("Error del servidor. Por favor intenta nuevamente.")
    }
}
