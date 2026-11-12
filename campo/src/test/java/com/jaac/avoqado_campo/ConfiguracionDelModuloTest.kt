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

    @Test fun `la version es la 4 (0_4_0)`() {
        assertEquals(4, BuildConfig.VERSION_CODE)
        assertTrue(BuildConfig.VERSION_NAME.startsWith("0.4.0"))
    }

    @Test fun `el carril de versiones sigue al versionName`() {
        // debug = «0.4.0-sandbox» → SANDBOX; release = «0.4.0» → PRODUCTION (como decide el alta del SERVIDOR).
        assertEquals(if (BuildConfig.VERSION_NAME.contains("sandbox")) "SANDBOX" else "PRODUCTION", BuildConfig.AMBIENTE_DE_VERSIONES)
    }
}
