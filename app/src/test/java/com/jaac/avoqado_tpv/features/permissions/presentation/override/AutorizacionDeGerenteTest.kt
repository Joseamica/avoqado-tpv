package com.jaac.avoqado_tpv.features.permissions.presentation.override

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.permissions.data.override.ErrorDeAutorizacion
import com.jaac.avoqado_tpv.features.permissions.data.override.PermissionOverrideRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutorizacionDeGerenteTest {
    private val permisos = mockk<PermissionsRepository>()
    private val repo = mockk<PermissionOverrideRepository>()
    private val storage = mockk<SecureStorage>()
    private val auth = AutorizacionDeGerente(permisos, repo, storage)

    @Before fun setUp() {
        mockkObject(PermissionsRepository.Companion)
        every { PermissionsRepository.invalidarListaTrasRechazo(any(), any(), any()) } returns Unit
        every { storage.getStaffId() } returns "s1"
        coEvery { permisos.hasPermission("tpv-orders:comp") } returns false
        coEvery { repo.pedirToken("v1", "1234", "orders:comp") } returns Result.success("tok-1")
    }

    @After fun tearDown() { unmockkObject(PermissionsRepository.Companion) }

    private fun TestScope.pide(motivo: String = "Para dar esta cortesía", forzar: Boolean = false) =
        async { auth.paraCortesia("v1", motivo, forzar) }

    @Test fun con_permiso_no_abre_dialogo() = runTest {
        coEvery { permisos.hasPermission("tpv-orders:comp") } returns true
        assertThat(auth.paraCortesia("v1", "m")).isEqualTo(Autorizacion.NoHaceFalta)
        assertThat(auth.prompt.value).isNull()
    }

    @Test fun sin_permiso_con_pin_bueno_devuelve_token_y_cierra() = runTest {
        val r = pide(); advanceUntilIdle()
        val p = auth.prompt.value!!
        assertThat(p.motivo).isEqualTo("Para dar esta cortesía")
        assertThat(p.error).isNull()
        auth.enviarPin(p.id, "1234"); advanceUntilIdle()
        assertThat(r.await()).isEqualTo(Autorizacion.ConPin("tok-1"))
        assertThat(auth.prompt.value).isNull()
    }

    @Test fun pin_malo_error_dentro_y_reintenta() = runTest {
        coEvery { repo.pedirToken("v1", "0000", "orders:comp") } returns Result.failure(ErrorDeAutorizacion.PinIncorrecto)
        val r = pide(); advanceUntilIdle()
        val id = auth.prompt.value!!.id
        auth.enviarPin(id, "0000"); advanceUntilIdle()
        assertThat(auth.prompt.value!!.error).isEqualTo("Código incorrecto")
        assertThat(auth.prompt.value!!.enviando).isFalse()
        assertThat(r.isCompleted).isFalse()
        auth.enviarPin(id, "1234"); advanceUntilIdle()
        assertThat(r.await()).isEqualTo(Autorizacion.ConPin("tok-1"))
    }

    @Test fun sin_red_SinConexion_dentro_del_dialogo() = runTest {
        coEvery { repo.pedirToken(any(), any(), any()) } returns Result.failure(ErrorDeAutorizacion.SinConexion)
        pide(); advanceUntilIdle()
        auth.enviarPin(auth.prompt.value!!.id, "1234"); advanceUntilIdle()
        assertThat(auth.prompt.value!!.error).isEqualTo(ErrorDeAutorizacion.SinConexion.texto)
        auth.cancelarTodo(); advanceUntilIdle()
    }

    @Test fun falla_generica_usa_texto_generico() = runTest {
        coEvery { repo.pedirToken(any(), any(), any()) } returns Result.failure(RuntimeException("x"))
        pide(); advanceUntilIdle()
        auth.enviarPin(auth.prompt.value!!.id, "1234"); advanceUntilIdle()
        assertThat(auth.prompt.value!!.error).isEqualTo("No se pudo autorizar. Intenta otra vez")
        auth.cancelarTodo(); advanceUntilIdle()
    }

    @Test fun cancelar_devuelve_Cancelada() = runTest {
        val r = pide(); advanceUntilIdle()
        auth.cancelar(auth.prompt.value!!.id); advanceUntilIdle()
        assertThat(r.await()).isEqualTo(Autorizacion.Cancelada)
        assertThat(auth.prompt.value).isNull()
    }

    @Test fun doble_envio_una_sola_peticion() = runTest {
        val gate = CompletableDeferred<Result<String>>()
        coEvery { repo.pedirToken("v1", "1234", "orders:comp") } coAnswers { gate.await() }
        val r = pide(); advanceUntilIdle()
        val id = auth.prompt.value!!.id
        auth.enviarPin(id, "1234"); advanceUntilIdle()
        assertThat(auth.prompt.value!!.enviando).isTrue()
        auth.enviarPin(id, "1234"); advanceUntilIdle()
        gate.complete(Result.success("tok-1")); advanceUntilIdle()
        assertThat(r.await()).isEqualTo(Autorizacion.ConPin("tok-1"))
        coVerify(exactly = 1) { repo.pedirToken(any(), any(), any()) }
    }

    @Test fun dos_cortesias_a_la_vez_un_dialogo_por_turno_con_ids_distintos() = runTest {
        val a = pide("A"); advanceUntilIdle()
        val b = pide("B"); advanceUntilIdle()
        val pa = auth.prompt.value!!
        assertThat(pa.motivo).isEqualTo("A")
        auth.enviarPin(pa.id, "1234"); advanceUntilIdle()
        assertThat(a.await()).isEqualTo(Autorizacion.ConPin("tok-1"))
        val pb = auth.prompt.value!!
        assertThat(pb.motivo).isEqualTo("B")
        assertThat(pb.id).isNotEqualTo(pa.id)
        auth.cancelar(pb.id); advanceUntilIdle()
        assertThat(b.await()).isEqualTo(Autorizacion.Cancelada)
    }

    @Test fun respuesta_de_id_viejo_no_completa_el_nuevo() = runTest {
        val gate = CompletableDeferred<Result<String>>()
        coEvery { repo.pedirToken("v1", "1234", "orders:comp") } coAnswers { gate.await() }
        val a = pide("A"); advanceUntilIdle()
        val idA = auth.prompt.value!!.id
        auth.enviarPin(idA, "1234"); advanceUntilIdle()
        auth.cancelar(idA); advanceUntilIdle()
        assertThat(a.await()).isEqualTo(Autorizacion.Cancelada)
        val b = pide("B"); advanceUntilIdle()
        val idB = auth.prompt.value!!.id
        gate.complete(Result.success("tok-A")); auth.enviarPin(idA, "1234"); auth.cancelar(idA); advanceUntilIdle()
        assertThat(b.isCompleted).isFalse()
        assertThat(auth.prompt.value!!.id).isEqualTo(idB)
        coVerify(exactly = 1) { repo.pedirToken(any(), any(), any()) }
        auth.cancelar(idB); advanceUntilIdle()
    }

    @Test fun cancelar_la_coroutine_que_espera_cierra_el_dialogo_y_libera() = runTest {
        val r = pide(); advanceUntilIdle()
        assertThat(auth.prompt.value).isNotNull()
        r.cancel(); advanceUntilIdle()
        assertThat(auth.prompt.value).isNull()
        val r2 = pide("otra"); advanceUntilIdle()
        assertThat(auth.prompt.value!!.motivo).isEqualTo("otra")
        auth.cancelar(auth.prompt.value!!.id); advanceUntilIdle()
        assertThat(r2.await()).isEqualTo(Autorizacion.Cancelada)
    }

    @Test fun cancelarTodo_completa_Cancelada() = runTest {
        val r = pide(); advanceUntilIdle()
        auth.cancelarTodo(); advanceUntilIdle()
        assertThat(r.await()).isEqualTo(Autorizacion.Cancelada)
        assertThat(auth.prompt.value).isNull()
    }

    @Test fun pin_que_llega_despues_de_cancelarTodo_no_pide_token_ni_completa() = runTest {
        val r = pide(); advanceUntilIdle()
        val id = auth.prompt.value!!.id
        auth.cancelarTodo()
        auth.enviarPin(id, "1234")
        advanceUntilIdle()
        assertThat(r.await()).isEqualTo(Autorizacion.Cancelada)
        coVerify(exactly = 0) { repo.pedirToken(any(), any(), any()) }
    }

    @Test fun cancelarTodo_cancela_la_activa_y_la_encolada_y_una_nueva_abre_normal() = runTest {
        val a = pide("A"); advanceUntilIdle()
        val b = pide("B"); advanceUntilIdle()
        auth.cancelarTodo(); advanceUntilIdle()
        assertThat(a.await()).isEqualTo(Autorizacion.Cancelada)
        assertThat(b.await()).isEqualTo(Autorizacion.Cancelada)
        assertThat(auth.prompt.value).isNull()
        val c = pide("C"); advanceUntilIdle()
        assertThat(auth.prompt.value!!.motivo).isEqualTo("C")
        auth.enviarPin(auth.prompt.value!!.id, "1234"); advanceUntilIdle()
        assertThat(c.await()).isEqualTo(Autorizacion.ConPin("tok-1"))
    }

    @Test fun excepcion_de_pedirToken_muestra_texto_generico_y_deja_abierto() = runTest {
        coEvery { repo.pedirToken(any(), any(), any()) } coAnswers { throw RuntimeException("boom") }
        val r = pide(); advanceUntilIdle()
        auth.enviarPin(auth.prompt.value!!.id, "1234"); advanceUntilIdle()
        assertThat(auth.prompt.value!!.error).isEqualTo("No se pudo autorizar. Intenta otra vez")
        assertThat(auth.prompt.value!!.enviando).isFalse()
        assertThat(r.isCompleted).isFalse()
        auth.cancelarTodo(); advanceUntilIdle()
    }

    @Test fun forzar_pide_pin_aunque_tenga_permiso_e_invalida_la_cache() = runTest {
        coEvery { permisos.hasPermission("tpv-orders:comp") } returns true
        val r = pide(forzar = true); advanceUntilIdle()
        assertThat(auth.prompt.value).isNotNull()
        verify { PermissionsRepository.invalidarListaTrasRechazo(storage, "v1", "s1") }
        auth.enviarPin(auth.prompt.value!!.id, "1234"); advanceUntilIdle()
        assertThat(r.await()).isEqualTo(Autorizacion.ConPin("tok-1"))
    }

    @Test fun pin_de_menos_de_4_no_se_manda() = runTest {
        val r = pide(); advanceUntilIdle()
        val id = auth.prompt.value!!.id
        auth.enviarPin(id, "123"); advanceUntilIdle()
        coVerify(exactly = 0) { repo.pedirToken(any(), any(), any()) }
        assertThat(auth.prompt.value!!.enviando).isFalse()
        auth.cancelar(id); advanceUntilIdle()
        r.await()
    }
}
