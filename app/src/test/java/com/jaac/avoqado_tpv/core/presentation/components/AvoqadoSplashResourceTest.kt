package com.jaac.avoqado_tpv.core.presentation.components

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class AvoqadoSplashResourceTest {

    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun `native seed keeps the display pixel canvas used by PAX firmware`() {
        val vector = seedVector().documentElement

        assertThat(vector.getAttributeNS(androidNamespace, "width")).isEqualTo("108dp")
        assertThat(vector.getAttributeNS(androidNamespace, "height")).isEqualTo("108dp")
    }

    @Test
    fun `native splash starts with the seed-only drawable`() {
        val theme = xml("src/main/res/values/themes.xml")
        val items = theme.getElementsByTagName("item")
        val animatedIcon = (0 until items.length)
            .map { items.item(it) }
            .first { it.attributes.getNamedItem("name")?.nodeValue == "windowSplashScreenAnimatedIcon" }

        assertThat(animatedIcon.textContent.trim()).isEqualTo("@drawable/avoqado_splash_seed")

        val paths = seedVector().getElementsByTagName("path")

        assertThat(paths.length).isEqualTo(1)
        assertThat(paths.item(0).attributes.getNamedItemNS(androidNamespace, "fillColor").nodeValue)
            .isEqualTo("#D97452")
    }

    private fun seedVector() = xml("src/main/res/drawable/avoqado_splash_seed.xml")

    private fun xml(path: String) = DocumentBuilderFactory.newInstance().run {
        isNamespaceAware = true
        newDocumentBuilder().parse(File(path))
    }
}
