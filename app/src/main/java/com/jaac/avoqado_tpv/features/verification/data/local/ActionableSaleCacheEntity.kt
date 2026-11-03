package com.jaac.avoqado_tpv.features.verification.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lista guardada de ventas con acción pendiente (foto/número), para verla sin señal. */
@Entity(tableName = "actionable_sale_cache", indices = [Index("venue_id"), Index("staff_id")])
data class ActionableSaleCacheEntity(
    @PrimaryKey @ColumnInfo(name = "payment_id") val paymentId: String,
    @ColumnInfo(name = "venue_id") val venueId: String,
    @ColumnInfo(name = "staff_id") val staffId: String,
    @ColumnInfo(name = "verification_id") val verificationId: String?,
    val json: String,
    @ColumnInfo(name = "delivered_slots", defaultValue = "''") val deliveredSlots: String, // "VINCULACION,PORTABILIDAD" entregadas desde esta terminal
    @ColumnInfo(name = "local_only", defaultValue = "0") val localOnly: Boolean,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
)
