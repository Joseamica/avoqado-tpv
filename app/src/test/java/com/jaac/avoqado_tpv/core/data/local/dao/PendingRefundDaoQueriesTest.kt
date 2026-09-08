package com.jaac.avoqado_tpv.core.data.local.dao

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guardia ESTÁTICA sobre el SQL de [PendingRefundDao]: Room no corre en la JVM, así que la única
 * forma de fijar el criterio de una consulta desde una prueba unitaria es leerla del fuente.
 *
 * Lo que se fija (founder, N86, 7-sep-2026): el banner del aparato cuenta lo rechazado que NADIE
 * ha reconocido — el mismo criterio que la barrera del cierre (`blockingForVenue`). Sin el
 * `acknowledged = 0`, una devolución rechazada y ya reconocida con «Ya lo vi» seguía en el banner
 * para siempre, con un «Reintentar» que no tenía nada que reintentar.
 */
class PendingRefundDaoQueriesTest {

    private fun fuente(): String {
        val ruta = "src/main/java/com/jaac/avoqado_tpv/core/data/local/dao/PendingRefundDao.kt"
        val archivo = listOf(File(ruta), File("app/$ruta")).firstOrNull { it.exists() }
            ?: error("No encuentro PendingRefundDao.kt desde ${File(".").absolutePath}")
        return archivo.readText()
    }

    private fun consultaDe(metodo: String): String {
        val src = fuente()
        val fin = src.indexOf("fun $metodo(")
        require(fin > 0) { "No existe $metodo en PendingRefundDao" }
        val inicio = src.lastIndexOf("@Query", fin)
        return src.substring(inicio, fin).replace(Regex("\\s+"), " ")
    }

    @Test
    fun `getFailedCount cuenta solo lo rechazado que nadie ha reconocido`() {
        val q = consultaDe("getFailedCount")
        assertThat(q).contains("sync_status = 'FAILED'")
        assertThat(q).contains("acknowledged = 0")
    }

    @Test
    fun `blockingForVenue y getFailedCount comparten el criterio de reconocido`() {
        assertThat(consultaDe("blockingForVenue")).contains("acknowledged = 0")
        assertThat(consultaDe("getFailedCount")).contains("acknowledged = 0")
    }
}
