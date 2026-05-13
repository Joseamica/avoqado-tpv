package com.jaac.avoqado_tpv.features.checkout.domain.repository

import com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for venue-scoped Checkout shortcuts.
 *
 * Shortcuts are stored in Room (`MosaicShortcutEntity`) so they survive app
 * restarts and persist per-venue. Backed by a Flow so the Shortcuts grid
 * stays in sync with edits made in the Configurar tab live.
 */
interface MosaicRepository {

    /** Observe shortcuts for [venueId] ordered by position ascending. */
    fun observe(venueId: String): Flow<List<MosaicShortcut>>

    /** Synchronous snapshot. */
    suspend fun get(venueId: String): List<MosaicShortcut>

    /** Upsert a single shortcut (used when the user assigns a slot). */
    suspend fun upsert(shortcut: MosaicShortcut)

    /** Bulk replace — useful when the operator drags slots to reorder. */
    suspend fun replaceAll(venueId: String, shortcuts: List<MosaicShortcut>)

    /** Remove a single shortcut by id. */
    suspend fun delete(id: String)

    /** Wipe all shortcuts for the venue (e.g. venue switch). */
    suspend fun clearVenue(venueId: String)
}
