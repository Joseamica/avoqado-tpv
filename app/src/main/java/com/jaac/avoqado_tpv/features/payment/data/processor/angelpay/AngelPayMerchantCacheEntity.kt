package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room cache for AngelPay user merchants — mirrors SDK 1.0.5 `MerchantSummary`
 * (returned by `AngelPaySdkGateway.getUserMerchants()`).
 *
 * Persisted so the merchant switcher UI renders instantly on app cold start
 * before the periodic refresh (D6 — spec §6.6, §18.5) re-fetches the live list.
 *
 * SAFE TO PERSIST: no PII beyond what the SDK already returns publicly to the
 * authenticated session. PIN is NEVER persisted (§4.5b — handled by
 * AngelPayCredentialResolver in-memory only).
 */
@Entity(tableName = "angelpay_merchant_cache")
data class AngelPayMerchantCacheEntity(
    @PrimaryKey val merchantId: Int,
    val name: String,
    val affiliationNumber: String,
    val isActive: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
)
