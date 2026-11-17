package com.jaac.avoqado_tpv.core.util

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * D18 (PAX, 16-nov): al volver el WiFi, Android avisa dos redes y ~10 s después manda «Network LOST» de la VIEJA. El flujo
 * decía «sin red» con la otra red viva y, por su `distinctUntilChanged`, el corte REAL siguiente ya no emitía nada: el aviso
 * rojo tardó 15 s (lo descubrió el latido). El flujo lleva las redes validadas y dice «sin red» sólo cuando no queda ninguna.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ConnectivityObserverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun red(id: Int): Network = ShadowNetwork.newInstance(id)
    private fun callback() = shadowOf(cm).networkCallbacks.single()

    @Test
    fun `P1 D18 el LOST tardio de la red vieja no dice sin red y el corte real siguiente si`() = runTest {
        val emitidos = mutableListOf<NetworkStatus>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { ConnectivityObserver(context).observe().toList(emitidos) }
        runCurrent()
        val cb = callback()
        val vieja = red(1)
        val nueva = red(2)

        cb.onAvailable(vieja); cb.onAvailable(nueva); runCurrent()
        assertThat(emitidos.last()).isEqualTo(NetworkStatus.Available)
        val antesDelTardio = emitidos.size

        cb.onLost(vieja); runCurrent() // el «Network LOST» de ~10 s después
        assertThat(emitidos.drop(antesDelTardio)).isEmpty()

        cb.onLost(nueva); runCurrent() // el corte real
        assertThat(emitidos.drop(antesDelTardio)).containsExactly(NetworkStatus.Unavailable)

        job.cancel()
    }

    @Test
    fun `P1 D18 el segundo ciclo tambien avisa el corte y la vuelta`() = runTest {
        val emitidos = mutableListOf<NetworkStatus>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { ConnectivityObserver(context).observe().toList(emitidos) }
        runCurrent()
        val cb = callback()

        // Ciclo 1: vuelve con dos redes, llega el tardío de la vieja, corte real.
        cb.onAvailable(red(1)); cb.onAvailable(red(2)); cb.onLost(red(1)); cb.onLost(red(2))
        // Ciclo 2: vuelve, otra vez dos redes y el tardío; corte real.
        cb.onAvailable(red(3)); cb.onAvailable(red(4)); cb.onLost(red(3)); runCurrent()
        val antesDelCorte = emitidos.size
        assertThat(emitidos.last()).isEqualTo(NetworkStatus.Available)
        cb.onLost(red(4)); runCurrent()
        assertThat(emitidos.drop(antesDelCorte)).containsExactly(NetworkStatus.Unavailable)

        cb.onAvailable(red(5)); runCurrent()
        assertThat(emitidos.last()).isEqualTo(NetworkStatus.Available)
        job.cancel()
    }

    @Test
    fun `D18 una red que pierde la validacion cuenta como perdida`() = runTest {
        val emitidos = mutableListOf<NetworkStatus>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { ConnectivityObserver(context).observe().toList(emitidos) }
        runCurrent()
        val cb = callback()
        val validada = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val sinValidar = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        cb.onAvailable(red(1)); cb.onCapabilitiesChanged(red(1), validada)
        cb.onAvailable(red(2)); runCurrent()
        val antes = emitidos.size
        cb.onCapabilitiesChanged(red(1), sinValidar); runCurrent()
        assertThat(emitidos.drop(antes)).isEmpty() // queda la red 2
        cb.onLost(red(2)); runCurrent()
        assertThat(emitidos.drop(antes)).containsExactly(NetworkStatus.Unavailable)
        job.cancel()
    }
}
