package com.jaac.avoqado_tpv.core.bluetooth

data class PairingEvent(
    val address: String?,
    val variant: Int,
    val key: Int
)
