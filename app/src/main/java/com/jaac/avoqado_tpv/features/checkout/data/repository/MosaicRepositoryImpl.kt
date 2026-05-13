package com.jaac.avoqado_tpv.features.checkout.data.repository

import com.jaac.avoqado_tpv.core.data.local.dao.MosaicShortcutDao
import com.jaac.avoqado_tpv.core.data.local.entities.MosaicShortcutEntity
import com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MosaicRepositoryImpl @Inject constructor(
    private val dao: MosaicShortcutDao,
) : MosaicRepository {

    override fun observe(venueId: String): Flow<List<MosaicShortcut>> =
        dao.observeForVenue(venueId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun get(venueId: String): List<MosaicShortcut> =
        dao.getForVenue(venueId).map { it.toDomain() }

    override suspend fun upsert(shortcut: MosaicShortcut) {
        dao.upsert(shortcut.toEntity())
    }

    override suspend fun replaceAll(venueId: String, shortcuts: List<MosaicShortcut>) {
        dao.clearVenue(venueId)
        if (shortcuts.isNotEmpty()) {
            dao.upsertAll(shortcuts.map { it.toEntity() })
        }
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun clearVenue(venueId: String) {
        dao.clearVenue(venueId)
    }
}

private fun MosaicShortcutEntity.toDomain() = MosaicShortcut(
    id = id,
    venueId = venueId,
    productId = productId,
    position = position,
    label = label,
    colorHex = colorHex,
    updatedAt = updatedAt,
)

private fun MosaicShortcut.toEntity() = MosaicShortcutEntity(
    id = id,
    venueId = venueId,
    productId = productId,
    position = position,
    label = label,
    colorHex = colorHex,
    updatedAt = updatedAt,
)
