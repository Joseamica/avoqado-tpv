package com.jaac.avoqado_tpv.features.permissions.presentation.override

import org.junit.Assert.assertEquals
import org.junit.Test

class PinDelDialogoTest {
    private fun p(id: Long = 1, error: String? = null, enviando: Boolean = false) = PromptDePin(id, "m", error, enviando)

    @Test fun cambio_de_id_borra() = assertEquals("", pinTrasCambio("1234", p(1), p(2)))
    @Test fun error_nuevo_borra() = assertEquals("", pinTrasCambio("1234", p(error = null), p(error = "PIN incorrecto")))
    @Test fun cierre_borra() = assertEquals("", pinTrasCambio("1234", p(), null))
    @Test fun mismo_prompt_conserva() = assertEquals("1234", pinTrasCambio("1234", p(), p()))
    @Test fun enviando_conserva() = assertEquals("1234", pinTrasCambio("1234", p(), p(enviando = true)))
}
