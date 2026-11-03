package com.jaac.avoqado_tpv.features.shift.data

import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.*

/** Sólo sesión/config y transporte son fixtures; admisión, transacción y DAOs son los de producto. */
fun task5RoomShiftRepository(db: AvoqadoDatabase, storage: SecureStorage, api: ApiService,
    venue: String, staff: String, enabled: Boolean = false,
    consultarAntesDeCerrar: suspend (String, List<com.jaac.avoqado_tpv.features.shift.domain.LocalShiftObligation>) -> Boolean = { _, _ -> false }): ShiftRepository {
    every { storage.getVenueId() } returns venue
    every { storage.getStaffId() } returns staff
    every { storage.getToken() } returns "fixture-token"
    every { storage.getKioskVenueId() } returns null
    every { storage.contains("enable_shifts") } returns true
    every { storage.getBoolean("enable_shifts", true) } returns enabled
    every { storage.isShiftSystemEnabled() } returns enabled
    every { storage.getString("permissions_cache", null) } returns "$venue|$staff\ntpv-shifts:create,tpv-shifts:close,shifts:read,payments:create,payments:read,payments:refund"
    return ShiftRepository(api, db, storage, db.cachedShiftDao(), mockk(relaxed = true),
        appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext(), consultarAntesDeCerrar = consultarAntesDeCerrar)
}

/** Sólo los fixtures vecinos sin intents de Mesas: el Worker productivo exige su coordinador real. */
fun emptyTableReplayFixture(): com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator =
    mockk<com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator>().also { coordinator ->
        coEvery { coordinator.replayCurrentVenueBatch() } returns
            com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox.ReplayProgress(0, false)
    }
