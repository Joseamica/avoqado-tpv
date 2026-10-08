package com.jaac.avoqado_tpv.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La alarma de memoria se mide contra el TOPE del heap de la app, no contra MB fijos: en la Nexgo el tope es 128 MB
 * (`dalvik.vm.heapgrowthlimit`) y con «≤ 100 MB libres» la alarma salía en cuanto la app usaba 28 MB (7-oct-2026).
 */
class MemoryInfoTest {

    @Test
    fun `Nexgo usando 30 MB de 128 no es memoria baja`() {
        assertFalse(MemoryInfo(totalMB = 128, usedMB = 30, freeMB = 98).isLow)
    }

    @Test
    fun `Nexgo con 15 por ciento o menos libre si es memoria baja`() {
        assertTrue(MemoryInfo(totalMB = 128, usedMB = 110, freeMB = 18).isLow)
        assertTrue(MemoryInfo(totalMB = 128, usedMB = 120, freeMB = 8).isLow)
    }

    @Test
    fun `PAX con heap de 256 sigue la misma proporcion`() {
        assertFalse(MemoryInfo(totalMB = 256, usedMB = 150, freeMB = 106).isLow)
        assertTrue(MemoryInfo(totalMB = 256, usedMB = 230, freeMB = 26).isLow)
    }

    @Test
    fun `sin lectura de memoria no se inventa una alarma`() {
        assertFalse(MemoryInfo(totalMB = -1, usedMB = -1, freeMB = -1).isLow)
        assertFalse(MemoryInfo(totalMB = -1, usedMB = -1, freeMB = -1).isTight)
    }

    @Test
    fun `aviso intermedio al quedar 30 por ciento o menos`() {
        assertFalse(MemoryInfo(totalMB = 128, usedMB = 30, freeMB = 98).isTight)
        assertTrue(MemoryInfo(totalMB = 128, usedMB = 95, freeMB = 33).isTight)
    }
}
