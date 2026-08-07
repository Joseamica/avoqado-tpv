package com.jaac.avoqado_tpv.features.payment.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO for the [AuthAttemptEntity] durability journal. Only ever touched by
 * [AuthAttemptTelemetryStore], and only fire-and-forget from there — a slow or failing
 * disk write must never delay or fail an authorization. See that class's KDoc for the
 * full read/write pattern (in-memory buffer is the source of truth at runtime; this
 * table exists purely so a process death doesn't silently drop an attempt that hasn't
 * ridden a heartbeat yet).
 */
@Dao
interface AuthAttemptDao {

    @Insert
    suspend fun insert(entity: AuthAttemptEntity): Long

    /** Oldest first — mirrors the in-memory buffer's FIFO order on reload. */
    @Query("SELECT * FROM auth_attempts ORDER BY id ASC")
    suspend fun getAll(): List<AuthAttemptEntity>

    /** Called after a successful drain (batch handed to the heartbeat request). */
    @Query("DELETE FROM auth_attempts")
    suspend fun clear()
}
