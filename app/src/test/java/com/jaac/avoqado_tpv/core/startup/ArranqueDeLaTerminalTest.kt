package com.jaac.avoqado_tpv.core.startup

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.dto.ActivationStatusResponse
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * Encargo 097e01 (21-nov-2026): reiniciar la TPV con el SERVIDOR mudo dejaba ~80 s el logo.
 *
 * El logo esperaba DOS consultas al servidor en fila (activación y módulos), cada una con tope de 25 s de
 * OkHttp — y ese tope no cuenta lo que la consulta pasa haciendo fila detrás de otras al mismo servidor.
 * Con la terminal ya activada en el aparato, el logo ahora espera a lo más [ArranqueDeLaTerminal.ESPERA_MAXIMA]
 * y sigue con lo guardado; lo que el servidor conteste después se aplica al fondo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArranqueDeLaTerminalTest {

    private val activada = Result.Success(
        ActivationStatusResponse(
            isActivated = true, status = "ACTIVE", venueId = "venue-1", venueName = "Café",
            venueSlug = "cafe", activatedAt = "2026-11-01T00:00:00Z", message = null
        )
    )
    private val noActivada = Result.Success(
        ActivationStatusResponse(
            isActivated = false, status = "INACTIVE", venueId = null, venueName = null,
            venueSlug = null, activatedAt = null, message = null
        )
    )
    private val sinRed = Result.Error(ApiException.NetworkError(IOException("sin red")))

    private class Registro {
        var consultas = 0
        var modulosTraidos = 0
    }

    private suspend fun TestScope.decidir(
        registro: Registro,
        activadaEnElAparato: Boolean = true,
        haySesion: Boolean = true,
        consultar: suspend () -> Result<ActivationStatusResponse>,
        traerModulos: suspend () -> Unit = {},
    ): ArranqueDeLaTerminal.Destino = ArranqueDeLaTerminal.decidir(
        alFondo = backgroundScope,
        activadaEnElAparato = { activadaEnElAparato },
        haySesion = { haySesion },
        consultarActivacion = { registro.consultas++; consultar() },
        traerModulos = { traerModulos(); registro.modulosTraidos++ },
    )

    @Test
    fun `P1 servidor mudo con la terminal activada y sesion abre el Inicio sin esperar de mas`() = runTest {
        val registro = Registro()
        val destino = decidir(registro, consultar = { awaitCancellation() })

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.INICIO)
        assertThat(currentTime).isAtMost(ArranqueDeLaTerminal.ESPERA_MAXIMA.inWholeMilliseconds)
    }

    @Test
    fun `P1 servidor mudo sin sesion abre el PIN sin esperar de mas`() = runTest {
        val destino = decidir(Registro(), haySesion = false, consultar = { awaitCancellation() })

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.LOGIN)
        assertThat(currentTime).isAtMost(ArranqueDeLaTerminal.ESPERA_MAXIMA.inWholeMilliseconds)
    }

    @Test
    fun `P1 activacion rapida pero modulos mudos tampoco detiene el logo`() = runTest {
        val destino = decidir(Registro(), consultar = { activada }, traerModulos = { awaitCancellation() })

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.INICIO)
        assertThat(currentTime).isAtMost(ArranqueDeLaTerminal.ESPERA_MAXIMA.inWholeMilliseconds)
    }

    @Test
    fun `servidor lento sigue al fondo y trae los modulos cuando por fin contesta`() = runTest {
        val registro = Registro()
        val destino = decidir(registro, consultar = { delay(60_000); activada })

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.INICIO)
        assertThat(registro.modulosTraidos).isEqualTo(0)

        advanceTimeBy(60_000)
        runCurrent()
        assertThat(registro.consultas).isEqualTo(1)
        assertThat(registro.modulosTraidos).isEqualTo(1)
    }

    @Test
    fun `servidor que contesta a tiempo se comporta como antes - trae modulos y luego decide`() = runTest {
        val registro = Registro()
        val destino = decidir(registro, consultar = { delay(500); activada }, traerModulos = { delay(500) })

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.INICIO)
        assertThat(registro.modulosTraidos).isEqualTo(1)
        assertThat(currentTime).isEqualTo(1_000)
    }

    @Test
    fun `servidor que contesta a tiempo que no esta activada manda a activar`() = runTest {
        val registro = Registro()
        val destino = decidir(registro, consultar = { noActivada })

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.ACTIVACION)
        assertThat(registro.modulosTraidos).isEqualTo(0)
    }

    @Test
    fun `sin red rapido con la terminal activada confia en lo guardado como antes`() = runTest {
        val registro = Registro()
        val destino = decidir(registro, haySesion = false, consultar = { sinRed })

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.LOGIN)
        assertThat(registro.modulosTraidos).isEqualTo(1)
    }

    @Test
    fun `sin activacion en el aparato espera al servidor completo porque solo el puede restaurarla`() = runTest {
        val registro = Registro()
        var activadaLocal = false
        val destino = ArranqueDeLaTerminal.decidir(
            alFondo = backgroundScope,
            activadaEnElAparato = { activadaLocal },
            haySesion = { true },
            // Como checkActivationStatusWithBackend: al contestar «activada» restaura el venue en el aparato.
            consultarActivacion = { registro.consultas++; delay(30_000); activadaLocal = true; activada },
            traerModulos = { registro.modulosTraidos++ },
        )

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.INICIO)
        assertThat(currentTime).isEqualTo(30_000)
        assertThat(registro.modulosTraidos).isEqualTo(1)
    }

    @Test
    fun `sin activacion en el aparato y sin red manda a activar`() = runTest {
        val destino = decidir(Registro(), activadaEnElAparato = false, consultar = { sinRed })

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.ACTIVACION)
    }

    @Test
    fun `si al vencer la espera el aparato ya no tiene la activacion manda a activar`() = runTest {
        var activadaLocal = true
        val destino = ArranqueDeLaTerminal.decidir(
            alFondo = backgroundScope,
            activadaEnElAparato = { activadaLocal },
            haySesion = { true },
            // Como un RETIRED: el aparato borró sus datos y la consulta sigue colgada.
            consultarActivacion = { activadaLocal = false; awaitCancellation() },
            traerModulos = {},
        )

        assertThat(destino).isEqualTo(ArranqueDeLaTerminal.Destino.ACTIVACION)
    }
}
