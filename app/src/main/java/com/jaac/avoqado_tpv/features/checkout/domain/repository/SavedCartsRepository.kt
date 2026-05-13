package com.jaac.avoqado_tpv.features.checkout.domain.repository

import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedCart
import kotlinx.coroutines.flow.Flow

/**
 * Local persistence for "saved carts" — carts that the operator has set aside
 * to take the next customer, intended to be resumed later.
 *
 * Distinct from `OrderRepository`/`DraftOrder`: those represent in-flight
 * orders that may already be in the kitchen. A SavedCart is purely local,
 * never reaches the backend, and is venue-scoped (one operator's saved carts
 * are not visible to another terminal).
 */
interface SavedCartsRepository {
    /** Observe all saved carts for the current venue, newest first. */
    fun observeAll(): Flow<List<SavedCart>>

    /** Synchronous snapshot — useful for one-off lookups inside ViewModels. */
    suspend fun getAll(): List<SavedCart>

    suspend fun save(cart: SavedCart)

    suspend fun delete(id: String)

    suspend fun clear()
}
