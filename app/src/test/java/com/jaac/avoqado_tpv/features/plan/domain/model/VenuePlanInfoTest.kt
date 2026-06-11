package com.jaac.avoqado_tpv.features.plan.domain.model

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.plan.data.dto.PlanInfoDto
import com.jaac.avoqado_tpv.features.plan.data.dto.toDomainOrNull
import org.junit.Test

/**
 * Domain rules for plan-tier gating (`allowsFeature` + parsing).
 *
 * GOLDEN RULES under test:
 * - FAIL OPEN: null plan info / unknown code / unknown tier → allowed.
 * - exempt == true (grandfathered legacy / demo venues) → ALL gates off.
 * - Tier ranking FREE < PRO < PREMIUM < ENTERPRISE.
 */
class VenuePlanInfoTest {

    private fun plan(
        tier: PlanTier,
        grandfathered: Boolean = false,
        exempt: Boolean = false,
    ) = VenuePlanInfo(tier = tier, grandfathered = grandfathered, exempt = exempt)

    // ══════════════════════════════════════════════════════════════════════
    // Tier ranking
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `tier ranking is FREE then PRO then PREMIUM then ENTERPRISE`() {
        assertThat(PlanTier.FREE.ordinal).isLessThan(PlanTier.PRO.ordinal)
        assertThat(PlanTier.PRO.ordinal).isLessThan(PlanTier.PREMIUM.ordinal)
        assertThat(PlanTier.PREMIUM.ordinal).isLessThan(PlanTier.ENTERPRISE.ordinal)
    }

