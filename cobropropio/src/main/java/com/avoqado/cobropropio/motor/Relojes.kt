package com.avoqado.cobropropio.motor

/**
 * Los plazos del motor, en milisegundos del reloj monotónico (Global Constraints). Antes de salir todo vence como
 * `NO_SALIO`; desde t0 (el commit de `ENVIADO`) corren los demás. El `connectTimeout` de 3 s vive en el transporte.
 */
internal data class Relojes(
    val esperaSesion: Long = 15_000,
    val esperaLectura: Long = 30_000,
    val esperaRespuesta: Long = 15_000,
    val peorCasoCajera: Long = 30_000,
    val esperaPermiso: Long = 11_000,
    val esperaReverso: Long = 10_000,
    val pausaEntreReversos: Long = 5_000,
    /** El último byte de un reverso se escribe antes de t0 + 45 s: un esfuerzo de puntualidad, no la corrección (parada 2). */
    val techoDelReverso: Long = 45_000,
    /** Lo más que H espera una lectura de la fila y `cortar` su veto; al vencer, lo más conservador (S1–S3). */
    val esperaLibreta: Long = 5_000,
) {
    /**
     * r2 familia E: una vuelta del reverso que empezó antes del techo puede seguir escribiendo hasta `esperaReverso` después.
     * El veto de renovar llaves y la reserva de la sesión cuentan hasta `t0 +` esto (reemplaza el ruling de r1 c1).
     */
    val finDelVeto: Long get() = techoDelReverso + esperaReverso
}
