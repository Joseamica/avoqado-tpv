package com.jaac.avoqado_campo.identidad

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinLocalTest {
    private var ahora = 1_000_000L
    private val almacen = AlmacenEnMemoria()
    private fun pin() = PinLocal(almacen) { ahora }

    private val sal = ByteArray(16) { it.toByte() }
    private fun verificador(p: String): VerificadorPin {
        val h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(p.toCharArray(), sal, 100_000, 256)).encoded
        return VerificadorPin("PBKDF2-SHA256", 100_000, Base64.getEncoder().encodeToString(sal),
            Base64.getEncoder().encodeToString(h))
    }
    private val v = verificador("1234")

    @Test fun `abre con el PIN bueno y no con el malo`() = runTest {
        assertEquals(ResultadoPinLocal.Abre, pin().verificar("1234", v))
        assertEquals(ResultadoPinLocal.Incorrecto(4), pin().verificar("1235", v))
    }

    @Test fun `cuatro fallos y el quinto bloquea 60 s, luego abre y reinicia`() = runTest {
        val p = pin()
        assertEquals(ResultadoPinLocal.Incorrecto(4), p.verificar("0000", v))
        assertEquals(ResultadoPinLocal.Incorrecto(3), p.verificar("0000", v))
        assertEquals(ResultadoPinLocal.Incorrecto(2), p.verificar("0000", v))
        assertEquals(ResultadoPinLocal.Incorrecto(1), p.verificar("0000", v))
        assertEquals(ResultadoPinLocal.Bloqueado(60), p.verificar("0000", v))
        // bloqueado: ni el bueno abre
        assertEquals(ResultadoPinLocal.Bloqueado(60), p.verificar("1234", v))
        ahora += 60_000
        assertEquals(ResultadoPinLocal.Abre, p.verificar("1234", v))
        assertEquals(ResultadoPinLocal.Incorrecto(4), p.verificar("0000", v))
    }

    @Test fun `espera tras fallos`() {
        val p = pin()
        val min = 60_000L
        assertEquals(0L, p.esperaTrasFallos(4))
        assertEquals(1 * min, p.esperaTrasFallos(5))
        assertEquals(2 * min, p.esperaTrasFallos(6))
        assertEquals(32 * min, p.esperaTrasFallos(10))
        assertEquals(60 * min, p.esperaTrasFallos(11))
        assertEquals(60 * min, p.esperaTrasFallos(20))
    }

    @Test fun `los fallos sobreviven a matar la app`() = runTest {
        repeat(5) { pin().verificar("0000", v) }
        assertTrue(pin().verificar("1234", v) is ResultadoPinLocal.Bloqueado)
    }

    @Test fun `un algoritmo desconocido no se puede verificar ni cuenta`() = runTest {
        val raro = v.copy(algoritmo = "otro")
        assertEquals(ResultadoPinLocal.Incorrecto(5), pin().verificar("1234", raro))
        assertEquals(ResultadoPinLocal.Abre, pin().verificar("1234", v))
    }

    @Test fun `reiniciar borra fallos y bloqueo`() = runTest {
        repeat(5) { pin().verificar("0000", v) }
        pin().reiniciar()
        assertEquals(ResultadoPinLocal.Abre, pin().verificar("1234", v))
    }
}
