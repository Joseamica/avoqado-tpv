package com.jaac.avoqado_tpv.core.presentation.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * D661 · ronda 4 (Minor). El bloqueo y el mantenimiento de la app son velos TRANSLÚCIDOS (0.84 y 0.76) que no se tragan los toques
 * (eso es aparte: `hz:b433ec6d:D665`), y la banda de pagados en línea les pasaba `bloqueada = false, mantenimiento = false`: se veía
 * y su «Ver» se tocaba debajo. Ahora lee las banderas de verdad con la MISMA regla (`avisoALaVista`) con la que el vigía apaga la
 * cabecera. Lo que decide la regla con cada bandera ya lo prueba `RecuadroPorPagarTest`
 * («Ruling 68 sin poder usar la terminal no hay aviso a la vista…»); aquí sólo el cableado, que en estas pruebas no se puede ejecutar
 * (`createComposeRule` sólo está en `androidTest`): se lee la fuente, como en `NoSeCobroCableadoTest`.
 */
class AvisosDeLaTiendaCableadoTest {
    private val navegacion = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()

    @Test
    fun `ronda 4 la banda lee el bloqueo y el mantenimiento de verdad - bajo sus velos no sale`() {
        val avisos = navegacion.substring(
            navegacion.indexOf("private fun AvisosDeLaTienda("),
            navegacion.indexOf("private fun BandaDePagadosEnLinea("),
        )
        assertThat(avisos).doesNotContain("bloqueada = false")
        assertThat(avisos).doesNotContain("mantenimiento = false")
        assertThat(avisos).contains("bloqueo.collectAsStateWithLifecycle()")
        assertThat(avisos).contains("mantenimiento.collectAsStateWithLifecycle()")
        // Los dos managers de la app (los mismos que pintan los velos en MainActivity), por el punto de entrada de Hilt.
        assertThat(navegacion).contains("fun lockScreenManager(): com.jaac.avoqado_tpv.core.data.manager.LockScreenManager")
        assertThat(navegacion).contains("fun maintenanceManager(): com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager")
        val montaje = navegacion.substringAfter("        AvisosDeLaTienda(").substringBefore("onVer = {")
        assertThat(montaje).contains("bloqueo = lockScreenManager.isLocked")
        assertThat(montaje).contains("mantenimiento = maintenanceManager.isInMaintenance")
    }
}
