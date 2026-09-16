package com.jaac.avoqado_tpv.features.payments.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.payments.data.dto.ReceiptLinkDto
import com.jaac.avoqado_tpv.features.payments.data.dto.ReceiptLinkResponse
import com.jaac.avoqado_tpv.features.payments.domain.models.ResultadoLigaRecibo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * La liga del recibo para el QR de la REIMPRESIÓN, desde el TPV.
 *
 * 🔴 Origen del caso del tope (QA de hardware, 12-sep-2026): el plan prometía que la liga se pide
 * con un tope de 5 s, para que un servidor lento nunca retrase el papel. Android e iOS lo tenían;
 * aquí NO — la llamada usaba el cliente compartido, cuyo `callTimeout` es de 25 s. Con red mala, la
 * PAX habría esperado hasta 25 segundos antes de imprimir, con el cliente enfrente.
 *
 * Y los tres desenlaces, porque un 500 no es «sin conexión»: decirle al cajero que no hay red cuando
 * el servidor sí respondió lo manda a revisar el WiFi por un problema que no está ahí.
 *
 * ⚠️ Reloj REAL (`runBlocking`), no `runTest`, a propósito: el repositorio corre la llamada en
 * `Dispatchers.IO`, y con el reloj virtual de `runTest` el tope de 5 s se adelanta en cuanto el hilo
 * de prueba queda libre — disparaba antes de que una respuesta instantánea terminara, y los cuatro
 * casos normales salían «sin red». Con reloj real los casos normales tardan milisegundos y el del
 * tope mide los 5 s de verdad.
 */
class PaymentRepositoryReceiptLinkTest {

    private lateinit var apiService: ApiService
    private lateinit var repo: PaymentRepositoryImpl

    private val url = "https://dashboardv2.avoqado.io/receipts/public/llave-abc"

    @Before
    fun setup() {
        apiService = mockk()
        repo = PaymentRepositoryImpl(apiService)
    }

    private fun ok(receiptUrl: String?, autofactura: Boolean = true) = Response.success(
        ReceiptLinkResponse(success = true, receipt = ReceiptLinkDto("llave-abc", receiptUrl, autofactura)),
    )

    @Test
    fun `con la liga devuelve Obtenida con la bandera de autofactura`() = runBlocking {
        coEvery { apiService.getReceiptLink("venue_1", "pmt_1") } returns ok(url, autofactura = true)

        val r = repo.getReceiptLink("venue_1", "pmt_1")

        assertThat(r).isEqualTo(ResultadoLigaRecibo.Obtenida(url, autofacturaAvailable = true))
    }

    @Test
    fun `P1 sin conexion es SinRed`() = runBlocking {
        coEvery { apiService.getReceiptLink(any(), any()) } throws IOException("sin ruta")

        assertThat(repo.getReceiptLink("venue_1", "pmt_1")).isEqualTo(ResultadoLigaRecibo.SinRed)
    }

    @Test
    fun `P1 un 500 es fallo del servidor, NO sin conexion`() = runBlocking {
        coEvery { apiService.getReceiptLink(any(), any()) } returns
            Response.error(500, "boom".toResponseBody())

        assertThat(repo.getReceiptLink("venue_1", "pmt_1")).isEqualTo(ResultadoLigaRecibo.FalloDelServidor(500))
    }

    @Test
    fun `P1 un 200 con la liga vacia NO es sin conexion`() = runBlocking {
        // El servidor SÍ respondió: culparle a la red sería mentir.
        coEvery { apiService.getReceiptLink(any(), any()) } returns ok("")

        assertThat(repo.getReceiptLink("venue_1", "pmt_1")).isInstanceOf(ResultadoLigaRecibo.FalloDelServidor::class.java)
    }

    @Test
    fun `P1 un servidor que tarda mas de 5 s NO retrasa el papel`() = runBlocking {
        // Tarda 10 s en contestar. Sin tope, la prueba devolvería la liga (Obtenida) tras esperar.
        coEvery { apiService.getReceiptLink(any(), any()) } coAnswers {
            delay(10_000)
            ok(url)
        }

        val inicio = System.nanoTime()
        val r = repo.getReceiptLink("venue_1", "pmt_1")
        val tardoMs = (System.nanoTime() - inicio) / 1_000_000

        assertThat(r).isEqualTo(ResultadoLigaRecibo.SinRed)
        // Se rindió al llegar al tope, no esperó los 10 s del servidor.
        assertThat(tardoMs).isAtLeast(PaymentRepositoryImpl.TOPE_LIGA_RECIBO_MS - 200)
        assertThat(tardoMs).isLessThan(9_000)
    }
}
