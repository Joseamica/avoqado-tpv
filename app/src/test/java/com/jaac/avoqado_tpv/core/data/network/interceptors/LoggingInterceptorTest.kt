package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.google.common.truth.Truth.assertThat
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
}
