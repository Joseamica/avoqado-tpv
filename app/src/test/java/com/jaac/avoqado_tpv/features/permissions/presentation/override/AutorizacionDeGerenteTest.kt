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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        coEvery { repo.pinPrendido(any()) } returns null
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

    @Test fun pin_apagado_devuelve_Negada_sin_abrir_dialogo() = runTest {
        coEvery { repo.pinPrendido("v1") } returns false
        val r = auth.paraCortesia("v1", "m")
        assertThat(r).isEqualTo(Autorizacion.Negada("Esta cortesía la da un gerente con su sesión"))
        assertThat(auth.prompt.value).isNull()
    }

    @Test fun con_permiso_no_consulta_el_interruptor() = runTest {
        coEvery { permisos.hasPermission("tpv-orders:comp") } returns true
        auth.paraCortesia("v1", "m")
        coVerify(exactly = 0) { repo.pinPrendido(any()) }
    }

    @Test fun forzar_tambien_consulta_y_respeta_apagado() = runTest {
        coEvery { repo.pinPrendido("v1") } returns false
        assertThat(auth.paraCortesia("v1", "m", forzar = true)).isInstanceOf(Autorizacion.Negada::class.java)
    }

    @Test fun null_abre_el_dialogo_y_no_se_guarda() = runTest {
        pide(); advanceUntilIdle()
        assertThat(auth.prompt.value).isNotNull()
        auth.cancelarTodo(); advanceUntilIdle()
        pide(); advanceUntilIdle()
        auth.cancelarTodo(); advanceUntilIdle()
        coVerify(exactly = 2) { repo.pinPrendido("v1") }
    }

    @Test fun false_y_true_se_guardan_en_cache() = runTest {
        coEvery { repo.pinPrendido("v1") } returns false
        auth.paraCortesia("v1", "m"); auth.paraCortesia("v1", "m")
        coVerify(exactly = 1) { repo.pinPrendido("v1") }
        coEvery { repo.pinPrendido("v2") } returns true
        coEvery { repo.pedirToken("v2", "1234", "orders:comp") } returns Result.success("t2")
        repeat(2) {
            val r = async { auth.paraCortesia("v2", "m") }; advanceUntilIdle()
            auth.enviarPin(auth.prompt.value!!.id, "1234"); advanceUntilIdle()
            r.await()
        }
        coVerify(exactly = 1) { repo.pinPrendido("v2") }
    }

    @Test fun pinPrendido_colgado_abre_el_dialogo_a_los_3s_y_no_cachea() = runTest {
        coEvery { repo.pinPrendido("v1") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val r = pide(); advanceTimeBy(3_001); runCurrent()
        assertThat(auth.prompt.value).isNotNull()
        auth.cancelarTodo(); advanceUntilIdle(); r.await()
        coEvery { repo.pinPrendido("v1") } returns false
        assertThat(auth.paraCortesia("v1", "m")).isInstanceOf(Autorizacion.Negada::class.java)
        coVerify(exactly = 2) { repo.pinPrendido("v1") }
    }

    @Test fun cancelarTodo_durante_el_GET_termina_Cancelada() = runTest {
        val gate = CompletableDeferred<Boolean?>()
        coEvery { repo.pinPrendido("v1") } coAnswers { gate.await() }
        val r = pide(); runCurrent()
        auth.cancelarTodo()
        gate.complete(false); advanceUntilIdle()
        assertThat(r.await()).isEqualTo(Autorizacion.Cancelada)
        assertThat(auth.prompt.value).isNull()
    }

    @Test fun cache_expira_a_los_5_min() = runTest {
        var t = 0L
        auth.reloj = { t }
        coEvery { repo.pinPrendido("v1") } returns false
        auth.paraCortesia("v1", "m")
        t = 4 * 60_000_000_000L; auth.paraCortesia("v1", "m")
        coVerify(exactly = 1) { repo.pinPrendido("v1") }
        t = 5 * 60_000_000_000L + 1; auth.paraCortesia("v1", "m")
        coVerify(exactly = 2) { repo.pinPrendido("v1") }
    }

    // D1: el mismo diálogo para otro permiso, con su motivo y la llave de la operación que autoriza.
    private val LLAVE = "descuento-cliente:c1:d1"

    private fun TestScope.pideDescuento() =
        async { auth.paraPermiso("v1", "discounts:apply", "discounts:apply", "Descuento de Cecilia", LLAVE) }

    @Test fun paraPermiso_pide_su_permiso_con_la_llave_y_el_motivo() = runTest {
        coEvery { permisos.hasPermission("discounts:apply") } returns false
        coEvery { repo.pedirToken("v1", "1234", "discounts:apply", LLAVE) } returns Result.success("tok-d")
        val r = pideDescuento(); advanceUntilIdle()
        val p = auth.prompt.value!!
        assertThat(p.motivo).isEqualTo("Descuento de Cecilia")
        auth.enviarPin(p.id, "1234"); advanceUntilIdle()
        assertThat(r.await()).isEqualTo(Autorizacion.ConPin("tok-d"))
        coVerify(exactly = 0) { permisos.hasPermission("tpv-orders:comp") }
    }

    @Test fun paraPermiso_con_su_permiso_no_abre_dialogo() = runTest {
        coEvery { permisos.hasPermission("discounts:apply") } returns true
        assertThat(pideDescuento().await()).isEqualTo(Autorizacion.NoHaceFalta)
        assertThat(auth.prompt.value).isNull()
    }

    @Test fun paraPermiso_con_el_PIN_apagado_no_habla_de_cortesia() = runTest {
        coEvery { permisos.hasPermission("discounts:apply") } returns false
        coEvery { repo.pedirToken("v1", "1234", "discounts:apply", LLAVE) } returns Result.failure(ErrorDeAutorizacion.PinApagado)
        pideDescuento(); advanceUntilIdle()
        auth.enviarPin(auth.prompt.value!!.id, "1234"); advanceUntilIdle()
        assertThat(auth.prompt.value!!.error).isEqualTo("En este negocio el PIN de gerente está apagado. Lo autoriza un gerente con su sesión")
        auth.cancelarTodo(); advanceUntilIdle()
        assertThat(pideDescuento().await()).isEqualTo(Autorizacion.Negada("Lo autoriza un gerente con su sesión"))
    }

    @Test fun PinApagado_del_servidor_guarda_false() = runTest {
        coEvery { repo.pinPrendido("v1") } returns true
        coEvery { repo.pedirToken("v1", "1234", "orders:comp") } returns Result.failure(ErrorDeAutorizacion.PinApagado)
        pide(); advanceUntilIdle()
        auth.enviarPin(auth.prompt.value!!.id, "1234"); advanceUntilIdle()
        auth.cancelarTodo(); advanceUntilIdle()
        assertThat(auth.paraCortesia("v1", "m")).isInstanceOf(Autorizacion.Negada::class.java)
        coVerify(exactly = 1) { repo.pinPrendido("v1") }
    }
}
