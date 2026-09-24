package com.jaac.avoqado_tpv.features.permissions.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.authentication.data.dto.StaffPermissionsData
import com.jaac.avoqado_tpv.features.authentication.data.dto.StaffPermissionsResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response

/**
 * 🔴 Codex r8 (P1-3, 22-sep): la lista de permisos EFECTIVOS guardada en el aparato es de UNA persona en UN negocio.
 *
 * El respaldo SIN RED de «no se presentó tarjeta» decide con esa lista quién puede cerrar un cobro en el aparato. Estaba
 * guardada sin dueño: el gerente A deja su lista, su sesión caduca y falla el refresh (`clearSession` no la borra), entra el
 * cajero B y falla la descarga de SUS permisos ⇒ el repositorio le devolvía la lista de A, y B podía cerrar el pendiente sin
 * red con el permiso del gerente. Volver a mirarla al tocar el botón no protegía: se releía la misma lista ajena.
 */
class PermissionsRepositoryTest {

    private val PERMISO = "payments:resolve-no-instrument"
    private val guardado = mutableMapOf<String, Any?>()
    private var staffDeLaSesion: String? = "staff-A"
    private var venueDeLaSesion: String? = "v1"

    /** Codex r9 (P2-8): cuántas escrituras le quedan al proceso antes de «morir» (cada `put`/`remove` es una). */
    private var escriturasAntesDeMorir = Int.MAX_VALUE
    private fun escribir(accion: () -> Unit) {
        if (escriturasAntesDeMorir <= 0) throw IllegalStateException("el proceso murió aquí")
        escriturasAntesDeMorir--
        accion()
    }

    /** El almacenamiento seguro, con memoria de verdad: lo que se guarda se lee después. */
    private val secureStorage = mockk<SecureStorage>(relaxed = true).also { s ->
        every { s.getString(any(), any()) } answers { guardado[firstArg()] as String? ?: secondArg() }
        every { s.putString(any(), any()) } answers { escribir { guardado[firstArg()] = secondArg<String>() } }
        every { s.getLong(any(), any()) } answers { guardado[firstArg()] as Long? ?: secondArg() }
        every { s.putLong(any(), any()) } answers { escribir { guardado[firstArg()] = secondArg<Long>() } }
        every { s.remove(any()) } answers { escribir { guardado.remove(firstArg<String>()) } }
        every { s.getStaffId() } answers { staffDeLaSesion }
        every { s.getVenueId() } answers { venueDeLaSesion }
    }
    private val api = mockk<ApiService>()
    private val repo = PermissionsRepository(api, secureStorage)

    private fun listaDe(staffId: String, venueId: String = "v1", permisos: List<String> = listOf(PERMISO)) =
        Response.success(StaffPermissionsResponse(true, StaffPermissionsData(staffId, venueId, "MANAGER", permisos)))

