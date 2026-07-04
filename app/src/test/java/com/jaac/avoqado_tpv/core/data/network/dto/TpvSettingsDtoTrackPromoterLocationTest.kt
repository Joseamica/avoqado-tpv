package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import org.junit.Test

/**
 * trackPromoterLocation ("cambaceo") flag on the terminal-config tpvSettings payload.
 * Backend sends it venue-level; old servers omit it -> MUST default to false.
 */
class TpvSettingsDtoTrackPromoterLocationTest {

    private val gson = Gson()

    @Test
    fun `toDomain maps trackPromoterLocation true`() {
        val dto = gson.fromJson("""{"trackPromoterLocation": true}""", TpvSettingsDto::class.java)
        assertThat(dto.toDomain().trackPromoterLocation).isTrue()
    }

    @Test
    fun `toDomain defaults to false when backend omits the field (old server)`() {
        val dto = gson.fromJson("""{"showTipScreen": true}""", TpvSettingsDto::class.java)
        assertThat(dto.toDomain().trackPromoterLocation).isFalse()
    }

    @Test
    fun `toDto round-trips the flag (regression - settings save must not drop it)`() {
        val settings = TpvSettings(trackPromoterLocation = true)
        assertThat(settings.toDto().trackPromoterLocation).isTrue()
    }

    @Test
    fun `regression - existing fields still parse with the new flag present`() {
        val dto = gson.fromJson(
            """{"showTipScreen": false, "enableShifts": false, "trackPromoterLocation": true}""",
            TpvSettingsDto::class.java,
        )
        val domain = dto.toDomain()
        assertThat(domain.showTipScreen).isFalse()
        assertThat(domain.enableShifts).isFalse()
        assertThat(domain.trackPromoterLocation).isTrue()
    }
}
