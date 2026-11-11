package com.jaac.avoqado_campo.version

import java.io.File

/**
 * Lo que el SERVIDOR ofrece por el carril de la app de campo (`check-update?platform=ANDROID_CAMPO`). [obligatoria] sale del
 * `minVersionCode` (la FORCE pendiente más alta), nunca del modo de esta fila (Codex r1 #3): con FORCE 4 y luego BANNER 5,
 * quien está en 3 sigue obligado aunque se le ofrezca la 5.
 */
data class VersionNueva(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val tamano: Long,
    val obligatoria: Boolean,
    val notas: String?,
)

/** Cómo quedó un archivo bajado o revisado. `Mala` = esa versión no se instala ni se vuelve a ofrecer (D-3). */
sealed interface Bajada {
    data class Lista(val archivo: File) : Bajada
    object SinRed : Bajada
    data class Mala(val motivo: String) : Bajada
}
