package com.jaac.avoqado_campo.version

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstaladorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instalador = Instalador(app)
    private val sesiones get() = app.packageManager.packageInstaller.mySessions

    @Before fun limpio() = sesiones.forEach { app.packageManager.packageInstaller.abandonSession(it.sessionId) }

    private fun apk(): File = tmp.newFile("campo-4.apk").also { it.writeBytes(ByteArray(4096) { 7 }) }

    @Test fun `sin permiso de esta fuente es FaltaPermiso, abre Ajustes con package y no crea sesion`() = runBlocking {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        assertEquals(ResultadoInstalacion.FaltaPermiso, instalador.instalar(apk()))
        val i = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, i.action)
        assertEquals("package:com.jaac.avoqado_tpv.campo", i.dataString)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(sesiones.isEmpty())
    }

    @Test fun `con permiso manda una sesion con el paquete propio y queda Enviado`() = runBlocking {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertEquals(ResultadoInstalacion.Enviado, instalador.instalar(apk()))
        assertEquals("com.jaac.avoqado_tpv.campo", sesiones.single().appPackageName)
        assertNull("no abre Ajustes", shadowOf(app).nextStartedActivity)
    }

    @Test fun `la sesion es MODE_FULL_INSTALL, del paquete propio, y no pide instalar sin confirmar`() {
        val p = parametrosDeSesion("com.jaac.avoqado_tpv.campo", 4096)
        assertEquals(SessionParams.MODE_FULL_INSTALL, SessionParams::class.java.getField("mode").getInt(p))
        assertEquals("com.jaac.avoqado_tpv.campo", SessionParams::class.java.getField("appPackageName").get(p))
        // Un celular personal confirma con su diálogo normal (Codex r1 SOBRA): nada de setRequireUserAction.
        assertEquals(SessionParams.USER_ACTION_UNSPECIFIED, SessionParams::class.java.getField("requireUserAction").getInt(p))
    }

    @Test fun `al arrancar abandona las sesiones propias que quedaron abiertas`() {
        val pi = app.packageManager.packageInstaller
        pi.createSession(parametrosDeSesion("com.jaac.avoqado_tpv.campo", 1))
        pi.createSession(parametrosDeSesion("com.jaac.avoqado_tpv.campo", 1))
        instalador.abandonarLasAbiertas()
        assertTrue(sesiones.isEmpty())
    }

    @Test fun `STATUS_PENDING_USER_ACTION lanza el EXTRA_INTENT del sistema`() {
        val confirmar = Intent("android.content.pm.action.CONFIRM_INSTALL")
        val i = Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmar)
        atender(app, i) { fail("no es un fallo: $it") }
        val lanzado = shadowOf(app).nextStartedActivity
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", lanzado.action)
        assertTrue(lanzado.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `STATUS_FAILURE_* es Fallo con el texto del sistema`() {
        listOf(
            PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE,
        ).forEach { status ->
            val fallos = mutableListOf<ResultadoInstalacion.Fallo>()
            val i = Intent().putExtra(PackageInstaller.EXTRA_STATUS, status)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: firmas distintas")
            atender(app, i) { fallos += it }
            assertEquals("status $status", listOf(ResultadoInstalacion.Fallo("INSTALL_FAILED_UPDATE_INCOMPATIBLE: firmas distintas")), fallos)
        }
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `un fallo sin texto del sistema dice su codigo`() {
        val fallos = mutableListOf<ResultadoInstalacion.Fallo>()
        atender(app, Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_STORAGE)) { fallos += it }
        assertEquals(1, fallos.size)
        assertTrue(fallos.single().motivo.contains("${PackageInstaller.STATUS_FAILURE_STORAGE}"))
    }

    @Test fun `STATUS_SUCCESS no es fallo ni lanza nada`() {
        atender(app, Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)) { fail("no es un fallo: $it") }
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
