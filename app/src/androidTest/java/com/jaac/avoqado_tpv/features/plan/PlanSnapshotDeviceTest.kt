package com.jaac.avoqado_tpv.features.plan

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.plan.data.PlanManager
import com.jaac.avoqado_tpv.features.plan.data.dto.PlanInfoDto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run each method in a separate instrumentation process on the isolated .hybridqa APK.
 * seedPaidAccess → disable networking → recoverOfflineAfterProcessDeath → restore networking → applyServerChange.
 * No fixture may touch an installed production/dev session.
 */
@RunWith(AndroidJUnit4::class)
class PlanSnapshotDeviceTest {
    private fun storage(): SecureStorage {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.jaac.avoqado_tpv.hybridqa") { "Requires isolated QA application ID" }
        return SecureStorage(context)
    }

    @Test fun seedPaidAccess() {
        val store = storage()
        store.saveVenueId("FULLTEST-hybrid-device")
        PlanManager(store).update(PlanInfoDto(
            tier = "FREE", accessSchemaVersion = 1,
            accessObservedAt = "2026-09-27T00:00:00Z", grantedFeatureCodes = listOf("CFDI"),
        ), "FULLTEST-hybrid-device")
        assertTrue(PlanManager(store).hasFeature("CFDI"))
        assertFalse(PlanManager(store).hasFeature("INVENTORY_TRACKING"))
    }

    @Test fun recoverOfflineAfterProcessDeath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Disable device networking for this check", network.activeNetwork)
        val store = storage()
        assertEquals("FULLTEST-hybrid-device", store.getVenueId())
        assertTrue(PlanManager(store).hasFeature("CFDI"))
        assertFalse(PlanManager(store).hasFeature("INVENTORY_TRACKING"))
        PlanManager(store).update(null, "FULLTEST-hybrid-device")
        assertTrue(PlanManager(store).hasFeature("CFDI"))
    }

    @Test fun applyServerChange() {
        val store = storage()
        PlanManager(store).update(PlanInfoDto(
            tier = "FREE", accessSchemaVersion = 1,
            accessObservedAt = "2026-09-27T00:01:00Z", grantedFeatureCodes = listOf("INVENTORY_TRACKING"),
        ), "FULLTEST-hybrid-device")
        assertFalse(PlanManager(store).hasFeature("CFDI"))
        assertTrue(PlanManager(store).hasFeature("INVENTORY_TRACKING"))
        val reopened = storage()
        assertFalse(PlanManager(reopened).hasFeature("CFDI"))
        assertTrue(PlanManager(reopened).hasFeature("INVENTORY_TRACKING"))
    }
}
