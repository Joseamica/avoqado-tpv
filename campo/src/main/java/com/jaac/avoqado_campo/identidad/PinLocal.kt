package com.jaac.avoqado_campo.identidad

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface ResultadoPinLocal {
    object Abre : ResultadoPinLocal
    data class Incorrecto(val intentosAntesDeEsperar: Int) : ResultadoPinLocal
    data class Bloqueado(val reintentaEnSeg: Long) : ResultadoPinLocal
}

/** PIN sin señal contra el verificador de la sucursal. Fallos y bloqueo viven en el almacén. */
class PinLocal(private val almacen: AlmacenSeguro, private val reloj: () -> Long) {

    suspend fun verificar(pin: String, v: VerificadorPin): ResultadoPinLocal {
        val hasta = almacen.leer(BLOQUEADO_HASTA)?.toLongOrNull() ?: 0L
        val faltaMs = hasta - reloj()
        if (faltaMs > 0) return ResultadoPinLocal.Bloqueado((faltaMs + 999) / 1000)

        var fallos = almacen.leer(FALLOS)?.toIntOrNull() ?: 0
        // Algoritmo que no sabemos calcular: no se puede verificar y no cuenta (hay que entrar con señal).
        if (v.algoritmo != "PBKDF2-SHA256") return ResultadoPinLocal.Incorrecto(restantes(fallos))

        if (coincide(pin, v)) {
            reiniciar()
            return ResultadoPinLocal.Abre
        }
        fallos++
        val espera = esperaTrasFallos(fallos)
        almacen.escribir(mapOf(FALLOS to fallos.toString(), BLOQUEADO_HASTA to if (espera > 0) (reloj() + espera).toString() else null))
        return if (espera > 0) ResultadoPinLocal.Bloqueado(espera / 1000) else ResultadoPinLocal.Incorrecto(restantes(fallos))
    }

    /** Milisegundos de espera: <5 fallos 0; después 2^(f-5) minutos con tope de 60 (igual que el SERVIDOR). */
    fun esperaTrasFallos(fallos: Int): Long =
        if (fallos < 5) 0L else minOf(60L, 1L shl minOf(fallos - 5, 6)) * 60_000L

    fun reiniciar() = almacen.escribir(mapOf(FALLOS to null, BLOQUEADO_HASTA to null))

    private fun restantes(fallos: Int) = maxOf(0, 5 - fallos)

    private suspend fun coincide(pin: String, v: VerificadorPin): Boolean = withContext(Dispatchers.Default) {
        val sal = Base64.getDecoder().decode(v.sal)
        val esperado = Base64.getDecoder().decode(v.hash)
        val calculado = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), sal, v.iteraciones, 256)).encoded
        MessageDigest.isEqual(calculado, esperado)
    }

    private companion object {
        const val FALLOS = "pin.fallos"
        const val BLOQUEADO_HASTA = "pin.bloqueadoHasta"
    }
}
