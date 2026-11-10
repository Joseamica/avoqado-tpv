package com.jaac.avoqado_campo.cola

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Un solo vaciado a la vez en todo el proceso: lo toman el `Subidor`, el `SubidorDeGracia` y el `BorradoDelCelular`. */
@Singleton
class MutexDeSubida @Inject constructor() {
    val mutex = Mutex()
}
