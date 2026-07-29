package com.jaac.avoqado_tpv.features.tables.data.sync

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * `classifyTablesSyncFailure` — clasificador PROPIO de Mesas, deliberadamente
 * distinto de `classifySyncFailure` (Pagos, Plan A). El defecto real que
 * motiva este archivo: `TablesRepository` reusaba el clasificador de Pagos,
 * cuyo sesgo permisivo (401/403/405/5xx/desconocido → Retryable) convertía un
 * rechazo de auth, un 500 real, o un bug de programación propio en un
 * "guardado offline" silencioso — el mesero ve la ronda como enviada y nunca
 * existió. Mesas no tiene nada irreversible que proteger (a diferencia de un
 * cobro ya hecho), así que el sesgo aquí es el opuesto: ante la duda, PROPAGA.
 */
class TablesSyncOutcomeTest {

    // region — encola: infraestructura transitoria, nunca "el server dijo que no"

    @Test
    fun `502 503 y 504 son reintentables`() {
        assertThat(classifyTablesSyncFailure(BackendHttpException(502, "Bad gateway")))
            .isInstanceOf(TablesSyncOutcome.Retryable::class.java)
        assertThat(classifyTablesSyncFailure(BackendHttpException(503, "Service unavailable")))
            .isInstanceOf(TablesSyncOutcome.Retryable::class.java)
        assertThat(classifyTablesSyncFailure(BackendHttpException(504, "Gateway timeout")))
            .isInstanceOf(TablesSyncOutcome.Retryable::class.java)
    }

    @Test
    fun `408 request timeout es reintentable`() {
        assertThat(classifyTablesSyncFailure(BackendHttpException(408, "Request Timeout")))
            .isInstanceOf(TablesSyncOutcome.Retryable::class.java)
    }

    @Test
    fun `429 rate limit es reintentable`() {
        // Nuestra propia ráfaga de intents encolados al volver la red es la que
        // provoca el 429 — mismo razonamiento que Plan A.
        assertThat(classifyTablesSyncFailure(BackendHttpException(429, "Too Many Requests")))
            .isInstanceOf(TablesSyncOutcome.Retryable::class.java)
    }

    @Test
    fun `errores de red son reintentables`() {
        assertThat(classifyTablesSyncFailure(SocketTimeoutException("timeout")))
            .isInstanceOf(TablesSyncOutcome.Retryable::class.java)
        assertThat(classifyTablesSyncFailure(IOException("Unable to resolve host")))
            .isInstanceOf(TablesSyncOutcome.Retryable::class.java)
    }

    // endregion

    // region — propaga: el server rechazó, o el origen del fallo no se conoce

    @Test
    fun `401 y 403 se propagan, NUNCA se encolan`() {
        // 🔴 Lo opuesto a Pagos: ahí 401/403 son Retryable A PROPÓSITO (casi
        // siempre es la sesión). En Mesas nada irreversible pasó todavía, así que
        // el usuario DEBE ver el rechazo de auth/permiso.
        assertThat(classifyTablesSyncFailure(BackendHttpException(401, "Unauthorized")))
            .isInstanceOf(TablesSyncOutcome.Propagate::class.java)
        assertThat(classifyTablesSyncFailure(BackendHttpException(403, "Forbidden")))
            .isInstanceOf(TablesSyncOutcome.Propagate::class.java)
    }

    @Test
    fun `4xx de negocio (400, 404, 405, 409, 422) se propagan`() {
        for (code in listOf(400, 404, 405, 409, 422)) {
            assertThat(classifyTablesSyncFailure(BackendHttpException(code, "rechazo de negocio")))
                .isInstanceOf(TablesSyncOutcome.Propagate::class.java)
        }
    }

    @Test
    fun `500 se propaga, NUNCA se encola`() {
        // 🔴 El defecto real: el clasificador de Pagos mete 500 en su `else`
        // Retryable junto con "cualquier otro". Un 500 puede ser un bug real del
        // server — el mesero viendo el error y reintentando a mano es más seguro
        // que un encolado silencioso.
        assertThat(classifyTablesSyncFailure(BackendHttpException(500, "Internal Server Error")))
            .isInstanceOf(TablesSyncOutcome.Propagate::class.java)
    }

    @Test
    fun `un throwable desconocido se propaga, NUNCA se encola`() {
        // 🔴 EL CASO PRINCIPAL: un bug de programación propio (NPE, ISE, lo que
        // sea) no debe disfrazarse de "guardado offline". El clasificador de Pagos
        // cae a Retryable en su `else` para "desconocido y null" — la regla
        // opuesta es la correcta para Mesas.
        assertThat(classifyTablesSyncFailure(NullPointerException("bug propio")))
            .isInstanceOf(TablesSyncOutcome.Propagate::class.java)
        assertThat(classifyTablesSyncFailure(IllegalStateException("???")))
            .isInstanceOf(TablesSyncOutcome.Propagate::class.java)
        assertThat(classifyTablesSyncFailure(null))
            .isInstanceOf(TablesSyncOutcome.Propagate::class.java)
    }

    // endregion

    // region — no depende del texto del mensaje, solo del código

    @Test
    fun `la clasificacion no se rompe si el server cambia el texto del mensaje`() {
        // Un reference number como "000000409231" contiene "409" — nunca
        // clasificar por texto. Mismo espiritu que el test gemelo de Plan A.
        val conReferenceEngañoso = BackendHttpException(503, "Error registrando ref=000000409231")
        assertThat(classifyTablesSyncFailure(conReferenceEngañoso))
            .isInstanceOf(TablesSyncOutcome.Retryable::class.java)

        val montoEngañoso = BackendHttpException(500, "Fallo al guardar total de 502.00")
        assertThat(classifyTablesSyncFailure(montoEngañoso))
            .isInstanceOf(TablesSyncOutcome.Propagate::class.java)
    }

    // endregion
}
