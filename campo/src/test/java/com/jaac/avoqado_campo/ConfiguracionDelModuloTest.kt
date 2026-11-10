package com.jaac.avoqado_campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguracionDelModuloTest {
    @Test fun `la app de campo tiene su propio id`() {
        assertEquals("com.jaac.avoqado_tpv.campo", BuildConfig.APPLICATION_ID)
    }

    @Test fun `el almacenamiento sigue al tipo de build`() {
        assertEquals(if (BuildConfig.DEBUG) "dev" else "prod", BuildConfig.STORAGE_ENV)
    }

    @Test fun `la URL del servidor termina en api v1`() {
        assertTrue(BuildConfig.API_BASE_URL.endsWith("/api/v1/"))
    }

    @Test fun `la version es la 2`() {
        assertEquals(2, BuildConfig.VERSION_CODE)
    }
}
