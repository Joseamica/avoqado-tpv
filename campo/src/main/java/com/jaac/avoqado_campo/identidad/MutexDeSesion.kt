package com.jaac.avoqado_campo.identidad

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Un solo candado para todo lo que renueva o cambia la sesión (renovar, entrar, activar). */
@Singleton
class MutexDeSesion @Inject constructor() {
    val mutex = Mutex()
}
