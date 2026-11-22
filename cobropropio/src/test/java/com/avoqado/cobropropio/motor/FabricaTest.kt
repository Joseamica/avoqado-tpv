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

    /** Un campo privado de lo armado. Sólo pruebas: así se ve lo que la fábrica ARMA sin abrir nada en producción. */
    private fun Any.campo(nombre: String): Any? = javaClass.getDeclaredField(nombre).apply { isAccessible = true }.get(this)

    /**
     * C2 (Codex b1 r1, + m4 de la revisión final): lo que la fábrica ARMA, en los dos ambientes. El cliente de Blumon (y su
     * envío) lleva los endpoints de ESE ambiente y la MISMA identidad que el motor; el cliente del SERVIDOR, los pines que se
     * pasaron. Reemplaza a la prueba suelta de `endpointsDe`, que llamaba a la función aparte y no veía lo armado.
     */
    @Test fun `la fabrica arma blumon y el servidor con lo de su ambiente`() {
        for ((ambiente, esperados) in listOf(Ambiente.SANDBOX to Endpoints.SANDBOX, Ambiente.PRODUCCION to Endpoints.PRODUCCION)) {
            val pines = pinesDe("api.ejemplo.invalid")
            val m = crear(ambiente = ambiente, pinner = pines)
            val blumon = m.blumon as ClienteBlumon
            assertEquals(ambiente.name, esperados, blumon.campo("endpoints"))
            assertEquals(ambiente.name, esperados, blumon.campo("envio")!!.campo("endpoints"))
            val delMotor = m.campo("identidad") as Identidad
            assertEquals(ambiente, delMotor.ambiente)
            assertSame("la misma identidad en el motor y en Blumon", delMotor, blumon.campo("identidad"))
            assertEquals(ambiente.name, pines.pins, (m.servidor as ReversoDelServidorHttp).cliente.certificatePinner.pins)
            Libreta.abrir(context).cerrar() // una fábrica por proceso: la siguiente vuelta abre otra libreta
        }
    }

    /** C2: lo que la fábrica rechaza no llega a la libreta: cero accesos a la base o a su directorio. */
    @Test fun `una url rechazada no abre la libreta`() {
        var accesos = 0
        val contando = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = context.applicationContext.also { accesos++ }
            override fun getNoBackupFilesDir(): File = context.noBackupFilesDir.also { accesos++ }
            override fun getDatabasePath(name: String): File = context.getDatabasePath(name).also { accesos++ }
        }
        assertThrows(IllegalArgumentException::class.java) {
            crear(contexto = contando, ambiente = Ambiente.PRODUCCION, url = "http://api.ejemplo.invalid/")
        }
        assertThrows(IllegalArgumentException::class.java) { crear(contexto = contando, pinner = pinesDe("otro.ejemplo.invalid")) }
        assertThrows(IllegalArgumentException::class.java) {
            crear(contexto = contando, url = "http://api.ejemplo.invalid/", pinner = pinesDe("api.ejemplo.invalid"))
        }
        assertEquals("ninguna rechazada tocó la base", 0, accesos)
        crear(contexto = contando)
        assertTrue("la que sí se arma abre la libreta por ese contexto", accesos > 0)
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
