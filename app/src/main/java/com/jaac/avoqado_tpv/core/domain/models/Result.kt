package com.jaac.avoqado_tpv.core.domain.models

/**
 * Result wrapper for API operations
 *
 * Provides type-safe error handling without exceptions
 *
 * Usage:
 * ```kotlin
 * suspend fun getOrders(): Result<List<Order>> {
 *     return try {
 *         val response = apiService.getOrders()
 *         if (response.isSuccessful && response.body() != null) {
 *             Result.Success(response.body()!!)
 *         } else {
 *             Result.Error(ApiException.HttpError(response.code(), response.message()))
 *         }
 *     } catch (e: Exception) {
 *         Result.Error(ApiException.NetworkError(e))
 *     }
 * }
 *
 * // In ViewModel
 * when (val result = repository.getOrders()) {
 *     is Result.Success -> updateUi(result.data)
 *     is Result.Error -> showError(result.exception.userMessage)
 * }
 * ```
 */
sealed class Result<out T> {
    /**
     * Success with data
     */
    data class Success<T>(val data: T) : Result<T>()

    /**
     * Error with exception details
     */
    data class Error(val exception: ApiException) : Result<Nothing>()

    /**
     * Check if result is success
     */
    val isSuccess: Boolean
        get() = this is Success

    /**
     * Check if result is error
     */
    val isError: Boolean
        get() = this is Error

    /**
     * Get data or null
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    /**
     * Get data or throw exception
     */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception
    }

    /**
     * Map success data to another type
     */
    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    /**
     * Execute action on success
     */
    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) {
            action(data)
        }
        return this
    }

    /**
     * Execute action on error
     */
    inline fun onError(action: (ApiException) -> Unit): Result<T> {
        if (this is Error) {
            action(exception)
        }
        return this
    }
}

/**
 * API Exception types
 *
 * Categorizes different types of API errors with user-friendly messages
 */
sealed class ApiException(
    override val message: String,
    val userMessage: String
) : Exception(message) {

    /**
     * HTTP error (4xx, 5xx)
     *
     * @param code HTTP status code
     * @param errorMessage Technical error message for logging
     * @param customUserMessage Optional custom user-friendly message (from backend). If null, uses generic message based on code.
     */
    data class HttpError(
        val code: Int,
        val errorMessage: String,
        val customUserMessage: String? = null
    ) : ApiException(
        message = "HTTP $code: $errorMessage",
        userMessage = customUserMessage ?: when (code) {
            400 -> "Solicitud inválida. Por favor intente nuevamente."
            401 -> "Sesión expirada. Por favor inicie sesión nuevamente."
            403 -> "No tiene permisos para realizar esta acción."
            404 -> "Recurso no encontrado."
            408 -> "Tiempo de espera agotado. Verifique su conexión."
            429 -> "Demasiadas solicitudes. Por favor espere un momento."
            in 500..599 -> "Error del servidor. Por favor intente más tarde."
            else -> "Error de conexión. Por favor intente nuevamente."
        }
    )

    /**
     * Network error (no internet, timeout)
     */
    data class NetworkError(
        override val cause: Throwable
    ) : ApiException(
        message = "Network error: ${cause.message}",
        userMessage = "Error de conexión. Verifique su internet e intente nuevamente."
    )

    /**
     * Parsing error (malformed JSON)
     */
    data class ParseError(
        override val cause: Throwable
    ) : ApiException(
        message = "Parse error: ${cause.message}",
        userMessage = "Error al procesar respuesta del servidor."
    )

    /**
     * Unknown error
     */
    data class Unknown(
        override val cause: Throwable
    ) : ApiException(
        message = "Unknown error: ${cause.message}",
        userMessage = "Error desconocido. Por favor intente nuevamente."
    )

    /**
     * Session expired (401)
     */
    object SessionExpired : ApiException(
        message = "Session expired",
        userMessage = "Su sesión ha expirado. Por favor inicie sesión nuevamente."
    )

    /**
     * Permission denied (403)
     */
    data class PermissionDenied(
        val requiredPermission: String? = null
    ) : ApiException(
        message = "Permission denied: $requiredPermission",
        userMessage = "No tiene permisos para realizar esta acción."
    )

    /**
     * Validation error (client-side validation failure)
     *
     * Used for local validation errors that don't require a network call.
     * Examples:
     * - Terminal not activated
     * - Missing required fields
     * - Invalid input format
     */
    data class ValidationError(
        val errorMessage: String
    ) : ApiException(
        message = "Validation error: $errorMessage",
        userMessage = errorMessage
    )
}
