package com.jaac.avoqado_tpv.features.shift.data

import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.ShiftBelonging
import io.mockk.*

/** Fixtures antiguos del registrador/SDK: su frontera de Caja se aísla aquí; no acreditan la cerca ni RoomTx. */
fun legacyShiftBoundaryFixture(): ShiftRepository = mockk<ShiftRepository>(relaxed = true).also { repo ->
    coEvery { repo.guardarNuevaObligacionDeCaja<Any?>(any(), any(), any(), any()) } coAnswers {
        arg<suspend (ShiftBelonging) -> Any?>(3).invoke(ShiftBelonging())
    }
    coEvery { repo.restaurarContextoDelIntento(any()) } answers { firstArg() }
}

/** Conserva las negativas/errores explícitos de las pruebas SDK anteriores; no fabrica permiso true. */
fun bridgeLegacyLedgerFixture(ledger: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger) {
    if (!isMockKMock(ledger)) return
    coEvery { ledger.openAttemptForShift(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
        ledger.openAttempt(arg(1), arg(2), arg(3), arg(4), arg(5), arg(6), arg(7), arg(8))
    }
}

fun bridgeLegacyShiftFixture(repo: ShiftRepository) {
    if (!isMockKMock(repo)) return
    coEvery { repo.restaurarContextoDelIntento(any()) } answers { firstArg() }
    coEvery { repo.guardarNuevaObligacionDeCaja<Any?>(any(), any(), any(), any()) } coAnswers {
        arg<suspend (ShiftBelonging) -> Any?>(3).invoke(ShiftBelonging())
    }
}
