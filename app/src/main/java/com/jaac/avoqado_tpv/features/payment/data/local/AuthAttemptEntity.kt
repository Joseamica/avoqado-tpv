package com.jaac.avoqado_tpv.features.payment.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for ONE card-authorization attempt on either rail — Blumon TPV (PAX) or
 * AngelPay (Nexgo). Durability backstop for [AuthAttemptTelemetryStore]'s in-memory
 * batch: a row lives here between the moment an authorization resolves and the moment
 * it either rides a heartbeat POST (and is cleared) or the in-memory buffer is
 * reloaded on the next cold start (a process death between the two must not silently
 * drop the attempt).
 *
 * **Privacy — by construction, not by convention.** This table has exactly four
 * columns and NONE of them can hold card data, an amount, or a reference number:
 * - [code]: a short result/error code (e.g. `"N400"`, `"S000"`, Blumon's exception
 *   class name) — never a free-text description (those can carry the cardholder name
 *   or a Blumon decline message with amount/reference embedded).
 * - [durationMs]: wall-clock duration of the authorization call.
 * - [rail]: `"BLUMON"` or `"ANGELPAY"`.
 * - [timestamp]: ISO-8601, when the attempt was recorded.
 *
 * See `AuthAttemptTelemetryTest` — "la telemetria no guarda datos de tarjeta ni
 * montos" scans a rendered record for `pan`/`card`/`amount`/`monto`/`reference` and
 * fails if any of those leak in. Never add a field to this entity that could fail
 * that scan.
 */
@Entity(tableName = "auth_attempts")
data class AuthAttemptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val code: String,
    val durationMs: Long,
    val rail: String,
    val timestamp: String,
)