    @Test
    fun `higher tier always includes lower tier features`() {
        // PRO feature reachable from PRO, PREMIUM and ENTERPRISE — not FREE
        assertThat(plan(PlanTier.FREE).allowsFeature(PlanFeatureCatalog.PROMOTIONS)).isFalse()
        assertThat(plan(PlanTier.PRO).allowsFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()
        assertThat(plan(PlanTier.PREMIUM).allowsFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()
        assertThat(plan(PlanTier.ENTERPRISE).allowsFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()

        // PREMIUM feature reachable from PREMIUM and ENTERPRISE only
        assertThat(plan(PlanTier.FREE).allowsFeature(PlanFeatureCatalog.CFDI)).isFalse()
        assertThat(plan(PlanTier.PRO).allowsFeature(PlanFeatureCatalog.CFDI)).isFalse()
        assertThat(plan(PlanTier.PREMIUM).allowsFeature(PlanFeatureCatalog.CFDI)).isTrue()
        assertThat(plan(PlanTier.ENTERPRISE).allowsFeature(PlanFeatureCatalog.CFDI)).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Per-feature matrix (codes mirrored by exact name with the backend)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `per-feature matrix - PRO features gate FREE and open from PRO up`() {
        val proFeatures = listOf(
            PlanFeatureCatalog.REFERRAL_PROGRAM,
            PlanFeatureCatalog.PROMOTIONS,
            PlanFeatureCatalog.ADVANCED_REPORTS,
        )
        proFeatures.forEach { code ->
            assertThat(PlanFeatureCatalog.minTierFor(code)).isEqualTo(PlanTier.PRO)
            assertThat(plan(PlanTier.FREE).allowsFeature(code)).isFalse()
            assertThat(plan(PlanTier.PRO).allowsFeature(code)).isTrue()
            assertThat(plan(PlanTier.PREMIUM).allowsFeature(code)).isTrue()
            assertThat(plan(PlanTier.ENTERPRISE).allowsFeature(code)).isTrue()
        }
    }

    @Test
    fun `per-feature matrix - PREMIUM features gate FREE and PRO`() {
        val premiumFeatures = listOf(
            PlanFeatureCatalog.SERIALIZED_INVENTORY,
            PlanFeatureCatalog.INVENTORY_TRACKING,
            PlanFeatureCatalog.CFDI,
        )
        premiumFeatures.forEach { code ->
            assertThat(PlanFeatureCatalog.minTierFor(code)).isEqualTo(PlanTier.PREMIUM)
            assertThat(plan(PlanTier.FREE).allowsFeature(code)).isFalse()
            assertThat(plan(PlanTier.PRO).allowsFeature(code)).isFalse()
            assertThat(plan(PlanTier.PREMIUM).allowsFeature(code)).isTrue()
            assertThat(plan(PlanTier.ENTERPRISE).allowsFeature(code)).isTrue()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Fail open
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `null plan info fails open - everything allowed`() {
        val noInfo: VenuePlanInfo? = null
        assertThat(noInfo.allowsFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()
        assertThat(noInfo.allowsFeature(PlanFeatureCatalog.SERIALIZED_INVENTORY)).isTrue()
        assertThat(noInfo.allowsFeature("ANYTHING")).isTrue()
    }

    @Test
    fun `unknown feature code fails open even on FREE`() {
        // Order-flow codes are deliberately NOT in the catalog → never gated
        assertThat(plan(PlanTier.FREE).allowsFeature("CHARGING")).isTrue()
        assertThat(plan(PlanTier.FREE).allowsFeature("PRINTING")).isTrue()
        assertThat(plan(PlanTier.FREE).allowsFeature("SOME_FUTURE_CODE")).isTrue()
        assertThat(PlanFeatureCatalog.minTierFor("SOME_FUTURE_CODE")).isNull()
    }

    @Test
    fun `fromRaw parses known tiers case-insensitively and trims`() {
        assertThat(PlanTier.fromRaw("PRO")).isEqualTo(PlanTier.PRO)
        assertThat(PlanTier.fromRaw("pro")).isEqualTo(PlanTier.PRO)
        assertThat(PlanTier.fromRaw(" premium ")).isEqualTo(PlanTier.PREMIUM)
        assertThat(PlanTier.fromRaw("ENTERPRISE")).isEqualTo(PlanTier.ENTERPRISE)
        assertThat(PlanTier.fromRaw("FREE")).isEqualTo(PlanTier.FREE)
    }

    @Test
    fun `fromRaw returns null for unknown or absent tier - fail open`() {
        assertThat(PlanTier.fromRaw(null)).isNull()
        assertThat(PlanTier.fromRaw("")).isNull()
        assertThat(PlanTier.fromRaw("GOLD")).isNull() // tier this app version doesn't know
    }

    @Test
    fun `dto with absent tier field maps to null - fail open`() {
        assertThat(PlanInfoDto(tier = null).toDomainOrNull()).isNull()
        assertThat(PlanInfoDto().toDomainOrNull()).isNull()
    }

    @Test
    fun `dto with unknown tier maps to null - fail open`() {
        assertThat(PlanInfoDto(tier = "PLATINUM").toDomainOrNull()).isNull()
    }

    @Test
    fun `dto with absent booleans defaults to not grandfathered and not exempt`() {
        val info = PlanInfoDto(tier = "PRO").toDomainOrNull()
        assertThat(info).isNotNull()
        assertThat(info!!.tier).isEqualTo(PlanTier.PRO)
        assertThat(info.grandfathered).isFalse()
        assertThat(info.exempt).isFalse()
    }

    // ══════════════════════════════════════════════════════════════════════
    // exempt bypass
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `exempt venue bypasses all gates even on FREE`() {
        val exemptFree = plan(PlanTier.FREE, grandfathered = true, exempt = true)
        assertThat(exemptFree.allowsFeature(PlanFeatureCatalog.REFERRAL_PROGRAM)).isTrue()
        assertThat(exemptFree.allowsFeature(PlanFeatureCatalog.PROMOTIONS)).isTrue()
        assertThat(exemptFree.allowsFeature(PlanFeatureCatalog.ADVANCED_REPORTS)).isTrue()
        assertThat(exemptFree.allowsFeature(PlanFeatureCatalog.SERIALIZED_INVENTORY)).isTrue()
        assertThat(exemptFree.allowsFeature(PlanFeatureCatalog.CFDI)).isTrue()
    }

    @Test
    fun `grandfathered alone without exempt does NOT bypass gates`() {
        // `grandfathered` is informational (seat caps); `exempt` is the bypass.
        val grandfatheredFree = plan(PlanTier.FREE, grandfathered = true, exempt = false)
        assertThat(grandfatheredFree.allowsFeature(PlanFeatureCatalog.PROMOTIONS)).isFalse()
    }
}
