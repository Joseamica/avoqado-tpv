package com.jaac.avoqado_tpv.core.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AvoqadoLoaderTimelineTest {

    @Test
    fun `seed appears before growth begins at the tail`() {
        val seedFrame = avoqadoLoaderFrameAt(0.10f)
        val growthFrame = avoqadoLoaderFrameAt(0.13f)

        assertThat(seedFrame.seedAlpha).isEqualTo(1f)
        assertThat(seedFrame.growthProgress).isEqualTo(0f)
        assertThat(growthFrame.growthProgress).isEqualTo(0f)
        assertThat(AVOQADO_GROWTH_PATH_DATA).startsWith("M 595 641")
    }

    @Test
    fun `green silhouette grows progressively and settles as the complete mark`() {
        val halfway = avoqadoLoaderFrameAt(0.375f)
        val settled = avoqadoLoaderFrameAt(0.70f)

        assertThat(halfway.growthProgress).isWithin(0.001f).of(0.5f)
        assertThat(settled.growthProgress).isEqualTo(1f)
        assertThat(settled.completeGreenAlpha).isEqualTo(1f)
    }

    @Test
    fun `all visible layers fade before the next cycle`() {
        val finalFrame = avoqadoLoaderFrameAt(1f)

        assertThat(finalFrame.seedAlpha).isEqualTo(0f)
        assertThat(finalFrame.tracedGreenAlpha).isEqualTo(0f)
        assertThat(finalFrame.completeGreenAlpha).isEqualTo(0f)
    }
}
