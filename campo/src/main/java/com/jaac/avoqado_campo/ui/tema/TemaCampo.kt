package com.jaac.avoqado_campo.ui.tema

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_campo.ui.tema.ColoresCampo as C

/**
 * TODAS las ranuras del esquema, como el tema claro de `:app`: una que falte cae al lila de Material3 (las tarjetas de «Mis
 * SIMs» se veían lilas por eso). `secondaryContainer` es gris a propósito, igual que en `:app` (los chips no se ponen amarillos).
 */
internal val Esquema = lightColorScheme(
    primary = C.Verde,
    onPrimary = C.Blanco,
    primaryContainer = C.VerdeClaro,
    onPrimaryContainer = C.VerdeTinta,
    inversePrimary = C.VerdeClaro,
    secondary = C.Naranja,
    onSecondary = C.Blanco,
    secondaryContainer = C.Gris100,
    onSecondaryContainer = C.Gris900,
    tertiary = C.Azul,
    onTertiary = C.Blanco,
    tertiaryContainer = C.AzulClaro,
    onTertiaryContainer = C.AzulTinta,
    background = C.Fondo,
    onBackground = C.Gris900,
    surface = C.Blanco,
    onSurface = C.Gris900,
    surfaceVariant = C.Gris100,
    onSurfaceVariant = C.Gris500,
    surfaceTint = Color.Transparent,
    inverseSurface = C.Gris800,
    inverseOnSurface = C.Gris50,
    error = C.Rojo,
    onError = C.Blanco,
    errorContainer = C.RojoClaro,
    onErrorContainer = C.RojoTinta,
    outline = C.Gris300,
    outlineVariant = C.Gris200,
    scrim = Color.Black,
    surfaceBright = C.Blanco,
    surfaceDim = C.Gris100,
    surfaceContainerLowest = C.Blanco,
    surfaceContainerLow = C.Blanco,
    surfaceContainer = C.Contenedor,
    surfaceContainerHigh = C.ContenedorAlto,
    surfaceContainerHighest = C.ContenedorMasAlto,
)

// Tarjetas de 14, botones de 12 y avisos de 10, como el mockup.
private val Formas = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// La letra del sistema (el mockup usa Roboto/-apple-system); sólo se ajustan pesos y tamaños.
private val Letras = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
    )
}

/** Sólo tema claro: se usa en la calle, a pleno sol. */
@Composable
fun TemaCampo(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Esquema, shapes = Formas, typography = Letras, content = content)
}
