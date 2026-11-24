package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.tables.data.CAMBIOS_SIN_SINCRONIZAR
import com.jaac.avoqado_tpv.features.tables.data.MESA_COBRO_POR_REVISAR
import com.jaac.avoqado_tpv.features.tables.data.MESA_COBRO_SIN_SINCRONIZAR
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Encargo f3cba0: un cobro que manda el POS para una cuenta que ESTA terminal ya cobró y que todavía no sube a Avoqado
 * (efectivo en el cajón con su fila en la cola, el efectivo de Mesas en la suya, o un registro rechazado sin revisar) no se
 * abre: el SERVIDOR aún la ve «por pagar» y abrirlo sería cobrarle dos veces al cliente.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CuentaCobradaSinSubirTest {

    private val cola = mockk<PendingPaymentDao>()
    private val mesasLibre: suspend (String, String) -> String? = { _, _ -> null }

    @Test
    fun `sin cuenta (pago rápido) no se revisa ninguna cola y no se frena`() = runBlocking {
        var mesasLeida = false
        assertThat(CuentaCobradaSinSubir.motivo(orderId = null, venueId = "v1", cola = cola, mesas = { _, _ -> mesasLeida = true; null })).isNull()
        coVerify(exactly = 0) { cola.hayCobroSinSubir(any(), any()) }
        assertThat(mesasLeida).isFalse()
    }

    @Test
    fun `cuenta sin nada pendiente en esta terminal se admite`() = runBlocking {
        coEvery { cola.hayCobroSinSubir("v1", "o1") } returns false
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola, mesas = mesasLibre)).isNull()
    }

    @Test
    fun `P1 cuenta con un cobro de esta terminal sin subir no se abre`() = runBlocking {
        coEvery { cola.hayCobroSinSubir("v1", "o1") } returns true
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola, mesas = mesasLibre))
            .isEqualTo(CuentaCobradaSinSubir.COBRO_SIN_SUBIR)
    }

    @Test
    fun `P1 efectivo de Mesas sin subir o por revisar tampoco deja abrir la cuenta`() = runBlocking {
        coEvery { cola.hayCobroSinSubir(any(), any()) } returns false
        for (respuesta in listOf(MESA_COBRO_SIN_SINCRONIZAR, MESA_COBRO_POR_REVISAR)) {
            assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola, mesas = { v, o ->
                assertThat(v to o).isEqualTo("v1" to "o1"); respuesta
            })).isEqualTo(CuentaCobradaSinSubir.COBRO_SIN_SUBIR)
        }
    }

    @Test
    fun `una ronda de Mesas sin subir no es dinero en el cajón y no cerca la cuenta`() = runBlocking {
        coEvery { cola.hayCobroSinSubir(any(), any()) } returns false
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola, mesas = { _, _ -> CAMBIOS_SIN_SINCRONIZAR })).isNull()
    }

    @Test
    fun `si una cola no se deja leer falla cerrado`() = runBlocking {
        coEvery { cola.hayCobroSinSubir(any(), any()) } throws IllegalStateException("disco")
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola, mesas = mesasLibre))
            .isEqualTo(CuentaCobradaSinSubir.NO_SE_PUDO_REVISAR)
        coEvery { cola.hayCobroSinSubir(any(), any()) } returns false
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola, mesas = { _, _ -> error("disco") }))
            .isEqualTo(CuentaCobradaSinSubir.NO_SE_PUDO_REVISAR)
    }

    @Test
    fun `sin negocio en la sesión falla cerrado`() = runBlocking {
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = null, cola = cola, mesas = mesasLibre))
            .isEqualTo(CuentaCobradaSinSubir.NO_SE_PUDO_REVISAR)
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "", cola = cola, mesas = mesasLibre))
            .isEqualTo(CuentaCobradaSinSubir.NO_SE_PUDO_REVISAR)
    }

    @Test
    fun `los textos dicen que este cobro NO se inició y no mandan a una pantalla que puede estar escondida`() {
        listOf(CuentaCobradaSinSubir.COBRO_SIN_SUBIR, CuentaCobradaSinSubir.NO_SE_PUDO_REVISAR).forEach {
            assertThat(it).contains("NO se inició")
            assertThat(it).doesNotContain("Turnos de caja")
        }
    }

    /**
     * Codex r1 #1: con dos lecturas, una fila que pasaba de SYNCING a FAILED entre ellas no la veía ninguna. La consulta única, con
     * el SQL real de Room, cerca la cuenta en TODOS los estados de dinero sin subir y la suelta en los resueltos.
     */
    @Test
    fun `P1 la consulta única cerca la cuenta en cada estado sin subir, con Room de verdad`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.pendingPaymentDao()
            fun fila(orden: String, estado: String, revision: Boolean = false, reconocida: Boolean = false, venue: String = "v1") =
                runBlocking {
                    dao.insert(
                        PendingPaymentEntity(
                            referenceNumber = "ref-$orden-$venue", venueId = venue, staffId = "seller", amount = "100.00", tip = "0.00",
                            rating = null, merchantAccountId = "", blumonSerialNumber = "", maskedPan = null, cardBrand = null,
                            entryMode = "CASH", isInternational = false, authorizationNumber = "EFECTIVO", createdAt = 100L,
                            orderId = orden, syncStatus = estado, requiereRevision = revision, acknowledged = reconocida,
                        ),
                    )
                }
            fila("subiendo", "PENDING"); fila("reclamada", "SYNCING"); fila("rechazada", "FAILED")
            fila("revision", "SUCCESS", revision = true); fila("revisada", "FAILED", reconocida = true); fila("subida", "SUCCESS")
            fila("otro-negocio", "PENDING", venue = "v2")
            for (orden in listOf("subiendo", "reclamada", "rechazada", "revision")) assertThat(dao.hayCobroSinSubir("v1", orden)).isTrue()
            for (orden in listOf("revisada", "subida", "otro-negocio", "nunca")) assertThat(dao.hayCobroSinSubir("v1", orden)).isFalse()
        } finally {
            db.close()
        }
    }

    /** El cableado: la admisión del cobro del POS en la navegación pregunta esto ANTES de abrir la pantalla de cobro. */
    @Test
    fun `la navegación revisa la cuenta antes de abrir un cobro del POS`() {
        val fuente = listOf(
            File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt"),
            File("app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt"),
        ).first { it.exists() }.readText()
        val admision = fuente.substringAfter("val motivoParaNoIniciar = CobroRemotoDelPos.motivoParaNoIniciar(")
            .substringBefore("if (motivoParaNoIniciar != null)")
        assertThat(admision).contains("?: cobroConSaldo.motivoParaNoCobrarRemoto(request.orderId)")
        assertThat(admision).contains("?: CuentaCobradaSinSubir.motivo(")
        assertThat(admision).contains("orderId = request.orderId")
        assertThat(admision).contains("cola = colaDeCobros")
        assertThat(admision).contains("mesas = { venue, cuenta -> colaDeMesas.pendienteDeSubir(venue, cuenta) }")
        // Y la pantalla de cobro se abre DESPUÉS de esa revisión, nunca antes.
        val antesDeLaRevision = fuente.substringBefore("val motivoParaNoIniciar = CobroRemotoDelPos.motivoParaNoIniciar(")
            .substringAfter("remotePaymentCoordinator.paymentRequests.collect")
        assertThat(antesDeLaRevision).doesNotContain("handle.set(\"initialAmount\"")
    }
}
