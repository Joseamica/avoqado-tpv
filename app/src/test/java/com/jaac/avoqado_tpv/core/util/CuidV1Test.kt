package com.jaac.avoqado_tpv.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class CuidV1Test {
    @Test fun `v1 contiene reloj contador fingerprint y dos bloques aleatorios`() {
        val random = object : SecureRandom() { override fun nextInt(bound: Int) = 35 }
        val generator = CuidV1Generator(clock = { 1793188800000L }, fingerprint = "jvm1", random = random)
        val a = generator.next()
        val b = generator.next()
        assertThat(a).isEqualTo("c" + 1793188800000L.toString(36) + "0000jvm1000z000z")
        assertThat(b.substring(9, 13)).isEqualTo("0001")
        assertThat(a).matches("c[a-z0-9]{24}")
    }

    @Test fun `rafaga concurrente y reloj que retrocede conservan unicidad`() {
        var clock = 1793188800000L
        val generator = CuidV1Generator(clock = { clock })
        val pool = Executors.newFixedThreadPool(8)
        try {
            val first = pool.invokeAll((0 until 16000).map { Callable { generator.next() } }).map { it.get() }
            clock -= 60000
            val second = pool.invokeAll((0 until 16000).map { Callable { generator.next() } }).map { it.get() }
            assertThat((first + second).toSet()).hasSize(32000)
            assertThat((first + second).all { it.matches(Regex("c[a-z0-9]{24}")) }).isTrue()
        } finally { pool.shutdownNow() }
    }
}
