package com.jaac.avoqado_tpv.features.payment.domain.sync

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SyncOutcomeTest {

    @Test
    fun `un 409 NO se considera sincronizado — se reintenta`() {
        // 🔴 Regresión: este test afirmaba lo contrario y blindaba el bug.
        // El reintento idempotente real del server responde 200 con el pago existente,
        // nunca 409; los 409 de ese endpoint significan "no lo registré, vuelve a
        // intentar". Cerrarlo como SUCCESS dejaba de reintentar y borraba la fila a los
        // 7 días: venta perdida con la tarjeta ya cobrada.
        val outcome = classifySyncFailure(BackendHttpException(409, "La cuenta cambió mientras se cobraba"))
        assertThat(outcome).isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `un reference number que contiene 409 NO se considera sincronizado`() {
        // 🔴 El bug: "000000409231".contains("409") == true
        val outcome = classifySyncFailure(
            BackendHttpException(500, "Error registrando ref=000000409231"),
        )
        assertThat(outcome).isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `un monto que contiene 400 NO se marca como permanente`() {
        val outcome = classifySyncFailure(
            BackendHttpException(503, "Servicio no disponible al cobrar 400.00"),
        )
        assertThat(outcome).isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `4xx de negocio real (400, 404, 422) es permanente`() {
        assertThat(classifySyncFailure(BackendHttpException(400, "Bad request")))
            .isInstanceOf(SyncOutcome.Permanent::class.java)
        assertThat(classifySyncFailure(BackendHttpException(404, "Order not found")))
            .isInstanceOf(SyncOutcome.Permanent::class.java)
        assertThat(classifySyncFailure(BackendHttpException(422, "Unprocessable entity")))
            .isInstanceOf(SyncOutcome.Permanent::class.java)
    }

    // --- Fix round 1 (hallazgo Crítico de revisión) ---------------------------------
    // 401/403 casi siempre son la SESIÓN, no el pago: un refresh de token que se atora
    // tras Doze (TokenAuthenticator.kt) devuelve el 401 ORIGINAL sin reintentar — "el
    // token puede seguir siendo válido". Antes de este fix, classifySyncFailure() los
    // marcaba Permanent: la PRIMERA vez que un refresh se atoraba justo al reconectar,
    // un pago YA COBRADO quedaba FAILED+permanent para siempre — nada en el código
    // limpia `permanent` salvo un tap manual del operador, y no hay ninguna señal que
    // lo invite a hacerlo. "Reintentar ahora no ayuda" es cierto para 401/403; "nunca
    // reintentable jamás" no lo es para ninguno de los dos.

    @Test
    fun `401 Unauthorized es reintentable, NUNCA permanente`() {
        assertThat(classifySyncFailure(BackendHttpException(401, "Unauthorized")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `403 Forbidden es reintentable, NUNCA permanente`() {
        assertThat(classifySyncFailure(BackendHttpException(403, "Forbidden")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `5xx es reintentable`() {
        assertThat(classifySyncFailure(BackendHttpException(502, "Bad gateway")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `429 rate limit es reintentable, no permanente`() {
        // El propio worker provoca esto: 10 pagos encolados pegandole al backend
        // en cuanto vuelve la red es la rafaga de thundering herd de manual.
        assertThat(classifySyncFailure(BackendHttpException(429, "Too Many Requests")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `408 request timeout es reintentable, no permanente`() {
        assertThat(classifySyncFailure(BackendHttpException(408, "Request Timeout")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `errores de red son reintentables`() {
        assertThat(classifySyncFailure(SocketTimeoutException("timeout")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
        assertThat(classifySyncFailure(IOException("Unable to resolve host")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `un error desconocido es reintentable, nunca sincronizado`() {
        // Regla de seguridad: ante la duda NUNCA marcar como sincronizado.
        // Perder un reintento es barato; perder una venta no.
        assertThat(classifySyncFailure(IllegalStateException("???")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
        assertThat(classifySyncFailure(null))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    // --- Task 8 / F-5 — clasificar por código en los 4 sitios restantes -------------
    // classifySyncFailure ya es correcto (fix de Task 1); estos dos tests documentan
    // el contrato que RecordPaymentUseCase, TerminalConfigRepositoryImpl y HomeViewModel
    // ahora heredan al delegar en él: la clasificación no debe depender del wording.

    @Test
    fun `isRetryable no se rompe si el server cambia el texto del mensaje`() {
        // Antes: message.contains("Error del servidor") / Regex("5\\d{2}").
        // Si el server traduce o reescribe el mensaje, la clasificacion se rompia
        // EN SILENCIO. Ahora solo importa el codigo.
        val conTextoNuevo = BackendHttpException(503, "Servicio temporalmente no disponible")
        assertThat(classifySyncFailure(conTextoNuevo)).isInstanceOf(SyncOutcome.Retryable::class.java)

        val enIngles = BackendHttpException(503, "Service temporarily unavailable")
        assertThat(classifySyncFailure(enIngles)).isInstanceOf(SyncOutcome.Retryable::class.java)

        val vacio = BackendHttpException(503, "")
        assertThat(classifySyncFailure(vacio)).isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    @Test
    fun `un 401 sigue siendo reintentable sin importar el idioma`() {
        // NOTA: el brief original de este test decía "permanente" — pero classifySyncFailure
        // marca 401 como Retryable a propósito (fix Crítico de Task 1: 401/403 casi siempre
        // son la SESIÓN, no el pago; ver KDoc de PERMANENT_HTTP_CODES arriba). El punto real
        // de este test es el mismo: la clasificación de 401 no debe depender del idioma.
        assertThat(classifySyncFailure(BackendHttpException(401, "No autorizado")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
        assertThat(classifySyncFailure(BackendHttpException(401, "")))
            .isInstanceOf(SyncOutcome.Retryable::class.java)
    }

    // ── Encargo 29ef3d: un intento en el que NADIE contestó no gasta un intento de la cola ──

    @Test
    fun `plazo vencido o no poder conectar cuenta como nadie contesto`() {
        assertThat(nadieContesto(SocketTimeoutException("timeout"))).isTrue()
        assertThat(nadieContesto(java.io.InterruptedIOException("timeout"))).isTrue() // callTimeout de OkHttp
        assertThat(nadieContesto(java.net.ConnectException("Failed to connect"))).isTrue()
        assertThat(nadieContesto(java.net.UnknownHostException("api"))).isTrue()
        assertThat(nadieContesto(java.net.NoRouteToHostException("no route"))).isTrue()
    }

    @Test
    fun `P1 cualquier respuesta HTTP cuenta, tambien 502 503 504 (los registradores tiran el code de los 5xx)`() {
        // Codex r1: el SERVIDOR contesta 503 propios (PAYMENT_REGISTRATION_UNRESOLVED_*) y el registrador no conserva el code.
        assertThat(nadieContesto(BackendHttpException(502, "Bad Gateway"))).isFalse()
        assertThat(nadieContesto(BackendHttpException(503, "Service Unavailable"))).isFalse()
        assertThat(nadieContesto(BackendHttpException(504, "Gateway Timeout"))).isFalse()
        assertThat(nadieContesto(BackendHttpException(500, "Internal Server Error"))).isFalse()
        assertThat(nadieContesto(BackendHttpException(429, "Too Many Requests"))).isFalse()
        assertThat(nadieContesto(BackendHttpException(401, "Unauthorized"))).isFalse()
    }

    @Test
    fun `P1 otra IOException puede llegar despues de una respuesta y si cuenta`() {
        // Codex r1: un 200 con JSON cortado (EOF), un cuerpo de 500 cortado, una conexión que se cae a media respuesta.
        assertThat(nadieContesto(java.io.EOFException("End of input"))).isFalse()
        assertThat(nadieContesto(IOException("unexpected end of stream"))).isFalse()
        assertThat(nadieContesto(java.net.SocketException("Connection reset"))).isFalse()
        assertThat(nadieContesto(IllegalStateException("boom"))).isFalse()
        assertThat(nadieContesto(null)).isFalse()
    }

    @Test
    fun `P1 si un intento anterior del mismo registro tuvo respuesta, el plazo final si cuenta`() {
        val plazo = SocketTimeoutException("timeout")
        plazo.addSuppressed(BackendHttpException(500, "Internal Server Error"))
        assertThat(nadieContesto(plazo)).isFalse()

        val soloPlazos = SocketTimeoutException("timeout")
        soloPlazos.addSuppressed(java.net.ConnectException("Failed to connect"))
        assertThat(nadieContesto(soloPlazos)).isTrue()
    }

    @Test
    fun `nadie contesto no cambia la clasificacion - sigue siendo reintentable`() {
        assertThat(classifySyncFailure(SocketTimeoutException("timeout"))).isInstanceOf(SyncOutcome.Retryable::class.java)
        assertThat(classifySyncFailure(java.net.ConnectException("x"))).isInstanceOf(SyncOutcome.Retryable::class.java)
    }
}
