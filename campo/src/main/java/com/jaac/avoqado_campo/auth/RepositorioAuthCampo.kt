package com.jaac.avoqado_campo.auth

sealed interface ResultadoLogin {
    data class Ok(val venueId: String) : ResultadoLogin
    data class Falla(val mensaje: String) : ResultadoLogin
}

interface RepositorioAuthCampo {
    /**
     * POST /api/v1/mobile/auth/login
     * Un 401 aquí significa "credenciales malas", NUNCA "la sesión venció".
     */
    suspend fun entrar(correo: String, contrasena: String): ResultadoLogin
}
