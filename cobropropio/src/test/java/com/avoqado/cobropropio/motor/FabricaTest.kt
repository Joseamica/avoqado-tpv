package com.avoqado.cobropropio.motor

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.avoqado.cobropropio.blumon.CatalogoDeRechazos
import com.avoqado.cobropropio.blumon.ClienteBlumon
import com.avoqado.cobropropio.blumon.CredencialDelCliente
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.LectorDeTarjeta
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Red
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.red.Endpoints
import com.avoqado.cobropropio.servidor.CredencialDelServidor
import com.avoqado.cobropropio.servidor.ReversoDelServidorHttp
import okhttp3.CertificatePinner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class FabricaTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val identidad = Identidad("COMERCIO DE PRUEBA", "9001", "0000000000", "90001", "A910S", "1", Ambiente.SANDBOX)
    private val lector = object : LectorDeTarjeta {
        override suspend fun leer(operacion: Operacion, importeCentavos: Long, espera: Long) = ResultadoDeLectura.Vencida
    }
    private val red = object : Red {
        override fun hayRed() = true
    }
    private val jwt = object : CredencialDelServidor {
        override fun vigente(): String? = null
        override suspend fun renovar(rechazado: String, espera: Duration): String? = null
    }

    private fun pinesDe(host: String) =
        CertificatePinner.Builder().add(host, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").build()

    private fun crear(
        contexto: Context = context,
        ambiente: Ambiente = Ambiente.SANDBOX,
        url: String = "https://api.ejemplo.invalid/",
        pinner: CertificatePinner? = null,
    ) = crearMotorDeCobro(
        contexto, identidad.copy(ambiente = ambiente), lector, red, CredencialDelCliente("usuario-de-prueba", "clave-de-prueba"),
        url, jwt, pinner, SecretKeySpec(ByteArray(32) { 5 }, "HmacSHA256"),
    )

    @After fun cerrar() = Libreta.abrir(context).cerrar()

    @Test fun `la fabrica arma el motor con la pila de produccion`() {
        val m = crear()
        assertEquals(Relojes(), m.relojes)
        assertSame(CatalogoDeRechazos.BLUMON, m.catalogo)
        assertSame(Libreta.abrir(context), m.libreta)
        assertTrue(m.blumon is ClienteBlumon)
        assertTrue(m.servidor is ReversoDelServidorHttp)
    }

    @Test fun `la fabrica elige los endpoints del ambiente`() {
        assertEquals(Endpoints.PRODUCCION, endpointsDe(Ambiente.PRODUCCION))
        assertEquals(Endpoints.SANDBOX, endpointsDe(Ambiente.SANDBOX))
    }

    /**
     * D6 (Codex r1 #8, r2 #8): https en producción; pines opcionales, pero si vienen, de ESE host y sobre https; lo inválido truena
     * ANTES de armar.
     */
    @Test fun `la fabrica exige https en produccion y pines del host si los trae`() {
        assertThrows(IllegalArgumentException::class.java) { crear(ambiente = Ambiente.PRODUCCION, url = "http://api.ejemplo.invalid/") }
        assertThrows(IllegalArgumentException::class.java) { crear(pinner = pinesDe("otro.ejemplo.invalid")) }
        assertThrows(IllegalArgumentException::class.java) { crear(url = "http://api.ejemplo.invalid/", pinner = pinesDe("api.ejemplo.invalid")) }
        crear(ambiente = Ambiente.PRODUCCION, pinner = pinesDe("api.ejemplo.invalid")) // las fallidas no armaron un motor
    }

    /** Revisión de la tarea 6: una identidad sin forma truena sin tomar el dueño; el reintento correcto arma el motor. */
    @Test fun `una identidad sin forma no toma el dueno de la libreta`() {
        assertThrows(IllegalArgumentException::class.java) {
            crearMotorDeCobro(
                context, identidad.copy(posId = "X"), lector, red, CredencialDelCliente("usuario-de-prueba", "clave-de-prueba"),
                "https://api.ejemplo.invalid/", jwt, null, SecretKeySpec(ByteArray(32) { 5 }, "HmacSHA256"),
            )
        }
        crear()
    }

    @Test fun `en sandbox acepta http y sin pines`() {
        crear(url = "http://127.0.0.1:3000/", pinner = null)
    }

    /** Codex r1 #7: otro `Context` (otro directorio) no abre otra libreta: la fábrica resuelve con `applicationContext`. */
    @Test fun `un segundo motor en el mismo proceso truena aunque llegue otro contexto`() {
        crear()
        assertThrows(IllegalStateException::class.java) { crear() }
        val otroDirectorio = carpeta.newFolder("otro")
        val otro = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = otroDirectorio
            override fun getApplicationContext(): Context = context.applicationContext
        }
        assertThrows(IllegalStateException::class.java) { crear(contexto = otro) }
    }
}
