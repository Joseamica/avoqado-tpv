package com.jaac.avoqado_tpv.features.checkout.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * D36 (encargo da7275): «atrás» antes de cobrar en «Cobrar» retira la venta del SERVIDOR sólo si esta terminal no tiene NINGÚN rastro
 * de un intento de cobro, y anota el retiro ANTES de salir a la red para reintentarlo sin red.
 */
class RetiroDeVentaSinCobroTest {

    private class LibroEnMemoria(var escribe: Boolean = true) : LibroDeRetiros {
        val filas = linkedMapOf<String, RetiroPendiente>()
        override fun anotar(pendiente: RetiroPendiente): Boolean {
            if (escribe) filas[pendiente.orderId] = pendiente
            return escribe
        }
        override fun quitar(orderId: String) { filas.remove(orderId) }
        override fun pendientes(): List<RetiroPendiente> = filas.values.sortedBy { it.anotadoEn }
    }

    private var ahora = 1_700_000_000_000L
    private val libro = LibroEnMemoria()
    private val enviados = mutableListOf<String>()
    private var rastro: (String) -> Boolean = { false }
    private var respuesta: () -> Response<Unit> = { Response.success(Unit) }
    /** Lo que había en el libro justo cuando salió la petición: prueba que se anota ANTES de la red. */
    private val anotadoAlEnviar = mutableListOf<Boolean>()

    private val core = RetiroDeVentaSinCobroCore(
        hayRastroDeCobro = { rastro(it) },
        libro = libro,
        enviar = { _, orderId ->
            anotadoAlEnviar += libro.filas.containsKey(orderId)
            enviados += orderId
            respuesta()
        },
        reloj = { ahora },
    )

    private fun error(code: Int): Response<Unit> = Response.error(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

    @Test
    fun `P1 sin rastro de cobro se anota ANTES de la red, se pide al servidor y se borra al retirarse`() = runTest {
        assertThat(core.retirarSiNuncaSeCobro("venue-1", "order-1")).isEqualTo(DesenlaceDelRetiro.RETIRADA)

        assertThat(enviados).containsExactly("order-1")
        assertThat(anotadoAlEnviar).containsExactly(true)
        assertThat(libro.filas).isEmpty()
    }

    @Test
    fun `P1 con rastro de cobro en la terminal (libreta, cola o cripto) no se pide nada`() = runTest {
        rastro = { true }

        assertThat(core.retirarSiNuncaSeCobro("venue-1", "order-1")).isEqualTo(DesenlaceDelRetiro.CON_RASTRO_DE_COBRO)

        assertThat(enviados).isEmpty()
        assertThat(libro.filas).isEmpty()
    }

    @Test
    fun `P1 si no se puede leer la libreta falla cerrado - no se pide nada`() = runTest {
        rastro = { throw IllegalStateException("Room cerrada") }

        assertThat(core.retirarSiNuncaSeCobro("venue-1", "order-1")).isEqualTo(DesenlaceDelRetiro.SIN_CERTEZA)
        assertThat(enviados).isEmpty()
    }

    @Test
    fun `si no se pudo anotar el retiro no se sale a la red`() = runTest {
        libro.escribe = false

        assertThat(core.retirarSiNuncaSeCobro("venue-1", "order-1")).isEqualTo(DesenlaceDelRetiro.SIN_CERTEZA)
        assertThat(enviados).isEmpty()
    }

    @Test
    fun `P1 sin red queda anotado y sale al reintentar cuando vuelve la red`() = runTest {
        respuesta = { throw IOException("sin red") }
        assertThat(core.retirarSiNuncaSeCobro("venue-1", "order-1")).isEqualTo(DesenlaceDelRetiro.PENDIENTE)
        assertThat(libro.filas.keys).containsExactly("order-1")

        respuesta = { Response.success(Unit) }
        ahora += 60_000
        core.reintentarPendientes()

        assertThat(enviados).containsExactly("order-1", "order-1")
        assertThat(libro.filas).isEmpty()
    }

    @Test
    fun `P1 al reintentar se vuelve a mirar la libreta - si ya hay rastro se olvida sin pedir`() = runTest {
        respuesta = { throw IOException("sin red") }
        core.retirarSiNuncaSeCobro("venue-1", "order-1")

        rastro = { true }
        core.reintentarPendientes()

        assertThat(enviados).containsExactly("order-1")
        assertThat(libro.filas).isEmpty()
    }

    @Test
    fun `un rechazo del servidor (409) es definitivo - se olvida y la venta queda como estaba`() = runTest {
        respuesta = { error(409) }

        assertThat(core.retirarSiNuncaSeCobro("venue-1", "order-1")).isEqualTo(DesenlaceDelRetiro.RECHAZADA)
        assertThat(libro.filas).isEmpty()
    }

    @Test
    fun `un error de paso (500, 401) se queda para reintentar`() = runTest {
        respuesta = { error(503) }
        assertThat(core.retirarSiNuncaSeCobro("venue-1", "order-1")).isEqualTo(DesenlaceDelRetiro.PENDIENTE)
        respuesta = { error(401) }
        assertThat(core.retirarSiNuncaSeCobro("venue-1", "order-2")).isEqualTo(DesenlaceDelRetiro.PENDIENTE)

        assertThat(libro.filas.keys).containsExactly("order-1", "order-2")
    }

    @Test
    fun `pasadas 24 h el retiro anotado se olvida sin pedir`() = runTest {
        respuesta = { throw IOException("sin red") }
        core.retirarSiNuncaSeCobro("venue-1", "order-1")

        ahora += RetiroDeVentaSinCobroCore.PLAZO_MS + 1
        respuesta = { Response.success(Unit) }
        core.reintentarPendientes()

        assertThat(enviados).containsExactly("order-1")
        assertThat(libro.filas).isEmpty()
    }

    @Test
    fun `P1 un envio lento no frena que el SIGUIENTE retiro quede anotado en el aparato (Codex r2 #6)`() = runTest {
        val sigue = CompletableDeferred<Unit>()
        val nucleo = RetiroDeVentaSinCobroCore(
            hayRastroDeCobro = { false },
            libro = libro,
            enviar = { _, orderId ->
                if (orderId == "order-lenta") sigue.await()
                Response.success(Unit)
            },
            reloj = { ahora },
        )
        val lento = launch { nucleo.retirarSiNuncaSeCobro("venue-1", "order-lenta") }
        yield()
        // Mientras la primera sigue en la red, la segunda ya quedó escrita (si la app muere aquí, se reintenta al volver).
        val segunda = async { nucleo.retirarSiNuncaSeCobro("venue-1", "order-2") }
        yield()
        assertThat(libro.filas.keys).contains("order-2")

        sigue.complete(Unit)
        lento.join()
        assertThat(segunda.await()).isEqualTo(DesenlaceDelRetiro.RETIRADA)
        assertThat(libro.filas).isEmpty()
    }

    @Test
    fun `el fragmento es el mismo con el que la libreta busca la venta`() {
        assertThat(RetiroDeVentaSinCobroCore.fragmentoDeLaVenta("cabc")).isEqualTo("\"orderId\":\"cabc\"")
    }
}
