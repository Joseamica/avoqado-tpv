package com.jaac.avoqado_campo.venta

import org.junit.Assert.assertEquals
import org.junit.Test

class IccidTest {
    @Test fun `canonica quita los espacios y pasa a mayusculas`() {
        assertEquals("8952020012345678901F", Iccid.canonica("8952 0200 1234 5678 901f"))
        assertEquals("8952020012345678901F", Iccid.canonica(" 8952020012345678901f\n"))
    }
}
