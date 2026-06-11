package com.jaac.avoqado_tpv.features.plan.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.plan.data.dto.PlanInfoDto
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanFeatureCatalog
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanTier
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

/**
 * PlanManager — update/cache/clear behavior on top of the domain rules.
 *
 * Uses a map-backed [SecureStorage] mock so persistence (offline cache,
 * restart survival) is observable without Android.
 */
class PlanManagerTest {

    private lateinit var strings: MutableMap<String, String>
    private lateinit var booleans: MutableMap<String, Boolean>
    private lateinit var secureStorage: SecureStorage

    @Before
    fun setUp() {
        strings = mutableMapOf()
        booleans = mutableMapOf()
        secureStorage = mockk {
            every { putString(any(), any()) } answers { strings[firstArg()] = secondArg() }
            every { getString(any(), any()) } answers { strings[firstArg()] ?: secondArg() }
            every { putBoolean(any(), any()) } answers { booleans[firstArg()] = secondArg() }
            every { getBoolean(any(), any()) } answers { booleans[firstArg()] ?: secondArg() }
            every { remove(any()) } answers {
                strings.remove(firstArg<String>())
                booleans.remove(firstArg<String>())
            }
        }
    }

    private fun manager() = PlanManager(secureStorage)

    // ══════════════════════════════════════════════════════════════════════
    // Fail open (null plan / unknown tier / unknown code)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `no plan info ever received - everything allowed (fail open)`() {
        val planManager = manager()

        assertThat(planManager.planInfo.value).isNull()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.SERIALIZED_INVENTORY)).isTrue()
        assertThat(planManager.hasFeature("UNKNOWN_CODE")).isTrue()
    }

    @Test
    fun `update with null dto clears plan info - fail open`() {
        val planManager = manager()
        planManager.update(PlanInfoDto(tier = "FREE"))
        assertThat(planManager.hasFeature(PlanFeatureCatalog.PROMOTIONS)).isFalse()

        // Old server stopped sending `plan` → back to no gating
        planManager.update(null)

        assertThat(planManager.planInfo.value).isNull()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()
    }

    @Test
    fun `update with unknown tier clears plan info - fail open`() {
        val planManager = manager()
        planManager.update(PlanInfoDto(tier = "FREE"))

        planManager.update(PlanInfoDto(tier = "PLATINUM"))

        assertThat(planManager.planInfo.value).isNull()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()
    }

    @Test
    fun `unknown feature code always allowed regardless of tier`() {
        val planManager = manager()
        planManager.update(PlanInfoDto(tier = "FREE"))

        assertThat(planManager.hasFeature("CHARGING")).isTrue()
        assertThat(planManager.hasFeature("SOME_FUTURE_CODE")).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Tier gating through the manager
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `FREE venue is gated from PRO and PREMIUM features`() {
        val planManager = manager()

        planManager.update(PlanInfoDto(tier = "FREE", grandfathered = false, exempt = false))

        assertThat(planManager.hasFeature(PlanFeatureCatalog.REFERRAL_PROGRAM)).isFalse()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.PROMOTIONS)).isFalse()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.ADVANCED_REPORTS)).isFalse()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.SERIALIZED_INVENTORY)).isFalse()
    }

    @Test
    fun `PRO venue gets PRO features but not PREMIUM ones`() {
        val planManager = manager()

        planManager.update(PlanInfoDto(tier = "PRO"))

        assertThat(planManager.hasFeature(PlanFeatureCatalog.ADVANCED_REPORTS)).isTrue()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.SERIALIZED_INVENTORY)).isFalse()
    }

    @Test
    fun `ENTERPRISE venue gets everything`() {
        val planManager = manager()

        planManager.update(PlanInfoDto(tier = "ENTERPRISE"))

        assertThat(planManager.hasFeature(PlanFeatureCatalog.REFERRAL_PROGRAM)).isTrue()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.SERIALIZED_INVENTORY)).isTrue()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.CFDI)).isTrue()
    }

    @Test
    fun `requiredTierFor mirrors the catalog`() {
        val planManager = manager()

        assertThat(planManager.requiredTierFor(PlanFeatureCatalog.PROMOTIONS))
            .isEqualTo(PlanTier.PRO)
        assertThat(planManager.requiredTierFor(PlanFeatureCatalog.SERIALIZED_INVENTORY))
            .isEqualTo(PlanTier.PREMIUM)
        assertThat(planManager.requiredTierFor("NOT_GATED")).isNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    // exempt bypass (grandfathered legacy / demo venues, e.g. PlayTelecom)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `exempt FREE venue bypasses all gates`() {
        val planManager = manager()

        planManager.update(PlanInfoDto(tier = "FREE", grandfathered = true, exempt = true))

        assertThat(planManager.hasFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.ADVANCED_REPORTS)).isTrue()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.SERIALIZED_INVENTORY)).isTrue()
    }

    @Test
    fun `grandfathered without exempt does not bypass gates`() {
        val planManager = manager()

        planManager.update(PlanInfoDto(tier = "FREE", grandfathered = true, exempt = false))

        assertThat(planManager.hasFeature(PlanFeatureCatalog.PROMOTIONS)).isFalse()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Persistence (offline cache) + clearCache
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `update persists plan info so a fresh manager reads it back`() {
        manager().update(PlanInfoDto(tier = "PRO", grandfathered = true, exempt = false))

        // Simulate app restart: new instance over the same storage
        val restarted = manager()

        val info = restarted.planInfo.value
        assertThat(info).isNotNull()
        assertThat(info!!.tier).isEqualTo(PlanTier.PRO)
        assertThat(info.grandfathered).isTrue()
        assertThat(info.exempt).isFalse()
        assertThat(restarted.hasFeature(PlanFeatureCatalog.SERIALIZED_INVENTORY)).isFalse()
    }

    @Test
    fun `clearCache wipes storage and fails open`() {
        val planManager = manager()
        planManager.update(PlanInfoDto(tier = "FREE"))
        assertThat(planManager.hasFeature(PlanFeatureCatalog.PROMOTIONS)).isFalse()

        planManager.clearCache()

        assertThat(planManager.planInfo.value).isNull()
        assertThat(planManager.hasFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()
        // And a fresh manager over the same storage sees nothing either
        assertThat(manager().planInfo.value).isNull()
    }
}
