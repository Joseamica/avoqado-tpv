package com.jaac.avoqado_tpv.features.payment.domain

import com.blumonpay.pax.shared.trans_process.domain.use_case.complete_emv_trans.CompleteEmvTransFailure

/**
 * 🔴 D5 · Cobro simple (t2-decisiones #13, 26-nov): el banco APROBÓ y el chip rechazó el cierre con −11 (EMV_DENIAL: el segundo
 * GENERATE AC salió AAC). Medido en el portal de Blumon TPV (operaciones del 16-nov, sin reverso al 26-nov): el SDK no reversa y el
 * cargo SE QUEDA ⇒ es un cobro, y se registra como cualquier aprobado.
 *
 * El SDK devuelve [CompleteEmvTransFailure.FailureSecondGenerate] para TODO `resultCode != 0` (bytecode de
 * `TransProcessRepositoryImpl.completeEmvTrans`, sandbox 1.6.1.2 y prod): por eso se mira el −11 exacto, que es lo medido, y que al
 * kernel se le pasó el «aprobado» del banco (`3030`). Cualquier otro caso sigue en la duda de siempre.
 */
object CierreDelChip {
    private const val RECHAZO_DEL_CHIP = -11
    private const val APROBADO_DEL_BANCO = "3030"

    fun elCargoSeQueda(error: Any?, codigoDelBancoAlKernel: String): Boolean =
        codigoDelBancoAlKernel == APROBADO_DEL_BANCO &&
            (error as? CompleteEmvTransFailure.FailureSecondGenerate)?.transResult?.resultCode == RECHAZO_DEL_CHIP
}
