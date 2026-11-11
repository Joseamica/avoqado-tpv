package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.google.common.truth.Truth.assertThat
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * Task 7 · fix 2 (P1-1): el `@PaymentClient` hereda el `LoggingInterceptor` (Level.BODY en DEBUG → Timber), así que el cuerpo
 * de `resolveNoInstrument` —con el `supervisorPin`— quedaba en logcat de un build DEBUG (el instalado en la N86 de QA). El PIN
 * nunca se persiste ni se loguea: se censura en la RAÍZ, por línea, antes de Timber. JUnit puro sobre la función pura.
 */
class LoggingInterceptorTest {

    @Test fun `censura el valor de supervisorPin y deja el resto del cuerpo intacto`() {
        val linea = """{"requestId":"r","resolutionId":"x","supervisorPin":"1234","statement":"NO_INSTRUMENT_PRESENTED","statementVersion":1}"""
        assertThat(LoggingInterceptor.censurarCuerpoSensible(linea))
            .isEqualTo("""{"requestId":"r","resolutionId":"x","supervisorPin":"***","statement":"NO_INSTRUMENT_PRESENTED","statementVersion":1}""")
    }

    @Test fun `censura pin, password, newPin y currentPin — como cadena o como numero, con o sin espacios`() {
        assertThat(LoggingInterceptor.censurarCuerpoSensible("""{"pin": 5678}""")).isEqualTo("""{"pin":"***"}""")
        assertThat(LoggingInterceptor.censurarCuerpoSensible("""{"password" : "s3cr3t", "user":"ana"}""")).isEqualTo("""{"password":"***", "user":"ana"}""")
        assertThat(LoggingInterceptor.censurarCuerpoSensible("""{"currentPin":"1111","newPin":"2222"}""")).isEqualTo("""{"currentPin":"***","newPin":"***"}""")
        assertThat(LoggingInterceptor.censurarCuerpoSensible("""{"supervisorPin":"12\"34"}""")).isEqualTo("""{"supervisorPin":"***"}""")   // comillas escapadas dentro del valor
    }

    @Test fun `una clave que solo CONTIENE pin o una linea que no es JSON no se tocan`() {
        val pinned = """{"pinned":true,"shippingAddress":"Av. Pino 1","spinCount":3}"""
        assertThat(LoggingInterceptor.censurarCuerpoSensible(pinned)).isEqualTo(pinned)
        val cabecera = "--> POST https://api.avoqado.io/api/v1/tpv/venues/v1/terminal-payment/attempts/a1/no-instrument-resolution"
        assertThat(LoggingInterceptor.censurarCuerpoSensible(cabecera)).isEqualTo(cabecera)
        assertThat(LoggingInterceptor.censurarCuerpoSensible("")).isEqualTo("")
    }
    @Test fun `Codex r1 2 el codigo y la liga de una tarjeta de regalo no salen en el log, el codigo de error si`() {
        val pago = """{"idempotencyKey":"k1","proof":{"kind":"CODE","code":"K7Q29DXM4R8HHB7TR"},"amount":50.00,"tip":0}"""
        assertThat(LoggingInterceptor.censurarCuerpoSensible(pago))
            .isEqualTo("""{"idempotencyKey":"k1","proof":{"kind":"CODE","code":"***"},"amount":50.00,"tip":0}""")
        assertThat(LoggingInterceptor.censurarCuerpoSensible("""{"code":"K7Q2-9DXM-4R8H-HB7T-R"}""")).isEqualTo("""{"code":"***"}""")
        assertThat(LoggingInterceptor.censurarCuerpoSensible("""{"pageToken":"AbCdEfGhIjKlMnOpQrStUvWxYz012345"}""")).isEqualTo("""{"pageToken":"***"}""")
        val error = """{"success":false,"message":"No encontramos esa tarjeta","code":"GIFT_CARD_NOT_FOUND"}"""
        assertThat(LoggingInterceptor.censurarCuerpoSensible(error)).isEqualTo(error)
    }

    @Test fun `device command credentials are redacted from activation responses`() {
        val body = """{"commandToken":"never-log-this","venueId":"v1"}"""
        assertThat(LoggingInterceptor.censurarCuerpoSensible(body)).isEqualTo("""{"commandToken":"***","venueId":"v1"}""")
    }

    @Test fun `X-Permission-Override sale censurado en el log`() {
        val lineas = mutableListOf<String>()
        val arbol = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { lineas.add(message) }
        }
        timber.log.Timber.plant(arbol)
        try {
            val cliente = okhttp3.OkHttpClient.Builder()
                .addInterceptor(LoggingInterceptor.create())
                .addInterceptor { chain ->
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                        .body("{}".toResponseBody(null)).build()
                }.build()
            cliente.newCall(okhttp3.Request.Builder().url("https://api.avoqado.io/x").header("X-Permission-Override", "tok-secreto").build()).execute().close()
        } finally { timber.log.Timber.uproot(arbol) }
        val todo = lineas.joinToString("\n")
        assertThat(todo).doesNotContain("tok-secreto")
        assertThat(todo).contains("X-Permission-Override: ██")
    }
}
