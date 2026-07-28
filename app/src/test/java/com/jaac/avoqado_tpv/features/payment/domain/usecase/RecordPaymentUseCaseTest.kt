package com.jaac.avoqado_tpv.features.payment.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import io.mockk.mockk
import org.junit.Test
import java.io.IOException

/**
 * Cubre [RecordPaymentUseCase.isRetryable] — la función que decide si el bucle de retry
 * (5 intentos, backoff exponencial) sigue intentando o se rinde.
 *
 * Task 8 / F-5: antes esto era `isRetriableError`, una cadena de ~18
 * `message.contains(...)` incluido `Regex("5\\d{2}")`. Ahora delega en
 * [com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure]. Estos tests
 * prueban que la delegación quedó bien cableada Y que sobrevive exactamente los casos que
 * rompían la versión vieja (dígitos engañosos dentro del mensaje, wording distinto,
 * idioma distinto, mensaje vacío).
 */
class RecordPaymentUseCaseTest {

    private val useCase = RecordPaymentUseCase(
        fastPaymentRecorder = mockk<FastPaymentRecorder>(relaxed = true),
        orderPaymentRecorder = mockk<OrderPaymentRecorder>(relaxed = true),
    )

    // --- Delegación correcta a classifySyncFailure -------------------------------------

    @Test
    fun `5xx es reintentable`() {
        assertThat(useCase.isRetryable(BackendHttpException(500, "Error del servidor (500)"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(503, "Service unavailable"))).isTrue()
    }

    @Test
    fun `400, 404 y 422 NO son reintentables`() {
        assertThat(useCase.isRetryable(BackendHttpException(400, "Bad request"))).isFalse()
        assertThat(useCase.isRetryable(BackendHttpException(404, "Venue no encontrado"))).isFalse()
        assertThat(useCase.isRetryable(BackendHttpException(422, "Unprocessable"))).isFalse()
    }

    @Test
    fun `409 (ya sincronizado) tampoco reintenta`() {
        // No es un error de negocio como 400/404/422, pero seguir reintentando cuando
        // el backend YA tiene el pago es ruido — classifySyncFailure lo marca Synced (no
        // Retryable), y esta función solo distingue retry-o-no.
        assertThat(useCase.isRetryable(BackendHttpException(409, "Duplicate payment"))).isFalse()
    }

    @Test
    fun `IOException de red es reintentable`() {
        assertThat(useCase.isRetryable(IOException("Unable to resolve host"))).isTrue()
    }

    @Test
    fun `null es reintentable (ante la duda, Retryable)`() {
        assertThat(useCase.isRetryable(null)).isTrue()
    }

    // --- Cambios de comportamiento DELIBERADOS vs. la version vieja --------------------
    // (ver KDoc de isRetryable / SyncOutcome — fix Critico de Task 1, NO revertir)

    @Test
    fun `401 ahora es reintentable (antes paraba el retry)`() {
        assertThat(useCase.isRetryable(BackendHttpException(401, "Unauthorized"))).isTrue()
    }

    @Test
    fun `403 ahora es reintentable (antes paraba el retry)`() {
        assertThat(useCase.isRetryable(BackendHttpException(403, "Forbidden"))).isTrue()
    }

    @Test
    fun `429 ahora es reintentable (antes paraba el retry)`() {
        // El propio retry loop implementa backoff exponencial - exactamente lo que pide
        // un 429 ("espera y reintenta", no "nunca").
        assertThat(useCase.isRetryable(BackendHttpException(429, "Demasiadas solicitudes"))).isTrue()
    }

    // --- El bug que esto reemplaza: digitos enganosos y wording -------------------------

    @Test
    fun `una referencia con 500 en un 409 NO se clasifica como servidor caido`() {
        // El Regex("5\\d{2}") viejo hacia match con "500" dentro de CUALQUIER texto.
        // Con classifySyncFailure, solo importa el codigo HTTP real (409 aqui -> Synced).
        val error = BackendHttpException(409, "Duplicate payment ref=000000500231")
        assertThat(useCase.isRetryable(error)).isFalse()
    }

    @Test
    fun `un monto que contiene 400 en un 503 SI se reintenta (clasifica por codigo, no texto)`() {
        val error = BackendHttpException(503, "Servicio no disponible al cobrar 400.00")
        assertThat(useCase.isRetryable(error)).isTrue()
    }

    @Test
    fun `wording distinto del mismo codigo no cambia la clasificacion`() {
        assertThat(useCase.isRetryable(BackendHttpException(503, "Servicio temporalmente no disponible"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(503, "Service temporarily unavailable"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(503, ""))).isTrue()
    }

    @Test
    fun `401 sin importar el idioma del mensaje sigue siendo reintentable`() {
        assertThat(useCase.isRetryable(BackendHttpException(401, "No autorizado"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(401, "Unauthorized"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(401, ""))).isTrue()
    }
}
