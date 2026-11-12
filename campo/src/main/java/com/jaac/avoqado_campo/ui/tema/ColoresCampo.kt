package com.jaac.avoqado_campo.ui.tema

import androidx.compose.ui.graphics.Color

/**
 * Tokens copiados de `:app` (core/presentation/theme/Color.kt, tema claro); `:campo` no depende de `:app`. Son los mismos
 * valores que pinta el mockup aprobado del 8-nov (`app-de-campo-recorrido-v2.html`). Sólo [AmbarTinta] no está en `:app`:
 * es el texto de los avisos ámbar del mockup (Amber 800), para que se lea sobre su fondo.
 */
object ColoresCampo {
    // Verde de la TPV: la acción principal, el turno abierto y lo que ya quedó.
    val Verde = Color(0xFF10B981) // AvoqadoGreen
    // Para TEXTO verde chico: el AvoqadoGreenDark (#059669) da 3.8:1 sobre blanco y lo mínimo es 4.5:1 (Codex rediseño
    // r2). Éste da 5.5:1 sobre blanco y 5.2:1 sobre el fondo. Lo cuida ContrasteTest.
    val VerdeTexto = Color(0xFF047857)
    val VerdeClaro = Color(0xFFD1FAE5) // LightPrimaryContainer
    val VerdeTinta = Color(0xFF064E3B) // LightOnPrimaryContainer

    // Naranja: avisos que no son error. [NaranjaSinRed] es el de «sin señal» (OfflineOrange).
    val Naranja = Color(0xFFF59E0B) // AvoqadoOrange
    val NaranjaSinRed = Color(0xFFE65100) // OfflineOrange
    // Para TEXTO: el OfflineOrange da 3.3:1 sobre su fondo y lo mínimo es 4.5:1 (Codex rediseño #5). Medidos: 6.4:1 la
    // tinta sobre el fondo de «sin señal», 5.2:1 el blanco sobre la insignia.
    val NaranjaTinta = Color(0xFF9A3412)
    val NaranjaFuerte = Color(0xFFC2410C)
    val AmbarTinta = Color(0xFF92400E) // mockup v2 (.cp-warn)

    val Azul = Color(0xFF3B82F6) // AvoqadoBlue
    val AzulClaro = Color(0xFFDBEAFE) // LightTertiaryContainer
    val AzulTinta = Color(0xFF1E3A8A) // LightOnTertiaryContainer

    // Rojo sólo para lo que sí es un error o un rechazo.
    val Rojo = Color(0xFFEF4444) // Error
    val RojoClaro = Color(0xFFFEE2E2) // LightErrorContainer
    val RojoTinta = Color(0xFF7F1D1D) // LightOnErrorContainer

    val Fondo = Color(0xFFFAF8F6) // LightBackground
    val Blanco = Color(0xFFFFFFFF)
    val Gris50 = Color(0xFFF9FAFB)
    val Gris100 = Color(0xFFF3F4F6)
    val Gris200 = Color(0xFFE5E7EB)
    val Gris300 = Color(0xFFD1D5DB)
    val Gris400 = Color(0xFF9CA3AF)
    val Gris500 = Color(0xFF6B7280)
    val Gris700 = Color(0xFF374151)
    val Gris800 = Color(0xFF1F2937)
    val Gris900 = Color(0xFF111827)

    // Contenedores de superficie explícitos (sin ellos Material3 pinta lila).
    val Contenedor = Color(0xFFF5F5F5) // LightSurfaceContainer
    val ContenedorAlto = Color(0xFFF0F0F0) // LightSurfaceContainerHigh
    val ContenedorMasAlto = Color(0xFFEBEBEB) // LightSurfaceContainerHighest
}
