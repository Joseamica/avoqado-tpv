package com.jaac.avoqado_tpv.core.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiFailoverControllerTest {
    @After fun tearDown() = unmockkAll()

    @Test
    fun `P1 la cancelacion durante la espera no devuelve un resultado`() = runTest {
        val wifi = mockk<WifiManager>(relaxed = true) { every { isWifiEnabled } returns true }
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.WIFI_SERVICE) } returns wifi
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        var resultado: WifiToggleResult? = null
        val job = launch { resultado = WifiFailoverController(ctx).setWifiEnabled(enabled = false, source = "prueba") }
        runCurrent()
        advanceTimeBy(500)
        job.cancel()
        runCurrent()

        // Hoy `catch (exception: Exception)` se traga la CancellationException del delay(1500) y ASIGNA un resultado
        // falso (success = false aunque el apagado ya salió hacia Android).
        assertNull(resultado)
    }

    @Test
    fun `P1 forzar pide prender aunque la lectura ya diga prendido`() = runTest {
        val wifi = mockk<WifiManager>(relaxed = true) { every { isWifiEnabled } returns true }
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.WIFI_SERVICE) } returns wifi
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        WifiFailoverController(ctx).setWifiEnabled(enabled = true, source = "prueba", forzar = true)

        io.mockk.verify { wifi.setWifiEnabled(true) }
    }
}
