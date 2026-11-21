package com.avoqado.cobropropio.recorrido

import com.avoqado.cobropropio.blumon.ClienteBlumon
import com.avoqado.cobropropio.blumon.CredencialDelCliente
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.red.Endpoints
import com.avoqado.cobropropio.red.EnvioUnico
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

/**
 * El recorrido real contra el SANDBOX de Blumon: token, identidad, llave pública e IPEK con su KCV. No vende.
 *
 * Se salta sin `COBRO_PROPIO_SANDBOX_FILE`: un `.properties` fuera de git (permisos 600) con `usuario`, `contrasena`,
 * `serie`, `posId` y `comercio`. Con `modo=descubrir` no compara la identidad: escribe `<archivo>.descubierto` (600) con el
 * `posId` y el comercio que devolvió `device/init`, si el KCV cuadró y la base KSN canónica. Nunca escribe ni imprime la
 * credencial, el token, la TK ni la IPEK.
 */
class SesionSandboxTest {

    @Test fun `sesion real en el sandbox con KCV que cuadra`() {
        val ruta = System.getenv("COBRO_PROPIO_SANDBOX_FILE")
        assumeTrue("sin COBRO_PROPIO_SANDBOX_FILE", !ruta.isNullOrBlank() && File(ruta).isFile)
        val p = Properties().apply { File(ruta!!).inputStream().use { load(it) } }
        val serie = p.getProperty("serie").orEmpty()
        val identidad = Identidad(
            comercio = p.getProperty("comercio").orEmpty(),
            posId = p.getProperty("posId").orEmpty(),
            serieFisica = serie,
            serieBlumon = serie,
            modelo = "A910S",
            versionSdk = "cobropropio",
            ambiente = Ambiente.SANDBOX,
        )
        val endpoints = Endpoints.SANDBOX
        val cliente = ClienteBlumon(
            endpoints,
            EnvioUnico(endpoints) { System.nanoTime() / 1_000_000 },
            CredencialDelCliente(p.getProperty("usuario").orEmpty(), p.getProperty("contrasena").orEmpty()),
            identidad,
            SecureRandom(),
        )

        if (p.getProperty("modo") == "descubrir") {
            // No compara la identidad ni falla: guarda lo descubierto junto al archivo, porque avq-verify sólo conserva totales.
            val d = runBlocking { cliente.descubrir(30.seconds) }
            val texto = "posId=${d?.posId}\ncomercio=${d?.comercio}\nkcvCuadra=${d?.kcvCuadra == true}\nbaseKsn=${d?.baseKsn}\n"
            val destino = Paths.get("$ruta.descubierto")
            Files.deleteIfExists(destino)
            Files.createFile(destino, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.write(destino, texto.toByteArray(Charsets.UTF_8))
            println(texto)
            return
        }

        val sesion = runBlocking { cliente.iniciarSesion(15.seconds) }
        assertNotNull("sin sesion: identidad, qps, KCV o espera (no se imprime cuál)", sesion)
        println("sesion del sandbox con KCV verificado")
    }
}
