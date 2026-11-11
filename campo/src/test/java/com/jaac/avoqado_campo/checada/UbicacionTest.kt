package com.jaac.avoqado_campo.checada

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UbicacionTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fused = mockk<FusedLocationProviderClient>()
    private val nunca = mockk<Task<Location>>(relaxed = true) // nunca llama a sus listeners

    private fun lugar(precision: Float = 15f, falsa: Boolean = false, edadNanos: Long = 0) = Location("fused").apply {
        latitude = 19.43; longitude = -99.13; accuracy = precision; isMock = falsa
        elapsedRealtimeNanos = AHORA - edadNanos
    }

    private fun ubicacion(permiso: Boolean = true) = Ubicacion(context, fused, permiso = { permiso }, ahoraNanos = { AHORA })

    @Test fun `un fused que nunca contesta da null a los 10 s`() = runTest {
        every { fused.getCurrentLocation(any<Int>(), any()) } returns nunca
        every { fused.lastLocation } returns nunca
        val lectura = async { ubicacion().leer() }
        advanceTimeBy(9_999); runCurrent()
        assertFalse(lectura.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertTrue(lectura.isCompleted)
        assertNull(lectura.await())
    }

    @Test fun `la ubicacion falsa se dice`() = runTest {
        every { fused.getCurrentLocation(any<Int>(), any()) } returns Tasks.forResult(lugar(falsa = true))
        every { fused.lastLocation } returns nunca
        val l = ubicacion().leer()!!
        assertTrue(l.falsa)
        assertEquals(19.43, l.lat, 0.0)
        assertEquals(15f, l.precision)
    }

    @Test fun `precision peor que 1000 m no sirve`() = runTest {
        every { fused.getCurrentLocation(any<Int>(), any()) } returns Tasks.forResult(lugar(precision = 1500f))
        every { fused.lastLocation } returns nunca
        assertNull(ubicacion().leer(esperaMs = 1_000))
    }

    @Test fun `sin la actual sirve la ultima de menos de 2 min y no una vieja`() = runTest {
        every { fused.getCurrentLocation(any<Int>(), any()) } returns Tasks.forResult<Location>(null)
        every { fused.lastLocation } returns Tasks.forResult(lugar(edadNanos = 60_000_000_000))
        assertFalse(ubicacion().leer()!!.falsa)
        every { fused.lastLocation } returns Tasks.forResult(lugar(edadNanos = 180_000_000_000))
        assertNull(ubicacion().leer())
    }

    @Test fun `sin permiso no pregunta`() = runTest {
        assertNull(ubicacion(permiso = false).leer())
    }

    private companion object { const val AHORA = 500_000_000_000L }
}
