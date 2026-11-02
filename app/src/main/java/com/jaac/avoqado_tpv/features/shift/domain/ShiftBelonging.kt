package com.jaac.avoqado_tpv.features.shift.domain

/** Pertenencia capturada al admitir, nunca recalculada al reproducir ni deducida de un shiftId legacy. */
data class ShiftBelonging(
    val kind: String = "UNKNOWN",
    val localSessionId: String? = null,
    val serverShiftId: String? = null,
    val cashDrawerSessionId: String? = null,
) {
    companion object {
        fun fromStored(kind: String?, localSessionId: String?, serverShiftId: String?, cashDrawerSessionId: String?): ShiftBelonging = when {
            kind == "MANAGED" && !localSessionId.isNullOrBlank() -> ShiftBelonging(kind, localSessionId, serverShiftId, cashDrawerSessionId)
            kind == "NO_SHIFT_SYSTEM" && localSessionId == null && cashDrawerSessionId == null && serverShiftId == null -> ShiftBelonging(kind)
            else -> ShiftBelonging()
        }
    }
}