    @Test fun `r8 P1-3 - la lista del gerente A NO habilita el boton sin red del cajero B cuando la descarga de B falla`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)
        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isTrue()   // A: sí

        // La sesión de A caduca, falla el refresh (la lista queda guardada) y entra el cajero B; su descarga falla.
        staffDeLaSesion = "staff-B"
        coEvery { api.getStaffPermissions() } throws java.io.IOException("sin red")

        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isFalse()
        assertThat(repo.getPermissions(forceRefresh = true).isFailure).isTrue()   // no le «presta» la lista de A
        assertThat(repo.hasPermission(PERMISO)).isFalse()
    }

    @Test fun `r8 P1-3 - tampoco sirve la lista de la MISMA persona en OTRO negocio`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A", venueId = "v1")
        repo.getPermissions(forceRefresh = true)

        venueDeLaSesion = "v2"

        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isFalse()
    }

    @Test fun `r8 P1-3 - una lista guardada SIN duenio (version anterior) no habilita nada`() = runTest {
        guardado["permissions_cache"] = PERMISO
        guardado["permissions_cache_timestamp"] = System.currentTimeMillis()

        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isFalse()
        assertThat(repo.hasPermission(PERMISO)).isFalse()
    }

    @Test fun `r8 P1-3 control - la lista PROPIA se sigue usando sin red`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)
        coEvery { api.getStaffPermissions() } throws java.io.IOException("sin red")

        assertThat(repo.getPermissions(forceRefresh = true).getOrNull()).containsExactly(PERMISO)
        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isTrue()
    }

    @Test fun `r8 P1-3 control - sin sesion no hay lista que valga`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)
        staffDeLaSesion = null

        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isFalse()
    }

    // ── Codex r9 ────────────────────────────────────────────────────────────────────────────────────

    private fun http(codigo: Int) = Response.error<StaffPermissionsResponse>(codigo, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

    @Test fun `r9 P1-4 - un 403 del servidor (membresia eliminada) invalida la lista guardada, no la reusa`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)
        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isTrue()

        // El dueño borra la membresía del gerente: el servidor contesta 403. No es «sin red»: es un NO.
        coEvery { api.getStaffPermissions() } returns http(403)

        assertThat(repo.getPermissions(forceRefresh = true).isFailure).isTrue()
        assertWithMessage("sin red, la lista vieja no puede seguir dejándolo declarar")
            .that(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isFalse()
        assertThat(repo.hasPermission(PERMISO)).isFalse()
    }

    @Test fun `r9 P1-4 control - un 500 no es un NO - la lista propia se conserva`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)
        coEvery { api.getStaffPermissions() } returns http(500)

        assertThat(repo.getPermissions(forceRefresh = true).getOrNull()).containsExactly(PERMISO)
        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isTrue()
    }

    @Test fun `r9 P2-7 - un 200 que llega DESPUES de cambiar de sesion no se devuelve como permisos de la sesion nueva`() = runTest {
        // Se pide con la sesión A; mientras la respuesta viaja entra B; llega el 200 de A.
        coEvery { api.getStaffPermissions() } coAnswers {
            staffDeLaSesion = "staff-B"
            listaDe("staff-A")
        }

        val resultado = repo.getPermissions(forceRefresh = true)

        assertWithMessage("el VALOR devuelto, no sólo la caché").that(resultado.getOrNull().orEmpty()).doesNotContain(PERMISO)
        assertThat(repo.hasPermission(PERMISO)).isFalse()
        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isFalse()
    }

    @Test fun `r9 P2-8 - morir a media escritura nunca deja la lista de B con el duenio de A`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)
        val deA = HashMap(guardado)

        for (n in 0..3) {
            guardado.clear(); guardado.putAll(deA)
            // Entra el cajero B; su lista (SIN el permiso) se guarda y el proceso muere tras `n` escrituras.
            staffDeLaSesion = "staff-B"
            coEvery { api.getStaffPermissions() } returns listaDe("staff-B", permisos = listOf("otro:permiso"))
            escriturasAntesDeMorir = n
            repo.getPermissions(forceRefresh = true)
            escriturasAntesDeMorir = Int.MAX_VALUE

            // A vuelve sin red: lo que esté guardado a su nombre tiene que ser SU lista, o ninguna.
            staffDeLaSesion = "staff-A"
            coEvery { api.getStaffPermissions() } throws java.io.IOException("sin red")
            val deA2 = repo.getPermissions(forceRefresh = true).getOrNull()
            assertWithMessage("muerte tras $n escrituras: $deA2").that(deA2 == null || deA2 == setOf(PERMISO)).isTrue()
        }
    }

    // ── Codex r10/r11: el ORDEN de las descargas y el borrado que falla ────────────────────────────────────────

    @Test fun `r11 P1-1 - UNA descarga a la vez - la segunda no sale hasta que la primera se aplico, y gana la leida despues`() = runTest {
        // Codex r11: numerar las descargas no basta — el SERVIDOR puede leer la segunda antes que la primera. Serializada la
        // descarga completa (petición + aplicación), el servidor lee en el orden en que salen, y la que se lee después manda.
        val casos = listOf(
            "403 después" to (listaDe("staff-A") to http(403)),
            "lista vacía después" to (listaDe("staff-A") to listaDe("staff-A", permisos = emptyList())),
            "403 antes, lista después" to (http(403) to listaDe("staff-A")),
        )
        for ((caso, respuestas) in casos) {
            val (primera, segunda) = respuestas
            val esperaPrimera = CompletableDeferred<Response<StaffPermissionsResponse>>()
            var llamadas = 0
            coEvery { api.getStaffPermissions() } coAnswers { if (llamadas++ == 0) esperaPrimera.await() else segunda }
            val uno = async { repo.getPermissions(forceRefresh = true) }
            val dos = async { repo.getPermissions(forceRefresh = true) }
            runCurrent()
            assertWithMessage("$caso: la segunda NO sale mientras la primera no se aplica").that(llamadas).isEqualTo(1)

            esperaPrimera.complete(primera)
            uno.await(); dos.await()

            assertWithMessage(caso).that(llamadas).isEqualTo(2)
            val esperado = segunda.body()?.data?.permissions?.contains(PERMISO) == true
            assertWithMessage("$caso: manda la que el servidor leyó DESPUÉS")
                .that(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isEqualTo(esperado)
        }
    }

    // ── Codex r12 (P3-3): el candado cubre TODA la descarga, también el GUARDADO, y se comporta ante cancelaciones ──

    @Test fun `r12 P3-3 - el candado cubre el GUARDADO - la segunda no sale mientras la primera escribe (hilos reales)`() = kotlinx.coroutines.runBlocking {
        // Tiempo REAL y dos hilos: el guardado de la primera se detiene con una barrera entre hilos; soltar el candado antes de
        // guardar dejaría salir a la segunda. 🔴 Codex r13 (P3): la segunda arranca UNDISPATCHED — corre en este hilo hasta su
        // primera suspensión, o sea hasta el candado, ANTES de mirar; así el resultado no depende del planificador.
        val guardando = java.util.concurrent.CountDownLatch(1)
        val soltarGuardado = java.util.concurrent.CountDownLatch(1)
        val primeraEscritura = java.util.concurrent.atomic.AtomicBoolean(true)
        val barreraVencio = java.util.concurrent.atomic.AtomicBoolean(false)
        every { secureStorage.putString(any(), any()) } answers {
            if (primeraEscritura.getAndSet(false)) {
                guardando.countDown()
                if (!soltarGuardado.await(10, java.util.concurrent.TimeUnit.SECONDS)) barreraVencio.set(true)
            }
            escribir { guardado[firstArg()] = secondArg<String>() }
        }
        val llamadas = java.util.concurrent.atomic.AtomicInteger()
        coEvery { api.getStaffPermissions() } coAnswers { llamadas.incrementAndGet(); listaDe("staff-A") }

        val uno = async(kotlinx.coroutines.Dispatchers.Default) { repo.getPermissions(forceRefresh = true) }
        try {
            assertWithMessage("la primera llegó a guardar").that(guardando.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            val dos = async(kotlinx.coroutines.Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                repo.getPermissions(forceRefresh = true)
            }
            assertWithMessage("la segunda salió al servidor mientras la primera todavía guardaba").that(llamadas.get()).isEqualTo(1)
            soltarGuardado.countDown()
            uno.await(); dos.await()
            assertThat(llamadas.get()).isEqualTo(2)
            assertWithMessage("la barrera se soltó a tiempo, no por vencimiento").that(barreraVencio.get()).isFalse()
        } finally {
            soltarGuardado.countDown()   // un fallo de arriba no deja al primer hilo colgado 10 s
        }
    }

    @Test fun `r12 P3-3b - cancelar al que tiene el candado lo suelta, y cancelar al que espera no toca al que lo tiene`() = runTest {
        // El dueño del candado se cancela a media petición: la siguiente descarga no se queda esperando para siempre.
        val sinContestar = CompletableDeferred<Response<StaffPermissionsResponse>>()
        var llamadas = 0
        coEvery { api.getStaffPermissions() } coAnswers { if (llamadas++ == 0) sinContestar.await() else listaDe("staff-A") }
        val dueno = async { repo.getPermissions(forceRefresh = true) }
        runCurrent()
        dueno.cancel(); runCurrent()
        assertWithMessage("el candado quedó libre").that(repo.getPermissions(forceRefresh = true).getOrNull()).containsExactly(PERMISO)

        // El que ESPERA se cancela: el dueño termina igual y el cancelado nunca sale al servidor.
        val deDueno = CompletableDeferred<Response<StaffPermissionsResponse>>()
        llamadas = 0
        coEvery { api.getStaffPermissions() } coAnswers { llamadas++; deDueno.await() }
        val dueno2 = async { repo.getPermissions(forceRefresh = true) }
        runCurrent()
        val esperando = async { repo.getPermissions(forceRefresh = true) }
        runCurrent()
        esperando.cancel(); runCurrent()
        deDueno.complete(listaDe("staff-A"))
        assertThat(dueno2.await().getOrNull()).containsExactly(PERMISO)
        assertWithMessage("el que esperaba y se canceló no salió").that(llamadas).isEqualTo(1)
    }

    @Test fun `r12 P3-3c - dos llamadas NO forzadas comparten la lista recien descargada - el servidor se consulta una vez`() = runTest {
        val contestacion = CompletableDeferred<Response<StaffPermissionsResponse>>()
        var llamadas = 0
        coEvery { api.getStaffPermissions() } coAnswers { llamadas++; contestacion.await() }
        val uno = async { repo.getPermissions() }
        runCurrent()
        val dos = async { repo.getPermissions() }   // no hay caché todavía: espera el candado…
        runCurrent()

        contestacion.complete(listaDe("staff-A"))
        assertThat(uno.await().getOrNull()).containsExactly(PERMISO)
        assertThat(dos.await().getOrNull()).containsExactly(PERMISO)   // …y al entrar usa la lista que la primera acaba de guardar
        assertWithMessage("la segunda NO volvió a pedir").that(llamadas).isEqualTo(1)
    }

    @Test fun `r10 P2-3 - un 403 atrasado de A no borra la lista valida de B`() = runTest {
        val deA = CompletableDeferred<Response<StaffPermissionsResponse>>()
        var llamadas = 0
        coEvery { api.getStaffPermissions() } coAnswers { if (llamadas++ == 0) deA.await() else listaDe("staff-B") }
        val pendienteDeA = async { repo.getPermissions(forceRefresh = true) }   // A pide y espera
        runCurrent()
        staffDeLaSesion = "staff-B"
        val deB = async { repo.getPermissions(forceRefresh = true) }            // B pide su lista (espera a que A termine)
        runCurrent()

        deA.complete(http(403))                                                   // llega el 403 de A, con la sesión ya de B
        pendienteDeA.await(); deB.await()

        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isTrue()
    }

    // La defensa de la SESIÓN, aislada: la descarga de A es la ÚNICA en vuelo y nadie aplicó nada después.

    @Test fun `r10 P2-3b - SOLO la sesion - un 403 de A mas nuevo que todo lo aplicado tampoco borra la lista de B`() = runTest {
        staffDeLaSesion = "staff-B"
        coEvery { api.getStaffPermissions() } returns listaDe("staff-B")
        repo.getPermissions(forceRefresh = true)                                 // B ya tenía su lista (la última aplicada)
        staffDeLaSesion = "staff-A"
        val deA = CompletableDeferred<Response<StaffPermissionsResponse>>()
        coEvery { api.getStaffPermissions() } coAnswers { deA.await() }
        val pendienteDeA = async { repo.getPermissions(forceRefresh = true) }   // A pide DESPUÉS: su respuesta es la más nueva
        runCurrent()
        staffDeLaSesion = "staff-B"                                              // …y B vuelve antes de que conteste
        deA.complete(http(403))
        pendienteDeA.await()

        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isTrue()
    }

    @Test fun `r10 P2-4 - si borrar la lista falla tras un 403, tampoco vale en la llamada siguiente - y una lista nueva la rehabilita`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)

        coEvery { api.getStaffPermissions() } returns http(403)
        escriturasAntesDeMorir = 0                    // el borrado falla: la lista sigue en disco
        assertThat(repo.getPermissions(forceRefresh = true).isFailure).isTrue()
        escriturasAntesDeMorir = Int.MAX_VALUE

        assertWithMessage("el lector estático de la declaración sin red").that(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isFalse()
        coEvery { api.getStaffPermissions() } throws java.io.IOException("sin red")
        assertThat(repo.hasPermission(PERMISO)).isFalse()

        // Control: una descarga NUEVA y autoritativa para la misma persona vuelve a valer.
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)
        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isTrue()
    }

    @Test fun `r11 P2-2 - una lista SIN el permiso que llega bien pero no se puede guardar deja inservible la vieja`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)

        // El servidor ya quitó el permiso (200 autoritativo con otra lista) y el primer guardado falla.
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A", permisos = listOf("otro:permiso"))
        escriturasAntesDeMorir = 0
        val resultado = repo.getPermissions(forceRefresh = true)
        escriturasAntesDeMorir = Int.MAX_VALUE

        assertWithMessage("se devuelve lo que dijo el servidor, no la caché vieja").that(resultado.getOrNull()).isEqualTo(setOf("otro:permiso"))
        assertWithMessage("el lector del respaldo sin red").that(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isFalse()
    }

    @Test fun `r11 P2-3 - el 403 de B no levanta la revocacion de A (dos borrados fallidos)`() = runTest {
        coEvery { api.getStaffPermissions() } returns listaDe("staff-A")
        repo.getPermissions(forceRefresh = true)

        coEvery { api.getStaffPermissions() } returns http(403)
        escriturasAntesDeMorir = 0                           // A: 403 y el borrado falla — la lista de A sigue en disco
        repo.getPermissions(forceRefresh = true)
        staffDeLaSesion = "staff-B"                          // B: 403 y el borrado vuelve a fallar
        repo.getPermissions(forceRefresh = true)
        escriturasAntesDeMorir = Int.MAX_VALUE

        staffDeLaSesion = "staff-A"                          // A vuelve sin red
        coEvery { api.getStaffPermissions() } throws java.io.IOException("sin red")
        assertThat(PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, PERMISO)).isFalse()
        assertThat(repo.hasPermission(PERMISO)).isFalse()
    }
}
