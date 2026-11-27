package com.jaac.avoqado_tpv.features.payment.domain

import com.blumonpay.pax.shared.trans_process.domain.use_case.complete_emv_trans.CompleteEmvTransFailure
import com.paxsz.module.emv.process.entity.TransResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 D5 · Cobro simple (t2-decisiones #13): sólo el −11 tras el «aprobado» que se le pasó al kernel es un cobro (medido: el cargo se
 * queda). El SDK devuelve [CompleteEmvTransFailure.FailureSecondGenerate] para TODO código distinto de 0, así que el tipo solo no basta.
 */
class CierreDelChipTest {

    private fun fallo(codigo: Int) = CompleteEmvTransFailure.FailureSecondGenerate(TransResult(codigo))

    @Test fun `el menos 11 con el banco aprobado es un cobro`() {
        assertTrue(CierreDelChip.elCargoSeQueda(fallo(-11), "3030"))
    }

    @Test fun `otro codigo del cierre sigue en duda aunque sea el mismo tipo del SDK`() {
        for (codigo in listOf(-2, -8, -9, -10, -12, 1)) assertFalse("código $codigo", CierreDelChip.elCargoSeQueda(fallo(codigo), "3030"))
    }

    @Test fun `el menos 11 sin el aprobado del banco al kernel no es un cobro`() {
        assertFalse(CierreDelChip.elCargoSeQueda(fallo(-11), "3035"))
        assertFalse(CierreDelChip.elCargoSeQueda(fallo(-11), ""))
    }

    @Test fun `un fallo que no es del cierre no es un cobro`() {
        assertFalse(CierreDelChip.elCargoSeQueda(null, "3030"))
        assertFalse(CierreDelChip.elCargoSeQueda(RuntimeException("x"), "3030"))
    }
}
