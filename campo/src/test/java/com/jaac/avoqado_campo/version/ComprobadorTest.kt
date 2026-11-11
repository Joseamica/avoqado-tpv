package com.jaac.avoqado_campo.version

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

private const val PROPIO = "com.jaac.avoqado_tpv.campo"
private const val FIRMA = "aa11"

class ComprobadorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun archivo(): File = tmp.newFile("campo-4.apk").also { it.writeText("apk de prueba") }
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun v(sha: String, code: Int = 4) = VersionNueva(code, "0.3.1", "https://x/campo.apk", sha, 13, false, null)
    private fun comprobador(info: InfoDeArchivo?, propias: Set<String> = setOf(FIRMA)) = Comprobador(PROPIO, { propias }) { info }
    private val buena = InfoDeArchivo(PROPIO, 4, setOf(FIRMA))

    private fun Bajada.esMala() = assertTrue("esperaba Mala y fue $this", this is Bajada.Mala)

    @Test fun `firma igual, paquete propio y version anunciada es Lista`() {
        val f = archivo()
        assertEquals(Bajada.Lista(f), comprobador(buena).comprobar(f, v(sha(f))))
    }

    @Test fun `la huella se compara sin importar mayusculas`() {
        val f = archivo()
        assertEquals(Bajada.Lista(f), comprobador(buena).comprobar(f, v(sha(f).uppercase())))
    }

    @Test fun `sha distinto es Mala`() = comprobador(buena).comprobar(archivo(), v("00".repeat(32))).esMala()

    @Test fun `sha vacio es Mala`() = comprobador(buena).comprobar(archivo(), v("")).esMala()

    @Test fun `archivo ilegible es Mala`() { val f = archivo(); comprobador(null).comprobar(f, v(sha(f))).esMala() }

    @Test fun `paquete ajeno es Mala`() {
        val f = archivo()
        comprobador(buena.copy(paquete = "com.jaac.avoqado_tpv")).comprobar(f, v(sha(f))).esMala()
    }

    @Test fun `versionCode distinto al anunciado es Mala`() {
        val f = archivo()
        comprobador(buena.copy(versionCode = 5)).comprobar(f, v(sha(f), code = 4)).esMala()
    }

    @Test fun `firmas ausentes en el archivo es Mala`() {
        val f = archivo()
        comprobador(buena.copy(firmas = emptySet())).comprobar(f, v(sha(f))).esMala()
    }

    @Test fun `firmas ausentes en la app instalada es Mala`() {
        val f = archivo()
        comprobador(buena, propias = emptySet()).comprobar(f, v(sha(f))).esMala()
    }

    @Test fun `firma distinta es Mala`() {
        val f = archivo()
        comprobador(buena.copy(firmas = setOf("bb22"))).comprobar(f, v(sha(f))).esMala()
    }

    @Test fun `una firma de mas en el archivo es Mala`() {
        val f = archivo()
        comprobador(buena.copy(firmas = setOf(FIRMA, "bb22"))).comprobar(f, v(sha(f))).esMala()
    }
}

/**
 * El lector real con un APK firmado de verdad (v2+v3, `apksigner --min-sdk-version 27`, llave desechable «Prueba de campo»,
 * certificado SHA-256 42a1145491b5f4550053ef588dff01c25c9407560219d86658487dd3e5070b8a).
 *
 * Robolectric corre el `PackageManager` real de AOSP, pero NO puede verificar firmas en la JVM (el verificador v2/v3 usa
 * `java.nio.DirectByteBuffer`, que la JVM no le deja tocar: IllegalAccessError; y el v1 usa código nativo). Lo que sí
 * prueba aquí, con el código real de Android 9, es la razón de las banderas (Codex r2 #1). La lectura de la firma de punta
 * a punta se prueba en el aparato (Task 9: la versión buena se instala y una firmada con otra llave queda «Mala»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LecturaRealDelApkTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun apk(): File = tmp.newFile("campo-de-prueba-4.apk").also { f ->
        javaClass.classLoader!!.getResourceAsStream("version/campo-de-prueba-4.apk")!!.use { e -> f.outputStream().use { e.copyTo(it) } }
    }

    @Test fun `en Android 9, sin GET_SIGNATURES el sistema lee el archivo pero no junta sus certificados`() {
        val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
        val info = pm.getPackageArchiveInfo(apk().path, PackageManager.GET_SIGNING_CERTIFICATES)
        assertEquals("com.jaac.avoqado_tpv.campo", info!!.packageName)
        assertEquals(4L, PackageInfoCompat.getLongVersionCode(info))
        assertNull("Android 9 no junta certificados sin GET_SIGNATURES", info.signingInfo)
    }

    @Test fun `por eso el lector pide GET_SIGNATURES siempre, y GET_SIGNING_CERTIFICATES desde el 28`() {
        @Suppress("DEPRECATION") val firmas = PackageManager.GET_SIGNATURES
        (27..36).forEach { assertTrue("sdk $it", banderasDeFirma(it) and firmas != 0) }
        assertEquals(0, banderasDeFirma(27) and PackageManager.GET_SIGNING_CERTIFICATES)
        (28..36).forEach { assertTrue("sdk $it", banderasDeFirma(it) and PackageManager.GET_SIGNING_CERTIFICATES != 0) }
    }
}
