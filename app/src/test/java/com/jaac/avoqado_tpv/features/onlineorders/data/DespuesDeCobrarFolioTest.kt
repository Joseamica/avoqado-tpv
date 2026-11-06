package com.jaac.avoqado_tpv.features.onlineorders.data

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioProducto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * P4 (tienda híbrida): con el cobro de un folio YA registrado, la terminal lo acepta (T2/T9) y después intenta la comanda de apoyo.
 * Nada de esto se encola: si «aceptar» falla o la app muere, la sincronización del SERVIDOR (`syncPaidFolios`) pasa el folio a
 * «Por entregar» la próxima vez que alguien lee la lista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DespuesDeCobrarFolioTest {

    private val folios = mockk<FoliosRepository>()
    private val comanda = mockk<ComandaDeApoyo>(relaxed = true)
    private val storage = mockk<SecureStorage> { every { getVenueId() } returns "v1" }

    private val folio = ComandaDeFolio("ord-1", "W7K3P", "Ana", null, listOf(FolioProducto("Latte", 1, null, emptyList())))

    @Test
    fun `despues de cobrar con red acepta el folio y despues intenta la comanda`() = runTest {
        coEvery { folios.aceptar("v1", "ord-1") } returns Result.success(Unit)
        val despues = DespuesDeCobrarFolio(folios, comanda, storage).also { it.alcance = this }

        despues.alConfirmarse(folio)
        advanceUntilIdle()

        coVerifyOrder {
            folios.aceptar("v1", "ord-1")
            comanda.intentar("v1", folio)
        }
    }

    @Test
    fun `si aceptar falla igual intenta la comanda`() = runTest {
        coEvery { folios.aceptar("v1", "ord-1") } returns Result.failure(IOException("sin red"))
        val despues = DespuesDeCobrarFolio(folios, comanda, storage).also { it.alcance = this }

        despues.alConfirmarse(folio)
        advanceUntilIdle()

        coVerify(exactly = 1) { comanda.intentar("v1", folio) }
    }

    @Test
    fun `matar la app despues de cobrar no deja nada en el aparato - aceptar no se encola ni se reintenta`() = runTest {
        coEvery { folios.aceptar("v1", "ord-1") } returns Result.failure(IOException("sin red"))
        val despues = DespuesDeCobrarFolio(folios, comanda, storage).also { it.alcance = this }

        despues.alConfirmarse(folio)
        advanceUntilIdle()

        // Una sola llamada y nada más: el folio lo pasa a «Por entregar» el SERVIDOR al leer la lista, no esta terminal.
        coVerify(exactly = 1) { folios.aceptar("v1", "ord-1") }
        confirmVerified(folios)
    }

    @Test
    fun `salir de la pantalla del cobro no corta la comanda`() = runTest {
        val compuerta = CompletableDeferred<Unit>()
        coEvery { folios.aceptar("v1", "ord-1") } coAnswers { compuerta.await(); Result.success(Unit) }
        val despues = DespuesDeCobrarFolio(folios, comanda, storage).also { it.alcance = this }
        val pantallaDelCobro = Job()

        despues.alConfirmarse(folio)
        pantallaDelCobro.cancel() // el viewModelScope del cobro muere: la comanda no vive ahí
        compuerta.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { comanda.intentar("v1", folio) }
    }

    @Test
    fun `sin negocio guardado no hace nada`() = runTest {
        every { storage.getVenueId() } returns null
        val despues = DespuesDeCobrarFolio(folios, comanda, storage).also { it.alcance = this }

        despues.alConfirmarse(folio)
        advanceUntilIdle()

        coVerify(exactly = 0) { folios.aceptar(any(), any()) }
        coVerify(exactly = 0) { comanda.intentar(any(), any()) }
    }

    @Test
    fun `un error inesperado no tumba la app`() = runTest {
        coEvery { folios.aceptar("v1", "ord-1") } throws IllegalStateException("inesperado")
        // Con el alcance de la prueba, una excepción que se escapara tumbaría runTest (en la app, la cerraría).
        val despues = DespuesDeCobrarFolio(folios, comanda, storage).also { it.alcance = this }

        despues.alConfirmarse(folio)
        advanceUntilIdle()

        coVerify(exactly = 1) { folios.aceptar("v1", "ord-1") }
    }
}
