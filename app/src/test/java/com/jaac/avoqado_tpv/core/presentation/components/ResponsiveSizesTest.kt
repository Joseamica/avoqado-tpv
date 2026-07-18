package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResponsiveSizesTest {

    @Test
    fun `N62 square viewport uses larger accessible keypad tokens`() {
        val sizes = ResponsiveSizes.calculate(height = 480.dp, width = 480.dp)

        assertThat(sizes.screenProfile).isEqualTo(ScreenProfile.CompactSquare)
        assertThat(sizes.isSquareScreen).isTrue()
        assertThat(sizes.keyboardButtonSize).isEqualTo(60.dp)
        assertThat(sizes.keyboardActionWidth).isEqualTo(72.dp)
        assertThat(sizes.keyboardSpacing).isEqualTo(4.dp)
        assertThat(sizes.keyboardFontSize).isEqualTo(22)
    }

    @Test
    fun `compact portrait viewport keeps existing portrait keypad tokens`() {
        val sizes = ResponsiveSizes.calculate(height = 568.dp, width = 360.dp)

        assertThat(sizes.screenProfile).isEqualTo(ScreenProfile.CompactPortrait)
        assertThat(sizes.isSquareScreen).isFalse()
        assertThat(sizes.keyboardButtonSize).isEqualTo(80.dp)
        assertThat(sizes.keyboardActionWidth).isEqualTo(100.dp)
        assertThat(sizes.keyboardSpacing).isEqualTo(8.dp)
        assertThat(sizes.keyboardFontSize).isEqualTo(24)
    }

    @Test
    fun `N86 and A910S sized portrait viewport keeps existing keypad tokens`() {
        val sizes = ResponsiveSizes.calculate(height = 640.dp, width = 360.dp)

        assertThat(sizes.screenProfile).isEqualTo(ScreenProfile.RegularPortrait)
        assertThat(sizes.isSquareScreen).isFalse()
        assertThat(sizes.keyboardButtonSize).isEqualTo(80.dp)
        assertThat(sizes.keyboardActionWidth).isEqualTo(100.dp)
        assertThat(sizes.keyboardSpacing).isEqualTo(8.dp)
        assertThat(sizes.keyboardFontSize).isEqualTo(24)
    }

    @Test
    fun `legacy size categories remain stable`() {
        assertThat(ResponsiveSizes.calculate(480.dp, 480.dp).sizeCategory).isEqualTo("small")
        assertThat(ResponsiveSizes.calculate(568.dp, 360.dp).sizeCategory).isEqualTo("small")
        assertThat(ResponsiveSizes.calculate(640.dp, 360.dp).sizeCategory).isEqualTo("medium")
        assertThat(ResponsiveSizes.calculate(800.dp, 400.dp).sizeCategory).isEqualTo("large")
    }
}
