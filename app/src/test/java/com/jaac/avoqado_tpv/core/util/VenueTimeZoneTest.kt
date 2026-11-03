package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * QA física del 14-oct: la PAX A910S trae tzdata 2021a (y la Nexgo N86 un ICU de 2017). Los dos creen que México sigue con
 * horario de verano y la terminal pintaba e imprimía la hora +1 h de abril a octubre (ticket «10:44:25» de un cobro de las
 * 09:44). México lo abolió por ley en 2022: la zona del negocio se resuelve por la ley, no por las reglas del aparato.
 */
class VenueTimeZoneTest {
    @After fun limpiar() = VenueTimeZone.invalidateCache()

    private fun guardada(tz: String?): SecureStorage = mockk<SecureStorage>().also { every { it.getVenueTimezone() } returns tz }

    @Test fun `la zona del negocio en CDMX es un desfase fijo y no las reglas del aparato`() {
        assertEquals(ZoneOffset.ofHours(-6), VenueTimeZone.get(guardada("America/Mexico_City")))
    }

    @Test fun `zonas de Mexico sin horario de verano usan su desfase por ley`() {
        for ((id, horas) in listOf("America/Mexico_City" to -6, "America/Monterrey" to -6, "America/Merida" to -6,
            "America/Bahia_Banderas" to -6, "America/Chihuahua" to -6, "America/Cancun" to -5, "America/Mazatlan" to -7,
            "America/Hermosillo" to -7, "Mexico/General" to -6, "Mexico/BajaSur" to -7)) {
            assertEquals(id, ZoneOffset.ofHours(horas), VenueTimeZone.zona(id))
        }
    }

    @Test fun `la frontera sigue el horario de Estados Unidos`() {
        val julio = Instant.parse("2026-07-01T12:00:00Z"); val enero = Instant.parse("2026-01-15T12:00:00Z")
        for ((id, eeuu) in listOf("America/Tijuana" to "America/Los_Angeles", "America/Ciudad_Juarez" to "America/Denver",
            "America/Ojinaga" to "America/Chicago", "America/Matamoros" to "America/Chicago")) {
            val zona = VenueTimeZone.zona(id); val ref = ZoneId.of(eeuu)
            assertEquals(id, ref.rules.getOffset(julio), zona.rules.getOffset(julio))
            assertEquals(id, ref.rules.getOffset(enero), zona.rules.getOffset(enero))
        }
    }

    @Test fun `sin zona o con una invalida cae en la hora de Mexico`() {
        for (tz in listOf(null, "", "Marte/Base")) assertEquals(ZoneOffset.ofHours(-6), VenueTimeZone.get(guardada(tz)).also { VenueTimeZone.invalidateCache() })
    }

    @Test fun `una zona fuera de Mexico se respeta`() {
        assertEquals(ZoneId.of("Europe/Madrid"), VenueTimeZone.zona("Europe/Madrid"))
    }

    @Test fun `el ticket con SimpleDateFormat recibe un -06 00 fijo sin horario de verano`() {
        val tz = java.util.TimeZone.getTimeZone(VenueTimeZone.MEXICO)
        assertEquals(-6 * 3600 * 1000, tz.rawOffset)
        assertFalse(tz.useDaylightTime())
    }
    // Codex r1 (D10): la lectura de la zona vieja, ya en camino, volvía a guardarla DESPUÉS de que la config nueva invalidó.
    @Test(timeout = 10_000) fun `una lectura vieja en camino no le gana a la invalidacion`() {
        var zonaGuardada = "America/Mexico_City"
        val leyendo = java.util.concurrent.CountDownLatch(1)
        val suelta = java.util.concurrent.CountDownLatch(1)
        val storage = mockk<SecureStorage>()
        every { storage.getVenueTimezone() } answers {
            val leida = zonaGuardada
            leyendo.countDown()
            suelta.await()
            leida
        }
        val lector = kotlin.concurrent.thread { VenueTimeZone.get(storage) }
        leyendo.await()
        zonaGuardada = "America/Tijuana"
        val invalidador = kotlin.concurrent.thread { VenueTimeZone.invalidateCache() }
        invalidador.join(300) // sin el candado termina aquí, antes que el lector; con él, espera al lector
        suelta.countDown()
        lector.join()
        invalidador.join()

        assertEquals(VenueTimeZone.zona("America/Tijuana"), VenueTimeZone.get(storage))
    }
}
