package com.jaac.avoqado_tpv.features.verification.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.NumberCheckResponse
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.verification.data.remote.ProofOfSaleRemote
import com.jaac.avoqado_tpv.features.verification.presentation.components.LINKING_NUMBER_LOCKED_TEXT
import com.jaac.avoqado_tpv.features.verification.presentation.components.canConfirmLinkingNumber
import com.jaac.avoqado_tpv.features.verification.presentation.components.isValidLinkingNumber
import com.jaac.avoqado_tpv.features.verification.presentation.components.linkingNumberInput
import com.jaac.avoqado_tpv.features.verification.presentation.components.submitLinkingNumber
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * El campo del número en la vista previa de la foto (C7) y la pregunta de repetido. La pantalla de Compose no tiene
 * pruebas de JVM en este repo (ver `AngelPayPaymentScreenSalidaTest`): aquí se prueban las funciones que usa el campo
 * y el controlador real con el `ProofOfSaleRemote` REAL sobre una `ApiService` de mentira, así el tope de 3 s es el de
 * producción medido con el reloj virtual.
 */
class NumberCaptureControllerTest {
    private val api = mockk<ApiService>()
    private val controller = NumberCaptureController(ProofOfSaleRemote(api))

    private fun servidorContesta(duplicate: Boolean) {
        coEvery { api.checkLinkingNumber(any(), any(), any()) } returns Response.success(NumberCheckResponse(duplicate = duplicate))
    }

    // ── El campo ───────────────────────────────────────────────────────────────

    @Test fun `el campo filtra lo que no es digito y lo pegado con mas de 10 no entra`() {
        assertThat(linkingNumberInput("", "55 1234-5678")).isEqualTo("5512345678")
        assertThat(linkingNumberInput("", "55a12b345")).isEqualTo("5512345")
        // Review Focus 1: «+52 55 1234 5678» son 12 dígitos: no entra y se queda lo que había.
        assertThat(linkingNumberInput("551", "+52 55 1234 5678")).isEqualTo("551")
        // El dígito 11 no se acepta.
        assertThat(linkingNumberInput("5512345678", "55123456789")).isEqualTo("5512345678")
    }

    @Test fun `Confirmar se habilita solo con 9 o 10 digitos`() {
        assertThat(listOf("", "55123456", "551234567", "5512345678", "55123456789").map { canConfirmLinkingNumber(it, locked = false) })
            .containsExactly(false, false, true, true, false).inOrder()
        assertThat(isValidLinkingNumber("55 1234567")).isFalse()
    }

    @Test fun `con el numero bloqueado no se edita, se confirma con el del back-office y lo dice`() {
        assertThat(linkingNumberInput("5512345678", "5599999999", locked = true)).isEqualTo("5512345678")
        assertThat(linkingNumberInput("5512345678", "", locked = true)).isEqualTo("5512345678")
        assertThat(canConfirmLinkingNumber("", locked = true)).isTrue()
        assertThat(LINKING_NUMBER_LOCKED_TEXT).isEqualTo("Corregido por el back-office")
    }

    @Test fun `cada ranura pide su numero con su etiqueta`() {
        assertThat(ProofSlot.VINCULACION.numberKind).isEqualTo(NumberKind.LINKING)
        assertThat(ProofSlot.PORTABILIDAD.numberKind).isEqualTo(NumberKind.PORTED)
        assertThat(NumberKind.LINKING.label).isEqualTo("Número de vinculación")
        assertThat(NumberKind.PORTED.label).isEqualTo("Número portado")
    }

    // ── La pregunta de repetido ────────────────────────────────────────────────

    @Test fun `con red y repetido pide Corregir o Guardar asi con el texto de Isaac, y Corregir regresa al campo`() = runTest {
        servidorContesta(duplicate = true)

        val respuesta = async { controller.confirm("5512345678", NumberKind.LINKING, "ver-1") }
        runCurrent()

        assertThat(controller.prompt.value).isEqualTo(DuplicatePrompt("Este número de vinculación ya fue registrado en otra venta"))
        assertThat(respuesta.isCompleted).isFalse()
        controller.correct()
        assertThat(respuesta.await()).isFalse()
        assertThat(controller.prompt.value).isNull()
        coVerify(exactly = 1) { api.checkLinkingNumber("5512345678", "linking", "ver-1") }
    }

    @Test fun `Guardar asi sigue, y el portado trae su propio texto`() = runTest {
        servidorContesta(duplicate = true)

        val respuesta = async { controller.confirm("5598765432", NumberKind.PORTED, null) }
        runCurrent()

        assertThat(controller.prompt.value?.text).isEqualTo("Este número portado ya fue registrado en otra venta")
        controller.saveAnyway()
        assertThat(respuesta.await()).isTrue()
        assertThat(controller.prompt.value).isNull()
        coVerify(exactly = 1) { api.checkLinkingNumber("5598765432", "ported", null) }
    }

    @Test fun `no repetido sigue sin preguntar`() = runTest {
        servidorContesta(duplicate = false)

        assertThat(controller.confirm("5512345678", NumberKind.LINKING, null)).isTrue()
        assertThat(controller.prompt.value).isNull()
    }

    @Test fun `sin red sigue sin preguntar`() = runTest {
        coEvery { api.checkLinkingNumber(any(), any(), any()) } throws IOException("sin red")

        assertThat(controller.confirm("5512345678", NumberKind.LINKING, null)).isTrue()
        assertThat(controller.prompt.value).isNull()
        assertThat(controller.checking.value).isFalse()
    }

    @Test fun `si el servidor no contesta en 3 s sigue sin preguntar`() = runTest {
        coEvery { api.checkLinkingNumber(any(), any(), any()) } coAnswers {
            delay(10_000)
            Response.success(NumberCheckResponse(duplicate = true))
        }

        val respuesta = async { controller.confirm("5512345678", NumberKind.LINKING, null) }
        runCurrent()
        assertThat(controller.checking.value).isTrue()

        assertThat(respuesta.await()).isTrue()
        assertThat(currentTime).isEqualTo(3_000)
        assertThat(controller.prompt.value).isNull()
        assertThat(controller.checking.value).isFalse()
    }

    @Test fun `un segundo toque mientras pregunta no cuenta y no se guarda dos veces`() = runTest {
        servidorContesta(duplicate = true)
        val guardados = mutableListOf<String>()

        backgroundScope.submitLinkingNumber("5512345678", NumberKind.LINKING, null, controller) { guardados += it }
        runCurrent()
        backgroundScope.submitLinkingNumber("5512345678", NumberKind.LINKING, null, controller) { guardados += it }
        runCurrent()
        controller.saveAnyway()
        runCurrent()

        assertThat(guardados).containsExactly("5512345678")
        coVerify(exactly = 1) { api.checkLinkingNumber(any(), any(), any()) }
    }

    @Test fun `sin a quien preguntar guarda directo y el servidor lo revisa al recibirlo`() = runTest {
        val guardados = mutableListOf<String>()

        backgroundScope.submitLinkingNumber("551234567", NumberKind.LINKING, null, capture = null) { guardados += it }

        assertThat(guardados).containsExactly("551234567")
    }
}
