package com.jaac.avoqado_campo.ui.tema

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val Esquema = lightColorScheme(
    primary = ColoresCampo.Verde,
    onPrimary = ColoresCampo.Blanco,
    primaryContainer = ColoresCampo.VerdeOscuro,
    secondary = ColoresCampo.Naranja,
    tertiary = ColoresCampo.Azul,
    background = ColoresCampo.Gris50,
    onBackground = ColoresCampo.Gris900,
    surface = ColoresCampo.Blanco,
    onSurface = ColoresCampo.Gris900,
    surfaceVariant = ColoresCampo.Gris100,
    onSurfaceVariant = ColoresCampo.Gris500,
    outline = ColoresCampo.Gris200,
    error = ColoresCampo.Rojo,
)

/** Sólo tema claro (decisión del bloque 2). */
@Composable
fun TemaCampo(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Esquema, content = content)
}
