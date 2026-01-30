package com.jaac.avoqado_tpv.core.bluetooth

/**
 * Authorization events emitted when a device is blocked or pending approval.
 */
data class AuthorizationEvent(
    val address: String?,
    val deviceId: String?,
    val message: String,
    val isError: Boolean = true
)
