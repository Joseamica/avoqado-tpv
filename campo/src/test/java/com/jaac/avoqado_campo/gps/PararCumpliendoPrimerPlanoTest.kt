package com.jaac.avoqado_campo.gps

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** r13: con el arranque DETENIDO, el servicio cumple `startForeground` antes de pararse (Android lo exige). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PararCumpliendoPrimerPlanoTest {
    @Test fun `pone el servicio en primer plano, lo quita y despues se para, en ese orden`() {
        val pasos = mutableListOf<String>()
        pararCumpliendoPrimerPlano({ pasos += "primerPlano" }, { pasos += "quitar" }) { pasos += "parar" }
        assertEquals(listOf("primerPlano", "quitar", "parar"), pasos)
    }

    @Test fun `si Android no deja ponerlo en primer plano, igual se quita y se para sin tronar`() {
        val pasos = mutableListOf<String>()
        pararCumpliendoPrimerPlano({ throw SecurityException("sin permiso") }, { pasos += "quitar" }) { pasos += "parar" }
        assertEquals(listOf("quitar", "parar"), pasos)
    }
}
