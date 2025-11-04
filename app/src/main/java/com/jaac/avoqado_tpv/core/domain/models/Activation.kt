package com.jaac.avoqado_tpv.core.domain.models

/**
 * Activation domain models
 *
 * Pure business logic models without Android dependencies.
 * Used in UseCases and ViewModels.
 */

/**
 * Terminal activation result
 *
 * Contains venue information after successful activation.
 * This data is stored permanently in SecureStorage.
 *
 * @param venueId Venue UUID (critical for tenant isolation)
 * @param terminalId Terminal UUID
 * @param venueName Human-readable venue name
 * @param venueSlug URL-friendly venue identifier
 * @param activatedAt ISO 8601 timestamp of activation
 */
data class ActivationResult(
    val venueId: String,
    val terminalId: String,
    val venueName: String,
    val venueSlug: String,
    val activatedAt: String
)

/**
 * Activation error types
 *
 * Business-specific errors for activation flow
 */
sealed class ActivationError(
    override val message: String,
    val userMessage: String
) : Exception(message) {

    /**
     * Invalid activation code (wrong code entered)
     */
    data class InvalidCode(
        val attemptsRemaining: Int
    ) : ActivationError(
        message = "Invalid activation code. $attemptsRemaining attempts remaining.",
        userMessage = "Código de activación incorrecto. $attemptsRemaining intento(s) restantes."
    )

    /**
     * Terminal locked due to too many failed attempts
     */
    object TerminalLocked : ActivationError(
        message = "Terminal locked due to too many failed attempts.",
        userMessage = "Terminal bloqueado por demasiados intentos fallidos. Contacte soporte."
    )

    /**
     * Activation code expired (>7 days old)
     */
    object CodeExpired : ActivationError(
        message = "Activation code expired.",
        userMessage = "Código de activación expirado. Solicite uno nuevo desde el dashboard."
    )

    /**
     * Terminal already activated
     */
    object AlreadyActivated : ActivationError(
        message = "Terminal already activated.",
        userMessage = "Este terminal ya está activado."
    )

    /**
     * Terminal not registered in database
     */
    object TerminalNotRegistered : ActivationError(
        message = "Terminal not registered.",
        userMessage = "Terminal no registrado. Contacte al administrador."
    )

    /**
     * Network error during activation
     */
    data class NetworkError(
        override val cause: Throwable
    ) : ActivationError(
        message = "Network error: ${cause.message}",
        userMessage = "Error de conexión. Verifique su internet e intente nuevamente."
    )

    /**
     * Unknown error
     */
    data class Unknown(
        override val cause: Throwable
    ) : ActivationError(
        message = "Unknown error: ${cause.message}",
        userMessage = "Error desconocido. Por favor intente nuevamente."
    )
}
