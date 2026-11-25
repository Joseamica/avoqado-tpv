package com.jaac.avoqado_tpv.core.di

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.ApiDelArranque
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Encargo 097e01 (21-nov-2026): con el SERVIDOR mudo, el logo de la TPV esperaba ~62 s (dos preguntas × 25 s) antes de
 * dejar vender. Las preguntas del logo van ahora por [ConsultasDelArranque.cliente]: tope corto y fila propia.
 *
 * Servidor «mudo» = acepta la conexión y nunca contesta (`SocketPolicy.NO_RESPONSE`), lo mismo que el SERVIDOR congelado
 * con SIGSTOP en la medición de la PAX.
 */
class ConsultasDelArranqueTest {

    private lateinit var servidor: MockWebServer

    /** Como el cliente general de NetworkModule: 25 s de tope total, 30 s por fase. */
    private val general = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val tope = ConsultasDelArranque.TOPE_S * 1_000

    @Before
    fun setUp() {
        servidor = MockWebServer()
        servidor.start()
    }

    @After
    fun tearDown() {
        general.dispatcher.cancelAll()
        servidor.shutdown()
    }

    private fun api(cliente: OkHttpClient): ApiDelArranque = Retrofit.Builder()
        .baseUrl(servidor.url("/api/v1/"))
        .client(cliente)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiDelArranque::class.java)

    private fun milisDe(bloque: () -> Unit): Long {
        val inicio = System.nanoTime()
        bloque()
        return (System.nanoTime() - inicio) / 1_000_000
    }

    @Test
    fun `P1 con el servidor mudo la pregunta de activacion se rinde en el tope corto, no en 25 s`() {
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val api = api(ConsultasDelArranque.cliente(general))

        var error: Throwable? = null
        val ms = milisDe {
            error = runCatching { runBlocking { api.checkActivationStatus("AVQD-1", "SAND") } }.exceptionOrNull()
        }

        // Falla como cualquier falta de red (IOException): el logo sigue su camino «sin red» de siempre.
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(ms).isAtLeast(tope - 500)
        assertThat(ms).isLessThan(tope + 2_000)
    }

    @Test
    fun `P1 con el servidor mudo la pregunta de modulos tambien se rinde en el tope corto`() {
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val api = api(ConsultasDelArranque.cliente(general))

        var error: Throwable? = null
        val ms = milisDe { error = runCatching { runBlocking { api.getModules("venue-1") } }.exceptionOrNull() }

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(ms).isLessThan(tope + 2_000)
    }

    @Test
    fun `P1 no hace fila detras de otras consultas colgadas al mismo servidor`() {
        // El arranque pide a la vez config, latido y cola de cobros; OkHttp deja 5 por servidor en su fila.
        repeat(6) { servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        repeat(5) {
            general.newCall(Request.Builder().url(servidor.url("/api/v1/tpv/heartbeat")).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
        while (general.dispatcher.runningCallsCount() < 5) Thread.sleep(10)

        val api = api(ConsultasDelArranque.cliente(general))
        var error: Throwable? = null
        val ms = milisDe { error = runCatching { runBlocking { api.checkActivationStatus("AVQD-1", "SAND") } }.exceptionOrNull() }

        // Con la fila del cliente general esperaría a que se liberara un lugar (25 s) antes de empezar su tope.
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(ms).isAtLeast(tope - 500) // se rindió por el tope, no por un fallo inmediato
        assertThat(ms).isLessThan(tope + 2_000)
        // y sí salió al servidor: no se quedó en la fila (Codex r5 #2)
        val rutas = List(6) { servidor.takeRequest(1, TimeUnit.SECONDS)?.path }
        assertThat(rutas).contains("/api/v1/tpv/terminals/AVQD-1/activation-status?environment=SAND")
    }

    @Test
    fun `P1 un servidor que contesta gota a gota tambien se rinde en el tope total, no por lectura`() {
        // Cada gota llega antes del tope de lectura (3 s): sólo el tope TOTAL (callTimeout) la corta (Codex r5 #2).
        servidor.enqueue(
            MockResponse().setBody("""{"modules":[]}""" + " ".repeat(40)).throttleBody(1, 500, TimeUnit.MILLISECONDS)
        )
        val api = api(ConsultasDelArranque.cliente(general))

        var error: Throwable? = null
        val ms = milisDe { error = runCatching { runBlocking { api.getModules("venue-1") } }.exceptionOrNull() }

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(ms).isLessThan(tope + 2_000)
    }

    @Test
    fun `con el servidor sano contesta igual que siempre`() {
        servidor.enqueue(
            MockResponse().setBody(
                """{"isActivated":true,"status":"ACTIVE","venueId":"venue-1","venueName":"Café","venueSlug":"cafe",""" +
                    """"activatedAt":"2026-11-01T00:00:00Z","message":null}"""
            )
        )
        val api = api(ConsultasDelArranque.cliente(general))

        val respuesta = runBlocking { api.checkActivationStatus("AVQD-1", "SAND") }

        assertThat(respuesta.isSuccessful).isTrue()
        assertThat(respuesta.body()!!.isActivated).isTrue()
        assertThat(servidor.takeRequest().path).isEqualTo("/api/v1/tpv/terminals/AVQD-1/activation-status?environment=SAND")
    }

    @Test
    fun `conserva la configuracion del cliente general (interceptores de sesion y version)`() {
        val conCabecera = general.newBuilder()
            .addInterceptor { it.proceed(it.request().newBuilder().header("X-App-Version-Code", "999").build()) }
            .build()
        servidor.enqueue(MockResponse().setBody("""{"modules":[]}"""))

        runBlocking { api(ConsultasDelArranque.cliente(conCabecera)).getModules("venue-1") }

        val pedido = servidor.takeRequest()
        assertThat(pedido.getHeader("X-App-Version-Code")).isEqualTo("999")
        assertThat(pedido.getHeader("X-Venue-Id")).isEqualTo("venue-1")
    }
}
