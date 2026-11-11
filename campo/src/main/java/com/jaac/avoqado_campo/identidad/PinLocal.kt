package com.jaac.avoqado_campo.identidad

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal val CLAVES_DEL_PIN = listOf("pin.fallos", "pin.bloqueadoHasta", "pin.bloqueo")

/**
 * PIN sin señal contra el verificador de la sucursal. Fallos y bloqueo viven en el almacén.
 * La espera se mide con el reloj monotónico (si es el mismo arranque), así mover la hora del celular no la salta.
 */
class PinLocal(
    private val almacen: AlmacenSeguro,
    private val reloj: () -> Long,
    private val transcurrido: () -> Long = { 0L },
    private val arranque: () -> Int = { -1 },
) {
    private val candado = Mutex()

    suspend fun verificar(pin: String, v: VerificadorPin): ResultadoPinLocal = candado.withLock {
        val falta = faltaMs()
        if (falta > 0) return ResultadoPinLocal.Bloqueado((falta + 999) / 1000)

        var fallos = almacen.leer(FALLOS)?.toIntOrNull() ?: 0
        // Algoritmo o datos que no sabemos calcular: no se puede verificar y no cuenta (hay que entrar con señal).
        if (v.algoritmo != "PBKDF2-SHA256") return ResultadoPinLocal.Incorrecto(restantes(fallos))
        val ok = coincide(pin, v) ?: return ResultadoPinLocal.Incorrecto(restantes(fallos))

        if (ok) {
            reiniciar()
            return ResultadoPinLocal.Abre
        }
        fallos++
        val espera = esperaTrasFallos(fallos)
        almacen.escribir(
            mapOf(
                FALLOS to fallos.toString(),
                BLOQUEADO_HASTA to if (espera > 0) (reloj() + espera).toString() else null,
                BLOQUEO to if (espera > 0) "$espera|${transcurrido()}|${arranque()}" else null,
            )
        )
        return if (espera > 0) ResultadoPinLocal.Bloqueado(espera / 1000) else ResultadoPinLocal.Incorrecto(restantes(fallos))
    }

    /** Lo que falta de espera, en ms: por el monotónico si es el mismo arranque; si no, por la hora, con tope de 60 min. */
    private fun faltaMs(): Long {
        val tope = esperaTrasFallos(Int.MAX_VALUE)
        val p = almacen.leer(BLOQUEO)?.split("|")
        if (p != null && p.size == 3) {
            val espera = p[0].toLongOrNull(); val t0 = p[1].toLongOrNull(); val a = p[2].toIntOrNull()
            val t = transcurrido()
            if (espera != null && t0 != null && a != null && a != -1 && a == arranque() && t >= t0) {
                return minOf(tope, espera - (t - t0))
            }
        }
        val hasta = almacen.leer(BLOQUEADO_HASTA)?.toLongOrNull() ?: return 0L
        return minOf(tope, hasta - reloj())
    }

    /** Milisegundos de espera: <5 fallos 0; después 2^(f-5) minutos con tope de 60 (igual que el SERVIDOR). */
    fun esperaTrasFallos(fallos: Int): Long =
        if (fallos < 5) 0L else minOf(60L, 1L shl minOf(fallos - 5, 6)) * 60_000L

    fun reiniciar() = almacen.escribir(CLAVES_DEL_PIN.associateWith { null })

    private fun restantes(fallos: Int) = maxOf(0, 5 - fallos)

    /** null = no se pudo verificar (datos mal formados). */
    private suspend fun coincide(pin: String, v: VerificadorPin): Boolean? = withContext(Dispatchers.Default) {
        runCatching {
            val sal = Base64.getDecoder().decode(v.sal)
            val esperado = Base64.getDecoder().decode(v.hash)
            val spec = PBEKeySpec(pin.toCharArray(), sal, v.iteraciones, 256)
            try {
                val calculado = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                MessageDigest.isEqual(calculado, esperado)
            } finally { spec.clearPassword() }
        }.getOrNull()
    }

    private companion object {
        const val FALLOS = "pin.fallos"
        const val BLOQUEADO_HASTA = "pin.bloqueadoHasta"
        const val BLOQUEO = "pin.bloqueo"
    }
}
