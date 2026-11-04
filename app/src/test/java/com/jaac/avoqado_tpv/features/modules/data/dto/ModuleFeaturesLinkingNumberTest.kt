package com.jaac.avoqado_tpv.features.modules.data.dto

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleConfig
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleFeatures
import com.jaac.avoqado_tpv.features.modules.domain.model.VenueModule
import com.jaac.avoqado_tpv.features.modules.domain.repository.linkingNumberEnabled
import org.junit.Test

/**
 * El interruptor del número de vinculación vive en `features.linkingNumber` del módulo SERIALIZED_INVENTORY (nivel
 * organización), como `enablePortabilidad`. La TPV guarda los módulos en su caché con `toDto()` y los vuelve a leer con
 * `toDomain()`: si el campo se perdiera en ese viaje, el campo del número desaparecería al reiniciar sin señal.
 */
class ModuleFeaturesLinkingNumberTest {
    private val gson = Gson()

    private fun json(features: String) =
        """{"code":"SERIALIZED_INVENTORY","config":{"labels":{"item":"SIM"},"features":$features}}"""

    @Test fun `features linkingNumber sobrevive el viaje del servidor al dominio y a la cache y de vuelta`() {
        val delServidor = gson.fromJson(json("""{"enablePortabilidad":true,"linkingNumber":true}"""), VenueModuleDto::class.java)

        val dominio = delServidor.toDomain()
        val deLaCache = gson.fromJson(gson.toJson(dominio.toDto()), VenueModuleDto::class.java).toDomain()

        assertThat(dominio.config.features.linkingNumber).isTrue()
        assertThat(deLaCache.config.features.linkingNumber).isTrue()
        assertThat(deLaCache.config.features.enablePortabilidad).isTrue()
        assertThat(ModuleFeaturesDto(linkingNumber = false).toDomain().toDto().linkingNumber).isFalse()
    }

    @Test fun `sin el campo, o con un servidor viejo, el interruptor queda apagado`() {
        assertThat(gson.fromJson(json("""{"enablePortabilidad":true}"""), VenueModuleDto::class.java).toDomain().config.features.linkingNumber)
            .isFalse()
        assertThat(ModuleFeaturesDto().toDomain().linkingNumber).isFalse()
        assertThat(ModuleFeatures().linkingNumber).isFalse()
    }

    @Test fun `el interruptor solo cuenta en SERIALIZED_INVENTORY activo`() {
        fun modulo(code: String, active: Boolean, on: Boolean) =
            VenueModule("m-$code", code, code, ModuleConfig(features = ModuleFeatures(linkingNumber = on)), active)

        assertThat(listOf(modulo("SERIALIZED_INVENTORY", active = true, on = true)).linkingNumberEnabled()).isTrue()
        assertThat(listOf(modulo("SERIALIZED_INVENTORY", active = false, on = true)).linkingNumberEnabled()).isFalse()
        assertThat(listOf(modulo("SERIALIZED_INVENTORY", active = true, on = false)).linkingNumberEnabled()).isFalse()
        assertThat(listOf(modulo("ATTENDANCE_TRACKING", active = true, on = true)).linkingNumberEnabled()).isFalse()
        assertThat(emptyList<VenueModule>().linkingNumberEnabled()).isFalse()
    }
}
