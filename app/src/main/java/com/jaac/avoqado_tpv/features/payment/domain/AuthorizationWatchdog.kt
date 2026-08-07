package com.jaac.avoqado_tpv.features.payment.domain

/**
 * Traduce cuánto lleva la autorización en qué avisarle al cajero.
 *
 * 🔴 Esto NO cancela nada. La autorización del SDK sigue viva hasta que
 * responda: si el procesador aprobó y nosotros abandonáramos, habría dinero
 * movido que la app no conoce. Este archivo sólo decide texto de pantalla.
 */
enum class AuthWatchdogLevel { NONE, SLOW, VERY_SLOW }

/** A los 8s el cajero ya cree que se colgó. */
const val AUTH_SLOW_THRESHOLD_MS = 8_000L

/** A los 25s hay que decirle explícitamente que NO vuelva a cobrar. */
const val AUTH_VERY_SLOW_THRESHOLD_MS = 25_000L

fun authWatchdogLevel(elapsedMillis: Long): AuthWatchdogLevel = when {
    elapsedMillis >= AUTH_VERY_SLOW_THRESHOLD_MS -> AuthWatchdogLevel.VERY_SLOW
    elapsedMillis >= AUTH_SLOW_THRESHOLD_MS -> AuthWatchdogLevel.SLOW
    else -> AuthWatchdogLevel.NONE
}
